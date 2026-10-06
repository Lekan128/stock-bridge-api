package com.procurepal_services.stock_bridge_api.stock;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a stock write changed that cannot be read back off the movement itself, kept just long
 * enough to void it (V39, decision D8). Written in the same transaction as the movement, so the
 * two commit or roll back together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockWriteUndoStore {

    /** Far longer than the two-minute undo window; only so a slow sweep never races a live undo. */
    private static final Duration RETENTION = Duration.ofDays(1);

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param notUndoableReason set when the write did something a void must not quietly undo -
     *     its void is refused with this sentence.
     */
    public record Snapshot(
            UUID movementId,
            UUID clientId,
            UUID createdBy,
            OffsetDateTime createdAt,
            BigDecimal priorCostPrice,
            UUID packId,
            BigDecimal priorPackLastCostPrice,
            String priorPackVendorSku,
            String notUndoableReason) {
    }

    public void record(
            UUID movementId,
            UUID clientId,
            UUID createdBy,
            BigDecimal priorCostPrice,
            UUID packId,
            BigDecimal priorPackLastCostPrice,
            String priorPackVendorSku,
            String notUndoableReason) {
        jdbc.update(
                """
                INSERT INTO stock_write_undo (
                    movement_id, client_id, created_by, prior_cost_price,
                    pack_id, prior_pack_last_cost_price, prior_pack_vendor_sku, not_undoable_reason)
                VALUES (:movementId, :clientId, :createdBy, :priorCostPrice,
                    :packId, :priorPackLastCostPrice, :priorPackVendorSku, :notUndoableReason)
                """,
                new MapSqlParameterSource()
                        .addValue("movementId", movementId)
                        .addValue("clientId", clientId)
                        .addValue("createdBy", createdBy)
                        .addValue("priorCostPrice", priorCostPrice)
                        .addValue("packId", packId)
                        .addValue("priorPackLastCostPrice", priorPackLastCostPrice)
                        .addValue("priorPackVendorSku", priorPackVendorSku)
                        .addValue("notUndoableReason", notUndoableReason));
    }

    public Optional<Snapshot> find(UUID clientId, UUID movementId) {
        List<Snapshot> rows = jdbc.query(
                """
                SELECT movement_id, client_id, created_by, created_at, prior_cost_price,
                       pack_id, prior_pack_last_cost_price, prior_pack_vendor_sku, not_undoable_reason
                  FROM stock_write_undo
                 WHERE client_id = :clientId AND movement_id = :movementId
                """,
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("movementId", movementId),
                (rs, rowNum) -> new Snapshot(
                        rs.getObject("movement_id", UUID.class),
                        rs.getObject("client_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getBigDecimal("prior_cost_price"),
                        rs.getObject("pack_id", UUID.class),
                        rs.getBigDecimal("prior_pack_last_cost_price"),
                        rs.getString("prior_pack_vendor_sku"),
                        rs.getString("not_undoable_reason")));
        return rows.stream().findFirst();
    }

    /** Daily, and harmless to run on several instances at once - it only deletes expired rows. */
    @Scheduled(cron = "${app.stock.undo.sweep-cron:0 40 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpired() {
        int deleted = jdbc.update(
                "DELETE FROM stock_write_undo WHERE created_at < :cutoff",
                new MapSqlParameterSource("cutoff", OffsetDateTime.now().minus(RETENTION)));
        if (deleted > 0) {
            log.info("Swept {} expired stock undo snapshots", deleted);
        }
    }
}
