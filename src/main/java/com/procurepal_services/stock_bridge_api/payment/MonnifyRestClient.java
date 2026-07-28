package com.procurepal_services.stock_bridge_api.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitCommand;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitResult;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Monnify over HTTP. Every endpoint path, field name and status value below was
 * confirmed against the live developer documentation (see MONNIFY_ENDPOINTS in
 * this class and the module report) rather than recalled.
 *
 * <h2>Token handling</h2>
 * Monnify's bearer token is bought with an HTTP Basic {@code base64(apiKey:secretKey)}
 * login and lasts an hour. Fetching one per request would triple our call volume
 * and add a round trip of latency to every checkout, so it is cached in-process
 * and discarded {@code tokenRefreshMargin} before the provider-stated expiry.
 * A 401 also discards it and retries once, which covers the cases a clock cannot:
 * a token revoked early, or a stale cache after credentials are rotated.
 *
 * <p>The cache is per-instance and deliberately not distributed. Two app
 * instances simply hold two valid tokens - Monnify issues them freely - and that
 * is far cheaper than a shared store that has to be invalidated correctly.
 *
 * <h2>Logging</h2>
 * Every interaction logs the paymentReference, because that is the string a
 * support conversation starts from. Nothing here ever logs the secret key, the
 * bearer token, or {@code cardDetails} from a response body - which is also why
 * failures log {@code responseMessage} rather than the whole payload.
 */
@Component
@Slf4j
public class MonnifyRestClient implements MonnifyClient {

    // Confirmed against https://developers.monnify.com — see the module report for
    // what was verified verbatim versus inferred.
    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String INIT_TRANSACTION_PATH = "/api/v1/merchant/transactions/init-transaction";
    private static final String TRANSACTION_STATUS_PATH = "/api/v2/transactions/";

    /**
     * Monnify renders timestamps in Nigerian local time with no offset
     * ("26/02/2020 09:38:13 AM"). Parsing that as UTC would file every West
     * African payment an hour early, which quietly corrupts daily revenue
     * reporting and any "paid before cutoff" rule built on it later.
     */
    private static final DateTimeFormatter PAID_ON_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy hh:mm:ss a", Locale.ENGLISH);

    private static final ZoneId MONNIFY_ZONE = ZoneId.of("Africa/Lagos");

    /**
     * Deliberately a private, plain mapper rather than the application's configured
     * one. Two reasons: Spring Boot 4 no longer auto-configures a
     * com.fasterxml.jackson.databind.ObjectMapper bean, and - more importantly -
     * reading a provider's response must not be steered by settings chosen for OUR
     * API's output. A future non-null inclusion rule or naming strategy applied to
     * our own DTOs has no business changing how a Monnify payload is parsed.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MonnifyProperties properties;
    private final RestClient restClient;

    /**
     * Volatile so a token fetched by one request thread is visible to the next
     * without either taking a lock on the happy path; the synchronized block in
     * {@link #bearerToken()} only serialises the (rare) refresh.
     */
    private volatile CachedToken cachedToken;

    /**
     * {@code @Autowired} is required, not decorative: there are two constructors, and
     * without it Spring cannot choose and falls back to looking for a no-arg one.
     * That failure surfaces only when the REAL bean is instantiated, which every
     * test in this package avoids by substituting the client - so the suite happily
     * passed while the application could not start. MonnifyRestClientTest now builds
     * one through this constructor for exactly that reason.
     */
    @Autowired
    public MonnifyRestClient(MonnifyProperties properties) {
        this(properties, MonnifyRestClientFactory.build(properties));
    }

