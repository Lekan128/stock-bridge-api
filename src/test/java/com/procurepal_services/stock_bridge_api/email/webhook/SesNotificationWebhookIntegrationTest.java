package com.procurepal_services.stock_bridge_api.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.email.EmailEligibility;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The SES bounce/complaint webhook end to end, over real HTTP and the real Spring
 * Security filter chain, against the local docker-compose Postgres - see
 * AuthIntegrationTest for why local Postgres rather than Testcontainers. Requires
 * {@code docker compose up -d} at the project root.
 *
 * <h2>Why these assertions have to be integration tests</h2>
 * Nearly everything this module promises is a claim about how several pieces behave
 * together, and would be vacuously true against mocks:
 * <ul>
 *   <li>That the endpoint is reachable with <em>no</em> authentication is a fact
 *       about {@code SecurityConfig}, not about the controller.</li>
 *   <li>That SNS's {@code Content-Type: text/plain} binds at all is a fact about
 *       message converters. A JSON DTO here would 415 every notification, and no
 *       unit test would notice.</li>
 *   <li>That the user updates cross tenants is a fact about Hibernate's filter being
 *       disabled on an unauthenticated request and the statements being native.</li>
 *   <li>That a transient bounce changes nothing is only meaningful as a statement
 *       about committed database rows.</li>
 * </ul>
 *
 * <h2>No network</h2>
 * The one thing stubbed is the certificate fetch, which would otherwise reach AWS.
 * The stub is deliberately not a no-op: it runs the real {@link SnsEndpointGuard}
 * over the {@code SigningCertURL} first and only then returns the test key, so the
 * SSRF check stays on the path being exercised. Everything else - signature
 * verification, canonical string construction, parsing, persistence - is the real
 * code, and the fixtures are signed by {@link SnsTestMessages} using field orders
 * transcribed from AWS's specification rather than from our implementation.
 *
 * <h2>Test properties</h2>
 * The topic ARN is pinned, because that is how a deployed environment must be
 * configured and a test that ran unpinned would not exercise the check. Signature
 * enforcement is left at its default of true, which is the point.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.email.sns.topic-arn=" + SnsTestMessages.TOPIC_ARN,
            "app.email.sns.require-signature=true",
            "app.email.sns.complaints-suppress-all-mail=true",
            // Every @SpringBootTest in this suite keeps its own Hikari pool alive for
            // the whole JVM (Spring caches contexts and does not close them), and the
            // local Postgres allows 100 connections in total. At Hikari's default of
            // 10 per context, adding one more context is enough to push the suite over
            // that ceiling - which surfaces as an unrelated context failing to start
            // with "sorry, too many clients already", not as anything here. This test
            // is strictly sequential (it blocks on each HTTP call before asserting
            // over JDBC), so a pool of three is ample and leaves the headroom where it
            // is needed.
            "spring.datasource.hikari.maximum-pool-size=3"
        })
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SesNotificationWebhookIntegrationTest {

    private static final String PATH = "/api/webhooks/ses/notifications";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * Replaces the certificate loader for this context, so nothing here opens a
     * socket to AWS. Still runs the real SSRF guard - see the class doc.
     */
    @TestBean(name = "httpSnsCertificateLoader")
    private SnsCertificateLoader snsCertificateLoader;

    static SnsCertificateLoader snsCertificateLoader() {
        SnsEndpointGuard guard = new SnsEndpointGuard(new SesWebhookProperties(
                true, null, null, true, true, Duration.ofSeconds(5), Duration.ofSeconds(10)));
        return url -> {
            guard.requireSigningCertificateUrl(url);
            return SnsTestMessages.KEY_PAIR.getPublic();
        };
    }

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Read the flags with plain SQL rather than through JPA: the columns are what the
     * feature actually changes, and going through the repositories would read them
     * back through the same tenant filter and persistence context whose behaviour is
     * under test.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** The consumer of all this - asserted directly, since the suppression list exists for it. */
    @Autowired
    private EmailEligibility eligibility;

    // ========================================================================
    // Bounces. The permanent/transient split is the heart of the module.
    // ========================================================================

    @Test
    void aPermanentBounceSuppressesTheAddressAndUnverifiesEveryUserHoldingIt() {
        String address = signupVerified("Bounce Permanent Co");
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isTrue();

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", address)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suppressionReasonFor(address)).isEqualTo("PERMANENT_BOUNCE");
        assertThat(verifiedFlagsFor(address)).containsExactly(false);
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /** The diagnostic is the field that distinguishes "never existed" from "stopped accepting us". */
    @Test
    void aPermanentBounceKeepsTheRemoteServersDiagnostic() {
        String address = signupVerified("Bounce Diagnostic Co");

        post(SnsTestMessages.notification(messageId(), SnsTestMessages.bounce("Permanent", address)));

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT diagnostic FROM email_suppressions WHERE address = ?", String.class, address))
                .contains("550 5.1.1 user unknown");
    }

    /**
     * THE most important test in this module.
     *
     * <p>A transient bounce is a full mailbox, a greylisting server, a provider having
     * a bad afternoon, or an out-of-office auto-reply. All of them resolve by
     * themselves within hours. Suppressing on one would mean a customer who went on
     * holiday with a full inbox permanently stops receiving the receipts for goods
     * they pay for - and nothing in this system would ever undo it, because nothing
     * ever tells us an inbox started working again.
     */
    @Test
    void aTransientBounceSuppressesNothingAndUnverifiesNobody() {
        String address = signupVerified("Bounce Transient Co");

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Transient", address)));

        // Accepted - we understood it and deliberately did nothing.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isTrue();
        // ...but it is on the record, which is the other half of "do nothing".
        assertThat(latestNoteFor(address)).isNotNull();
    }

    /**
     * Undetermined is treated as transient. The reasoning is asymmetry of cost, not
     * optimism: guessing "transient" wrongly means we mail a dead address a few more
     * times and get told again, which is self-correcting; guessing "permanent"
     * wrongly silently and irreversibly disconnects a paying customer.
     */
    @Test
    void anUndeterminedBounceIsTreatedAsTransient() {
        String address = signupVerified("Bounce Undetermined Co");

        post(SnsTestMessages.notification(messageId(), SnsTestMessages.bounce("Undetermined", address)));

        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
    }

    /**
     * A bounce type SES has not documented yet must fall through to "do nothing", not
     * to suppression - which is why the check is an allow-list of one value rather
     * than a deny-list of the two safe ones.
     */
    @Test
    void anUnrecognisedBounceTypeChangesNothing() {
        String address = signupVerified("Bounce Unknown Type Co");

        post(SnsTestMessages.notification(messageId(), SnsTestMessages.bounce("SomethingNew", address)));

        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
    }

    /**
     * One SES send can address several people and any subset can bounce. Each entry
     * carries its own diagnostic and has to be handled on its own; collapsing to the
     * first would suppress the wrong inbox.
     */
    @Test
    void everyBouncedRecipientIsHandledIndependently() {
        String first = signupVerified("Bounce Multi A Co");
        String second = signupVerified("Bounce Multi B Co");
        String third = signupVerified("Bounce Multi C Co");

        post(SnsTestMessages.notification(messageId(), SnsTestMessages.bounce("Permanent", first, second)));

        assertThat(isSuppressed(first)).isTrue();
        assertThat(isSuppressed(second)).isTrue();
        assertThat(isSuppressed(third)).isFalse();
        assertThat(verifiedFlagsFor(third)).containsExactly(true);
    }

    /**
     * SES describes the same event two ways depending on how notifications were wired
     * up: identity notifications say {@code notificationType}, configuration-set event
     * destinations - which is what the runbook sets up - say {@code eventType}. A
     * deployment can easily end up on either, and reading only one produces a webhook
     * that receives everything and does nothing.
     */
    @Test
    void acceptsTheConfigurationSetEventPublishingDialect() {
        String address = signupVerified("Bounce Dialect Co");

        post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounceInEventPublishingDialect("Permanent", address)));

        assertThat(isSuppressed(address)).isTrue();
    }

    // ========================================================================
    // Complaints.
    // ========================================================================

    /**
     * Someone pressed "mark as spam". Marketing stops for certain; whether everything
     * else stops is the module's one genuinely contested decision, and it defaults to
     * yes - see EmailSuppressionService for the argument. Both halves are asserted so
     * that flipping the default is a visible change rather than a silent one.
     */
    @Test
    void aComplaintOptsOutOfPromotionalMailAndSuppressesTheAddressByDefault() {
        String address = signupVerified("Complaint Co");
        assertThat(promotionalFlagsFor(address)).containsExactly(true);

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.complaint("abuse", address)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(promotionalFlagsFor(address)).containsExactly(false);
        assertThat(suppressionReasonFor(address)).isEqualTo("COMPLAINT");
        assertThat(eligibility.isEligible(address, EmailKind.PROMOTIONAL)).isFalse();
        assertThat(eligibility.isEligible(address, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /**
     * Gmail sends {@code complaintFeedbackType: "not-spam"} when a reader moves our
     * mail OUT of their spam folder. That is an endorsement. Acting on it as a
     * complaint would silence precisely the customers who signalled the mail was
     * wanted - which is the sort of rule that looks like a bug until you read why.
     */
    @Test
    void aNotSpamFeedbackReportIsAnEndorsementAndChangesNothing() {
        String address = signupVerified("Complaint Not Spam Co");

        post(SnsTestMessages.notification(messageId(), SnsTestMessages.complaint("not-spam", address)));

        assertThat(isSuppressed(address)).isFalse();
        assertThat(promotionalFlagsFor(address)).containsExactly(true);
    }

    // ========================================================================
    // The gap this module exists to close.
    // ========================================================================

    /**
     * V8 shipped the verified flag on {@code users} and recorded, in its own comment,
     * the gap it could not close: most mail goes to {@code clients.admin_contact_email},
     * which is routinely a shared finance inbox that no user has ever logged in as. It
     * has no flag to clear and nobody who could ever click a verification link, so a
     * design built on user flags would report success and keep mailing a dead address
     * forever.
     *
     * <p>This is that case, end to end: a company contact address with no users row
     * behind it, eligible for transactional mail on the strength of an authenticated
     * OWNER having nominated it, hard-bouncing, and stopping.
     */
    @Test
    void suppressionCoversAClientContactAddressWithNoUserRowBehindIt() {
        TenantLoginResponse owner = signup("Contact Only Co");
        String sharedInbox = "accounts-" + UUID.randomUUID() + "@example.com";
        // Decouple the company's contact of record from its account holder, which is
        // what an OWNER with MANAGE_COMPANY_PROFILE does.
        jdbcTemplate.update(
                "UPDATE clients SET admin_contact_email = ? WHERE slug = ?",
                sharedInbox, owner.user().clientIdentifier());

        // No users row holds this address at all - that is the whole point.
        assertThat(verifiedFlagsFor(sharedInbox)).isEmpty();
        assertThat(eligibility.isEligible(sharedInbox, EmailKind.TRANSACTIONAL)).isTrue();

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", sharedInbox)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(suppressionReasonFor(sharedInbox)).isEqualTo("PERMANENT_BOUNCE");
        assertThat(eligibility.isEligible(sharedInbox, EmailKind.TRANSACTIONAL)).isFalse();
        // And for the kinds that bypass every other rule in EmailEligibility.
        assertThat(eligibility.isEligible(sharedInbox, EmailKind.VERIFICATION)).isFalse();
        assertThat(eligibility.isEligible(sharedInbox, EmailKind.SECURITY)).isFalse();
    }

    // ========================================================================
    // Security. These are the tests that matter if this endpoint is ever found.
    // ========================================================================

    /**
     * The endpoint is public and it disables email for arbitrary users. Unsigned,
     * anybody who finds the path could unverify every customer on the platform with a
     * loop and a word list. Note what is asserted alongside the status: that nothing
     * changed, and that the attempt was nonetheless recorded - an invalid signature is
     * otherwise completely invisible, and a run of these rows is the alert.
     */
    @Test
    void anInvalidSignatureIsRejectedAndChangesNothing() {
        String address = signupVerified("Bad Signature Co");
        String forged = SnsTestMessages.withBrokenSignature(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", address)));

        ResponseEntity<String> response = post(forged);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
        assertThat(countRejectedSignatures()).isPositive();
    }

    /** A body edited in flight fails the same way, which is what the signature is for. */
    @Test
    void aTamperedPayloadIsRejectedAndChangesNothing() {
        String victim = signupVerified("Tampered Co");
        String signedForSomeoneElse = SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", "harmless@example.test"));
        String tampered = signedForSomeoneElse.replace("harmless@example.test", victim);

        ResponseEntity<String> response = post(tampered);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(isSuppressed(victim)).isFalse();
        assertThat(verifiedFlagsFor(victim)).containsExactly(true);
    }

    /**
     * A valid AWS signature proves only that <em>some</em> SNS topic sent the message.
     * Anybody can create a topic in their own AWS account, hand-write a bounce for any
     * address, and have Amazon sign it. 403 rather than 401 because the message
     * authenticated perfectly and was refused on authorization - and because an
     * operator needs to tell "someone is forging" from "someone pointed a second topic
     * at us".
     */
    @Test
    void aCorrectlySignedMessageFromAnotherTopicIsRefused() {
        String address = signupVerified("Wrong Topic Co");
        String fromAttackersTopic = SnsTestMessages.notification(
                messageId(),
                SnsTestMessages.bounce("Permanent", address),
                "arn:aws:sns:eu-west-1:999999999999:attacker-topic",
                "2");

        ResponseEntity<String> response = post(fromAttackersTopic);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(isSuppressed(address)).isFalse();
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
    }

    /**
     * SSRF. {@code SubscribeURL} arrives in the body and this server is asked to GET
     * it, so an unvalidated fetch would hand an attacker the instance metadata
     * endpoint and this process's IAM credentials. The message here is correctly
     * signed and from the right topic - it is refused purely on where the URL points,
     * which is what makes the guard a hard check rather than a heuristic.
     */
    @Test
    void aSubscriptionConfirmationPointingAtANonAwsHostIsRefused() {
        String malicious = SnsTestMessages.subscriptionConfirmation(
                messageId(), "http://169.254.169.254/latest/meta-data/iam/security-credentials/");

        ResponseEntity<String> response = post(malicious);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** The same guard, on the other URL - and this one is fetched before anything is trusted. */
    @Test
    void aSigningCertUrlOnANonAwsHostIsRefused() {
        String tampered = SnsTestMessages.notification(messageId(), SnsTestMessages.bounce("Permanent", "x@e.test"))
                .replace(SnsTestMessages.CERT_URL, "https://attacker.test/SimpleNotificationService-abc.pem");

        ResponseEntity<String> response = post(tampered);

        // Refused at the certificate fetch, so the signature can never verify.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** No token, no session, no CSRF token - AWS has none of ours to send. */
    @Test
    void theEndpointRequiresNoAuthentication() {
        String address = signupVerified("No Auth Co");

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", address)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ========================================================================
    // Idempotency and protocol behaviour.
    // ========================================================================

    /**
     * SNS guarantees at-least-once delivery and retries anything not answered 2xx, so
     * a redelivery is the ordinary case rather than an edge case. The second POST must
     * change nothing and must still answer 200 - answering anything else would have
     * AWS retry it for hours.
     */
    @Test
    void aDuplicateNotificationIsANoOp() {
        String address = signupVerified("Duplicate Co");
        String messageId = messageId();
        String notification = SnsTestMessages.notification(
                messageId, SnsTestMessages.bounce("Permanent", address));

        assertThat(post(notification).getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstSuppressedAt = suppressedAtFor(address);

        assertThat(post(notification).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post(notification).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Exactly one row was ever processed for this id; the retries are recorded
        // but marked unprocessed, because losing the evidence of a retry hides
        // exactly the provider misbehaviour you want to see.
        assertThat(processedCountFor(messageId)).isEqualTo(1);
        assertThat(totalCountFor(messageId)).isEqualTo(3);
        // And the suppression was not churned - created_at still says when the
        // address was FIRST suppressed.
        assertThat(suppressedAtFor(address)).isEqualTo(firstSuppressedAt);
    }

    /**
     * A Delivery event is real, expected and of no interest to a suppression list.
     * Ignored without a row: a busy configuration set emits several of these per email
     * sent, and recording them would grow the audit table with a multiple of total
     * mail volume in exchange for nothing anybody will query.
     */
    @Test
    void aDeliveryNotificationIsIgnoredWithoutWritingARow() {
        String address = signupVerified("Delivery Co");
        long before = totalEventCount();

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.delivery(address)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(totalEventCount()).isEqualTo(before);
        assertThat(verifiedFlagsFor(address)).containsExactly(true);
    }

    /**
     * Nothing to do, and that is why it matters. From this moment SES bounces stop
     * arriving and every other signal in the application looks healthy while the
     * bounce rate climbs at AWS - so the recorded row is the only trace.
     */
    @Test
    void anUnsubscribeConfirmationIsRecorded() {
        String messageId = messageId();

        ResponseEntity<String> response = post(SnsTestMessages.unsubscribeConfirmation(messageId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noteFor(messageId)).contains("subscription was removed");
    }

    /**
     * SNS posts JSON as {@code text/plain}. If this ever binds a JSON DTO, every
     * notification 415s before any handler runs, SNS retries each for hours and then
     * drops it, and nothing of ours logs a thing.
     */
    @Test
    void acceptsTheTextPlainContentTypeSnsActuallySends() {
        String address = signupVerified("Text Plain Co");

        ResponseEntity<String> response = post(SnsTestMessages.notification(
                messageId(), SnsTestMessages.bounce("Permanent", address)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(isSuppressed(address)).isTrue();
    }

    /** A malformed body still has to leave evidence - the payload column is NOT NULL JSONB. */
    @Test
    void aMalformedBodyIsRecordedAndRefused() {
        long before = totalEventCount();

        ResponseEntity<String> response = post("this is not json");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(totalEventCount()).isEqualTo(before + 1);
    }

    // ========================================================================
    // Helpers.
    // ========================================================================

    /** Exactly what SNS sends: JSON, under text/plain, with no credential of any kind. */
    private ResponseEntity<String> post(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        return restTemplate.exchange(PATH, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private static String messageId() {
        return "msg-" + UUID.randomUUID();
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

    /**
     * A freshly signed-up user is UNVERIFIED - that is V8's column default and module
     * B's whole flow. Verifying by SQL rather than by driving the verification
     * endpoint keeps this test about bounces: what is being asserted is that a
     * verified address becomes unverified, and how it got verified is another module's
     * test.
     */
    private String signupVerified(String name) {
        String address = signup(name).user().username().toLowerCase();
        jdbcTemplate.update(
                "UPDATE users SET is_email_verified = TRUE, email_verified_at = now() "
                        + "WHERE lower(username) = ? OR lower(email) = ?",
                address, address);
        return address;
    }

    /** One entry per user row holding the address, ordered so assertions are stable. */
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

    private String suppressedAtFor(String address) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at::text FROM email_suppressions WHERE address = ?", String.class, address);
    }

    private int processedCountFor(String messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ses_notification_events WHERE message_id = ? AND processed = TRUE",
                Integer.class, messageId);
    }

    private int totalCountFor(String messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ses_notification_events WHERE message_id = ?", Integer.class, messageId);
    }

    private String noteFor(String messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_note FROM ses_notification_events WHERE message_id = ?",
                String.class, messageId);
    }

    private String latestNoteFor(String address) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_note FROM ses_notification_events WHERE payload::text LIKE ? "
                        + "ORDER BY received_at DESC LIMIT 1",
                String.class, "%" + address + "%");
    }

    private long totalEventCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ses_notification_events", Long.class);
    }

    private long countRejectedSignatures() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ses_notification_events WHERE signature_valid = FALSE", Long.class);
    }
}
