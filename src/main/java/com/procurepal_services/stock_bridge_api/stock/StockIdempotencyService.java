package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes a stock write safe to send twice - see {@code V35__stock_idempotency_keys.sql} for the
 * failure this closes (a lost response, a retry, a delivery recorded twice).
 *
 * <h2>How a duplicate is caught, including two arriving at once</h2>
 * The key row is claimed with {@code INSERT ... ON CONFLICT DO NOTHING} inside the transaction
 * that also writes the stock movement, and its response is stored before that transaction
 * commits. A second request with the same key either:
 * <ul>
 *   <li>arrives after the first committed - the insert does nothing, and the stored response is
 *       read back and returned; or</li>
 *   <li>arrives while the first is still in flight - Postgres makes the insert WAIT on the
 *       uncommitted conflicting row, then behaves as above once it commits. If the first rolls
 *       back instead, the wait ends with the insert succeeding and the second request runs for
 *       real, which is what a retry of a failed request should do.</li>
 * </ul>
 * Either way the ledger gets exactly one row, without an application-level lock.
 *
 * <p>The response is replayed through the same {@link JsonMapper} the HTTP layer uses, so a
 * replay is byte-for-byte the shape the first caller would have seen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockIdempotencyService {

    /** Long enough to cover a phone that was offline over a weekend, short enough to stay small. */
    static final Duration RETENTION = Duration.ofDays(7);

    static final int MAX_KEY_LENGTH = 100;

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper jsonMapper;

    /** A stock-write result, and whether it is the stored answer to an earlier identical request. */
    public record Result(StockMutationResponse response, boolean replayed) {
    }

    /**
     * Runs {@code action} at most once per {@code (tenant, key)}.
     *
     * @param operation which write this is ({@code STOCK_IN}, {@code STOCK_OUT}, {@code ADJUSTMENT})
     * @param request the request body, hashed so a key reused for a different request is refused
     */
    @Transactional
    public Result execute(
            String idempotencyKey,
            String operation,
            UUID productId,
            Object request,
            Supplier<StockMutationResponse> action) {
        validate(idempotencyKey);
        UUID clientId = TenantContext.get();
        String requestHash = hash(operation, productId, request);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("key", idempotencyKey)
                .addValue("operation", operation)
                .addValue("productId", productId)
                .addValue("requestHash", requestHash);

        int claimed = jdbc.update(
                """
                INSERT INTO stock_idempotency_keys (client_id, idempotency_key, operation, product_id, request_hash)
                VALUES (:clientId, :key, :operation, :productId, :requestHash)
                ON CONFLICT (client_id, idempotency_key) DO NOTHING
                """,
                params);

        if (claimed == 0) {
            return new Result(replay(params, requestHash), true);
        }

        StockMutationResponse response = action.get();
        jdbc.update(
                """
                UPDATE stock_idempotency_keys SET response_body = :responseBody
                WHERE client_id = :clientId AND idempotency_key = :key
                """,
                params.addValue("responseBody", jsonMapper.writeValueAsString(response)));
        return new Result(response, false);
    }

    private StockMutationResponse replay(MapSqlParameterSource params, String requestHash) {
        List<StoredKey> rows = jdbc.query(
                """
                SELECT request_hash, response_body FROM stock_idempotency_keys
                WHERE client_id = :clientId AND idempotency_key = :key
                """,
                params,
                (rs, rowNum) -> new StoredKey(rs.getString("request_hash"), rs.getString("response_body")));

        if (rows.isEmpty()) {
            // The conflicting row existed when we inserted and is gone now: swept between the two
            // statements. Vanishingly unlikely inside a 7-day window, but answering it as "in
            // progress" lets the client retry rather than guessing.
            throw new IdempotencyKeyInProgressException();
        }
        StoredKey stored = rows.get(0);
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        if (stored.responseBody() == null) {
            // Unreachable while the response is written in the claiming transaction - the row is
            // never visible to anyone without it. Kept as a refusal rather than an NPE.
            throw new IdempotencyKeyInProgressException();
        }
        return jsonMapper.readValue(stored.responseBody(), StockMutationResponse.class);
    }

    private static void validate(String idempotencyKey) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(MAX_KEY_LENGTH);
        }
    }

    /**
     * When the write happened, not what it was. The offline outbox stamps these on a resend (the
     * phone only learns it was offline once the first try fails), so a retry of a write whose reply
     * was lost carries them while the original did not. Hashing them turned that retry into a
     * "key reused" conflict for a write the server had already recorded once.
     */
    private static final List<String> TIMING_FIELDS = List.of("occurredAt", "recordedOffline", "countedAt");

    private String hash(String operation, UUID productId, Object request) {
        JsonNode body = jsonMapper.valueToTree(request);
        if (body instanceof ObjectNode object) {
            object.remove(TIMING_FIELDS);
        }
        String material = operation + '|' + productId + '|' + jsonMapper.writeValueAsString(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    /** Daily, and harmless to run on several instances at once - it only deletes expired rows. */
    @Scheduled(cron = "${app.stock.idempotency.sweep-cron:0 30 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpiredKeys() {
        int deleted = jdbc.update(
                "DELETE FROM stock_idempotency_keys WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", OffsetDateTime.now().minus(RETENTION)));
        if (deleted > 0) {
            log.info("Swept {} expired stock idempotency keys", deleted);
        }
    }

    private record StoredKey(String requestHash, String responseBody) {
    }
}
