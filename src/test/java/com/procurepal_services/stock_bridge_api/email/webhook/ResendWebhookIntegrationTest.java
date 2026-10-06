package com.procurepal_services.stock_bridge_api.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.EmailEligibility;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The Resend bounce/complaint webhook end to end - real HTTP, the real Spring Security
 * chain, the local docker-compose Postgres - for the same reasons
 * SesNotificationWebhookIntegrationTest gives: permit-all is a fact about
 * SecurityConfig, raw-byte binding is a fact about message converters, and the
 * cross-tenant user updates are a fact about the tenant filter. Requires
 * {@code docker compose up -d} at the project root.
 *
 * <p>The suppression semantics themselves (transient changes nothing, complaints
 * suppress all mail by default, and so on) are EmailSuppressionService's and are
 * covered at length by the SES test; this covers that Resend's payloads reach them.
 * Signatures are produced here with the Svix scheme, which ResendSignatureVerifierTest
 * pins against Svix's own published vector.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.email.resend.webhook-secret=" + ResendWebhookIntegrationTest.SECRET,
            "app.email.sns.complaints-suppress-all-mail=true",
            // See SesNotificationWebhookIntegrationTest: every cached context keeps
            // its pool, and the local Postgres caps connections at 100.
            "spring.datasource.hikari.maximum-pool-size=3"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ResendWebhookIntegrationTest {

    static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";

    private static final String PATH = "/api/webhooks/resend";
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmailEligibility eligibility;

    @Test
    void aPermanentBounceSuppressesTheAddressAndUnverifiesTheUser() {
        String address = signupVerified("Resend Bounce Co");
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isTrue();

        ResponseEntity<String> response = postSigned(svixId(), bounced("Permanent", "General", address));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suppressionReasonFor(address)).isEqualTo("PERMANENT_BOUNCE");
        assertThat(verifiedFlagsFor(address)).containsExactly(false);
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /** A full mailbox must never cost a customer their receipts. */
    @Test
    void aTemporaryBounceChangesNothing() {
        String address = signupVerified("Resend Transient Co");
        String id = svixId();

        ResponseEntity<String> response = postSigned(id, bounced("Temporary", "MailboxFull", address));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
        assertThat(processedCountFor(id)).isEqualTo(1);
    }

    @Test
    void aComplaintOptsOutAndSuppressesAllMailByDefault() {
        String address = signupVerified("Resend Complaint Co");

        ResponseEntity<String> response = postSigned(svixId(), event("email.complained", address, ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suppressionReasonFor(address)).isEqualTo("COMPLAINT");
        assertThat(promotionalFlagsFor(address)).containsExactly(false);
    }

    @Test
    void anUnsignedDeliveryIsRejectedAndChangesNothing() {
        String address = signupVerified("Resend Forged Co");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("svix-id", svixId());
        headers.set("svix-timestamp", String.valueOf(Instant.now().getEpochSecond()));
        headers.set("svix-signature", "v1,bm90LXRoZS1zaWduYXR1cmU=");

        ResponseEntity<String> response = restTemplate.exchange(PATH, HttpMethod.POST,
                new HttpEntity<>(bounced("Permanent", "General", address), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
    }

    /** Svix retries anything not answered 2xx, so a redelivery is ordinary and must be a 200 no-op. */
    @Test
    void aRedeliveryIsANoOp() {
        String address = signupVerified("Resend Duplicate Co");
        String id = svixId();
        String body = bounced("Permanent", "General", address);

        assertThat(postSigned(id, body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postSigned(id, body).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(processedCountFor(id)).isEqualTo(1);
    }

    /**
     * email.failed is Resend refusing to send at all, and its reasons (reached_daily_quota, a
     * revoked key, an unverified domain) are OURS. Recorded for the audit trail, never turned into
     * a suppression - running out of quota must not silence a good customer forever.
     */
    @Test
    void aFailedSendIsRecordedButNeverSuppressesTheRecipient() {
        String address = signupVerified("Resend Quota Co");
        String id = svixId();

        ResponseEntity<String> response = postSigned(id,
                event("email.failed", address, ",\"failed\":{\"reason\":\"reached_daily_quota\"}"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
        assertThat(processedCountFor(id)).isEqualTo(1);
    }

    @Test
    void anEventTypeItDoesNotActOnIsAcknowledged() {
        String address = signupVerified("Resend Delivered Co");

        ResponseEntity<String> response = postSigned(svixId(), event("email.delivered", address, ""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isSuppressed(address)).isFalse();
    }

    // ------------------------------------------------------------------------

    /** What Resend sends: JSON, the three Svix headers, no credential of ours. */
    private ResponseEntity<String> postSigned(String id, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("svix-id", id);
        headers.set("svix-timestamp", timestamp);
        headers.set("svix-signature", "v1," + sign(id, timestamp, body));
        return restTemplate.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String sign(String id, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    Base64.getDecoder().decode(SECRET.substring("whsec_".length())), "HmacSHA256"));
            byte[] signature = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Shaped on the email.bounced example in Resend's docs. */
    private static String bounced(String type, String subType, String address) {
        return event("email.bounced", address, ",\"bounce\":{\"message\":\"550 5.1.1 user unknown\","
                + "\"subType\":\"" + subType + "\",\"type\":\"" + type + "\"}");
    }

    private static String event(String type, String address, String extraData) {
        return "{\"type\":\"" + type + "\",\"created_at\":\"2026-10-06T10:00:00.000Z\",\"data\":{"
                + "\"created_at\":\"2026-10-06T09:59:59.000Z\","
                + "\"email_id\":\"" + UUID.randomUUID() + "\","
                + "\"from\":\"Procure Paddy <no-reply@mail.procurepaddy.com>\","
                + "\"to\":[\"" + address + "\"],"
                + "\"subject\":\"Order confirmed\","
                + "\"tags\":{\"kind\":\"transactional\"}"
                + extraData + "}}";
    }

    private static String svixId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private TenantLoginResponse signup(String name) {
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + UUID.randomUUID().toString().substring(0, 8),
                null,
                "owner-" + UUID.randomUUID() + "@example.com",
                PASSWORD,
                PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private String signupVerified(String name) {
        String address = signup(name).user().username().toLowerCase();
        jdbcTemplate.update(
                "UPDATE users SET is_email_verified = TRUE, email_verified_at = now() "
                        + "WHERE lower(username) = ? OR lower(email) = ?",
                address, address);
        return address;
    }

    private List<Boolean> verifiedFlagsFor(String address) {
        return jdbcTemplate.queryForList(
                "SELECT is_email_verified FROM users WHERE lower(email) = ? OR lower(username) = ? "
                        + "ORDER BY created_at",
                Boolean.class, address, address);
    }

    private List<Boolean> promotionalFlagsFor(String address) {
        return jdbcTemplate.queryForList(
                "SELECT receive_promotional_email FROM users WHERE lower(email) = ? OR lower(username) = ? "
                        + "ORDER BY created_at",
                Boolean.class, address, address);
    }

    private boolean isSuppressed(String address) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM email_suppressions WHERE address = ?", Long.class, address) > 0;
    }

    private String suppressionReasonFor(String address) {
        return jdbcTemplate.queryForObject(
                "SELECT reason FROM email_suppressions WHERE address = ?", String.class, address);
    }

    private int processedCountFor(String messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ses_notification_events WHERE message_id = ? AND processed = TRUE",
                Integer.class, messageId);
    }
}