    /** Test seam: lets the wire-level test drive a stubbed request factory through the same code. */
    MonnifyRestClient(MonnifyProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    @PostConstruct
    void logConfigurationStatus() {
        if (!isConfigured()) {
            log.warn("Monnify is not configured (app.monnify api-key/secret-key/contract-code/base-url must all "
                    + "be set) - card checkout will return 503 until this is fixed. Pay-on-delivery is unaffected.");
        } else {
            // Never the key itself; the prefix is enough to tell MK_TEST from MK_PROD
            // at a glance, which is the mistake worth catching in a startup log.
            log.info("Monnify configured against {} with contract code {} (api key prefix {})",
                    properties.baseUrl(), properties.contractCode(), apiKeyPrefix());
        }
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public MonnifyInitResult initializeTransaction(MonnifyInitCommand command) {
        requireConfigured();

        // A LinkedHashMap rather than a record so absent optional fields are simply
        // never added. Monnify rejects some fields sent as explicit nulls, and the
        // app-wide Jackson non_null inclusion applies to serialization of our API
        // responses, not necessarily to whatever mapper builds this body.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", command.amount());
        body.put("customerName", command.customerName());
        body.put("customerEmail", command.customerEmail());
        body.put("paymentReference", command.paymentReference());
        body.put("paymentDescription", command.paymentDescription());
        body.put("currencyCode", command.currencyCode());
        body.put("contractCode", properties.contractCode());
        if (properties.redirectUrl() != null && !properties.redirectUrl().isBlank()) {
            body.put("redirectUrl", properties.redirectUrl());
        }

        log.info("Monnify init-transaction paymentReference={} amount={} {}",
                command.paymentReference(), command.amount(), command.currencyCode());

        JsonNode responseBody = withBearerToken(token -> restClient
                .post()
                .uri(INIT_TRANSACTION_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                // Suppress RestClient's default throw-on-error so a 401 can be told
                // apart from a 500 and retried with a fresh token.
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class), command.paymentReference());

        String checkoutUrl = text(responseBody, "checkoutUrl");
        String transactionReference = text(responseBody, "transactionReference");
        if (checkoutUrl == null || transactionReference == null) {
            throw new MonnifyApiException(
                    "Monnify init-transaction returned no checkoutUrl/transactionReference for paymentReference "
                            + command.paymentReference());
        }

        log.info("Monnify init-transaction ok paymentReference={} transactionReference={}",
                command.paymentReference(), transactionReference);
        return new MonnifyInitResult(
                checkoutUrl,
                transactionReference,
                // Prefer what Monnify echoed back, but never lose ours if it is absent.
                text(responseBody, "paymentReference") != null
                        ? text(responseBody, "paymentReference")
                        : command.paymentReference());
    }

    @Override
    public MonnifyTransactionStatus getTransactionStatus(String transactionReference) {
        requireConfigured();

        log.info("Monnify verify transactionReference={}", transactionReference);

        // Monnify references look like "MNFY|20200226093601|002095". Left unencoded
        // the pipes make the request path invalid, and the failure mode is the worst
        // one available: a 404 that reads exactly like "no such transaction", i.e. a
        // paid order silently treated as unpaid.
        //
        // Passed as a URI VARIABLE rather than pre-encoded into the path on purpose.
        // RestClient's default DefaultUriBuilderFactory runs in TEMPLATE_AND_VALUES
        // mode, which strictly encodes expanded variables - so it produces %7C - but
        // also encodes the template itself, which would turn a hand-encoded %7C into
        // %257C and break it just as thoroughly. Let one layer do the encoding.
        // MonnifyRestClientTest asserts the resulting URI, because reasoning about
        // this is exactly how it gets broken again.
        JsonNode responseBody = withBearerToken(token -> restClient
                .get()
                .uri(TRANSACTION_STATUS_PATH + "{transactionReference}", transactionReference)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class), transactionReference);

        MonnifyTransactionStatus status = new MonnifyTransactionStatus(
                text(responseBody, "transactionReference") != null
                        ? text(responseBody, "transactionReference")
                        : transactionReference,
                text(responseBody, "paymentReference"),
                decimal(responseBody, "amountPaid"),
                decimal(responseBody, "totalPayable"),
                text(responseBody, "paymentStatus"),
                text(responseBody, "paymentMethod"),
                parsePaidOn(text(responseBody, "paidOn")),
                responseBody.toString());

        log.info("Monnify verify result paymentReference={} transactionReference={} paymentStatus={} amountPaid={}",
                status.paymentReference(), status.transactionReference(),
                status.paymentStatus(), status.amountPaid());
        return status;
    }

    // ------------------------------------------------------------------------
    // Token cache
    // ------------------------------------------------------------------------

    /**
     * Runs one authenticated call, transparently re-authenticating once on a 401.
     *
     * @param reference the paymentReference or transactionReference the call is
     *     about, used only to make the log line traceable
     */
    private JsonNode withBearerToken(Function<String, ResponseEntity<String>> call, String reference) {
        ResponseEntity<String> response = execute(call, bearerToken(), reference);

        if (response.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            // The cached token was rejected. That is expected occasionally (early
            // revocation, rotated credentials) and is the one error worth retrying
            // blind: exactly once, with a definitely-fresh token, so a genuinely
            // bad credential fails fast instead of looping.
            log.warn("Monnify rejected the cached token (401) for reference={} - re-authenticating once", reference);
            invalidateToken();
            response = execute(call, bearerToken(), reference);
        }

        return unwrapEnvelope(response, reference);
    }

    private ResponseEntity<String> execute(
            Function<String, ResponseEntity<String>> call, String token, String reference) {
        try {
            return call.apply(token);
        } catch (MonnifyApiException | MonnifyNotConfiguredException e) {
            throw e;
        } catch (Exception e) {
            // Timeouts and connection resets land here. They mean "we do not know",
            // never "not paid" - see MonnifyApiException.
            throw new MonnifyApiException("Monnify request failed for reference " + reference, e);
        }
    }

    private String bearerToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isUsable()) {
            return current.token();
        }
        synchronized (this) {
            // Re-check inside the lock: several requests can queue here on startup
            // and only the first should pay for a login.
            CachedToken recheck = cachedToken;
            if (recheck != null && recheck.isUsable()) {
                return recheck.token();
            }
            CachedToken fresh = login();
            cachedToken = fresh;
            return fresh.token();
        }
    }

    private synchronized void invalidateToken() {
        cachedToken = null;
    }

    private CachedToken login() {
        String basic = Base64.getEncoder()
                .encodeToString((properties.apiKey() + ":" + properties.secretKey())
                        .getBytes(StandardCharsets.UTF_8));

        ResponseEntity<String> response;
        try {
            response = restClient
                    .post()
                    .uri(LOGIN_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> true, (request, res) -> { })
                    .toEntity(String.class);
        } catch (Exception e) {
            throw new MonnifyApiException("Monnify login failed", e);
        }

        JsonNode body = unwrapEnvelope(response, "login");
        String token = text(body, "accessToken");
        if (token == null) {
            throw new MonnifyApiException("Monnify login returned no accessToken");
        }

        // expiresIn is documented in seconds (3599). Treat an absent or nonsensical
        // value as the documented one-hour life rather than as "never expires",
        // which would strand this instance on a dead token until a restart.
        long expiresInSeconds = body.hasNonNull("expiresIn") ? body.get("expiresIn").asLong(3600L) : 3600L;
        if (expiresInSeconds <= 0) {
            expiresInSeconds = 3600L;
        }
        Duration margin = properties.tokenRefreshMargin();
        Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds).minus(margin);

        log.info("Monnify authenticated (api key prefix {}); token cached until {}", apiKeyPrefix(), expiresAt);
        return new CachedToken(token, expiresAt);
    }

    // ------------------------------------------------------------------------
    // Envelope + field access
    // ------------------------------------------------------------------------

    /**
     * Monnify wraps everything in
     * {@code {requestSuccessful, responseMessage, responseCode, responseBody}} and
     * returns HTTP 200 with {@code requestSuccessful:false} for some business
     * failures. Checking the status code alone would therefore read a refusal as a
     * success, so both are checked here, once, for every call.
     */
    private JsonNode unwrapEnvelope(ResponseEntity<String> response, String reference) {
        String raw = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new MonnifyApiException("Monnify returned HTTP " + response.getStatusCode().value()
                    + " for reference " + reference + ": " + summarise(raw));
        }
        if (raw == null || raw.isBlank()) {
            throw new MonnifyApiException("Monnify returned an empty body for reference " + reference);
        }

        JsonNode envelope;
        try {
            envelope = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new MonnifyApiException("Monnify returned a non-JSON body for reference " + reference, e);
        }

        if (!envelope.path("requestSuccessful").asBoolean(false)) {
            throw new MonnifyApiException("Monnify reported failure for reference " + reference + ": "
                    + envelope.path("responseMessage").asText("(no message)")
                    + " [responseCode " + envelope.path("responseCode").asText("?") + "]");
        }

        JsonNode responseBody = envelope.get("responseBody");
        if (responseBody == null || responseBody.isNull()) {
            throw new MonnifyApiException("Monnify returned no responseBody for reference " + reference);
        }
        return responseBody;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /**
     * Monnify sends monetary values as JSON strings ("100.00") on the verify
     * endpoint and as numbers on some others. {@code asText} then {@code new
     * BigDecimal(...)} handles both without ever going through a double, which is
     * what keeps the amount check exact.
     */
    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            // A malformed amount must not be silently coerced to zero: null flows
            // through to the amount check, which refuses to treat it as payment.
            log.warn("Monnify sent an unparseable {} value: {}", field, value);
            return null;
        }
    }

    private static OffsetDateTime parsePaidOn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, PAID_ON_FORMAT).atZone(MONNIFY_ZONE).toOffsetDateTime();
        } catch (Exception ignored) {
            // Some Monnify surfaces emit ISO-8601 instead. Try it before giving up.
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            // A timestamp we cannot read is not a reason to reject a real payment.
            // The caller substitutes "now", and the verbatim value survives in
            // payments.provider_payload either way.
            log.warn("Monnify sent an unparseable paidOn value: {}", value);
            return null;
        }
    }

    private String apiKeyPrefix() {
        String key = properties.apiKey();
        if (key == null || key.isBlank()) {
            return "(unset)";
        }
        return key.length() <= 8 ? key : key.substring(0, 8) + "...";
    }

    /** Bounded so a large or hostile error body cannot flood the log. */
    private static String summarise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "(empty body)";
        }
        return raw.length() <= 500 ? raw : raw.substring(0, 500) + "...";
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new MonnifyNotConfiguredException();
        }
    }

    private record CachedToken(String token, Instant expiresAt) {

        boolean isUsable() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
