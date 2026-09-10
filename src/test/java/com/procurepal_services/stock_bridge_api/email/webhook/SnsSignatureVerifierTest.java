package com.procurepal_services.stock_bridge_api.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The webhook's only authentication.
 *
 * <p>Everything here signs fixtures with a key pair generated in-process and lets
 * the verifier do real RSA against the matching public key - the same discipline
 * {@code MonnifySignatureVerifierTest} applies to its HMAC, and for the same reason:
 * the canonical string is rebuilt independently in {@link SnsTestMessages} from AWS's
 * published field order, so a bug in the production implementation cannot make these
 * tests agree with themselves.
 *
 * <p>The certificate fetch is stubbed rather than mocked away entirely. That seam is
 * the reason {@link SnsCertificateLoader} is an interface: the cryptography is the
 * part most likely to be got wrong and it deserves to be exercised for real, which
 * is impossible if verifying requires reaching AWS.
 */
class SnsSignatureVerifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SnsSignatureVerifier verifier = verifierWith(SnsTestMessages.KEY_PAIR.getPublic(), null);

    // ========================================================================
    // The happy paths, one per signature version.
    // ========================================================================

    @Test
    void acceptsAVersion2NotificationSignedWithTheMatchingKey() {
        JsonNode envelope = parse(SnsTestMessages.notification(
                "m-1", SnsTestMessages.bounce("Permanent", "dead@example.test")));

        assertThat(verifier.isValid(envelope)).isTrue();
    }

    /**
     * SHA-1 is collision-broken and would never be chosen today, but AWS still signs
     * version 1 messages and refusing them would refuse real bounce notifications.
     * The exposure is narrow - forging one needs a preimage against a key AWS holds,
     * not a collision between two documents an attacker wrote.
     */
    @Test
    void acceptsAVersion1NotificationSignedWithSha1() {
        JsonNode envelope = parse(SnsTestMessages.notification(
                "m-2", SnsTestMessages.bounce("Permanent", "dead@example.test"),
                SnsTestMessages.TOPIC_ARN, "1"));

        assertThat(verifier.isValid(envelope)).isTrue();
    }

    /**
     * The confirmation types sign a different, longer field list than a Notification
     * does - {@code SubscribeURL} and {@code Token} instead of {@code Subject}. Using
     * the wrong list is a mistake that presents only as "signature invalid", so both
     * shapes are asserted.
     */
    @Test
    void acceptsASubscriptionConfirmationWithItsOwnFieldList() {
        JsonNode envelope = parse(
                SnsTestMessages.subscriptionConfirmation("m-3", SnsTestMessages.SUBSCRIBE_URL));

        assertThat(verifier.isValid(envelope)).isTrue();
    }

    @Test
    void acceptsAnUnsubscribeConfirmation() {
        assertThat(verifier.isValid(parse(SnsTestMessages.unsubscribeConfirmation("m-4")))).isTrue();
    }

    // ========================================================================
    // Rejections.
    // ========================================================================

    /**
     * The whole point. Change one byte of the payload and the signature no longer
     * covers it - which is what stops a stranger POSTing a hand-written permanent
     * bounce for somebody else's address.
     */
    @Test
    void rejectsAMessageWhoseBodyWasTamperedWithAfterSigning() {
        String signed = SnsTestMessages.notification(
                "m-5", SnsTestMessages.bounce("Permanent", "victim@example.test"));
        String tampered = signed.replace("victim@example.test", "someone-else@example.test");

        assertThat(verifier.isValid(parse(tampered))).isFalse();
    }

    /** The bounce TYPE is inside the signed Message, so it cannot be upgraded in flight either. */
    @Test
    void rejectsAMessageWhoseBounceTypeWasEscalatedAfterSigning() {
        String signed = SnsTestMessages.notification(
                "m-6", SnsTestMessages.bounce("Transient", "holiday@example.test"));
        String tampered = signed.replace("Transient", "Permanent");

        assertThat(verifier.isValid(parse(tampered))).isFalse();
    }

    @Test
    void rejectsAGarbageSignature() {
        String signed = SnsTestMessages.notification("m-7", SnsTestMessages.bounce("Permanent", "x@example.test"));

        assertThat(verifier.isValid(parse(SnsTestMessages.withBrokenSignature(signed)))).isFalse();
    }

    @Test
    void rejectsAMessageWithNoSignatureAtAll() throws Exception {
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(
                SnsTestMessages.notification("m-8", SnsTestMessages.bounce("Permanent", "x@example.test")));
        envelope.remove("Signature");

        assertThat(verifier.isValid(envelope)).isFalse();
    }

    /**
     * A correctly-formed signature made by somebody else's key. This is what an
     * attacker who has read the AWS documentation but does not hold Amazon's private
     * key can produce.
     */
    @Test
    void rejectsASignatureMadeWithADifferentKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PublicKey strangersKey = generator.generateKeyPair().getPublic();
        SnsSignatureVerifier expectingStranger = verifierWith(strangersKey, null);

        JsonNode envelope = parse(SnsTestMessages.notification(
                "m-9", SnsTestMessages.bounce("Permanent", "x@example.test")));

        assertThat(expectingStranger.isValid(envelope)).isFalse();
    }

    /** An unknown version is refused rather than defaulted - it is also what a downgrade looks like. */
    @Test
    void rejectsAnUnrecognisedSignatureVersion() throws Exception {
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(
                SnsTestMessages.notification("m-10", SnsTestMessages.bounce("Permanent", "x@example.test")));
        envelope.put("SignatureVersion", "3");

        assertThat(verifier.isValid(envelope)).isFalse();
    }

    /**
     * "Cannot verify" must never read as "valid" - the same rule
     * {@code MonnifySignatureVerifier} states for a missing secret key. Here the
     * certificate URL is refused by the SSRF guard, and the verifier must resolve
     * that to false rather than let it escape as a 500 and lose the audit row.
     */
    @Test
    void rejectsRatherThanThrowsWhenTheCertificateCannotBeLoaded() {
        SnsSignatureVerifier refusing = new SnsSignatureVerifier(
                url -> {
                    throw new SnsEndpointRefusedException("refused in test");
                },
                properties(null));

        JsonNode envelope = parse(SnsTestMessages.notification(
                "m-11", SnsTestMessages.bounce("Permanent", "x@example.test")));

        assertThat(refusing.isValid(envelope)).isFalse();
    }

    @Test
    void rejectsANullEnvelopeRatherThanThrowing() {
        assertThat(verifier.isValid(null)).isFalse();
    }

    // ========================================================================
    // Topic pinning: the check a valid signature does NOT give you.
    // ========================================================================

    /**
     * The most important test in this class, and the least obvious. A valid AWS
     * signature proves only that <em>some</em> SNS topic sent the message. Anybody
     * can create a topic in their own account, hand-write a bounce notification for
     * any address they like, subscribe this endpoint to it, and have AWS sign it with
     * the very certificate this verifier trusts. The signature here is genuine - the
     * message is refused because of where it came from.
     */
    @Test
    void refusesACorrectlySignedMessageFromAnotherTopic() {
        SnsSignatureVerifier pinned = verifierWith(SnsTestMessages.KEY_PAIR.getPublic(), SnsTestMessages.TOPIC_ARN);
        JsonNode fromAttackersTopic = parse(SnsTestMessages.notification(
                "m-12",
                SnsTestMessages.bounce("Permanent", "victim@example.test"),
                "arn:aws:sns:eu-west-1:999999999999:attacker-topic",
                "2"));

        // The signature itself is perfectly valid - that is the point.
        assertThat(pinned.isValid(fromAttackersTopic)).isTrue();
        assertThat(pinned.isFromExpectedTopic(fromAttackersTopic)).isFalse();
    }

    @Test
    void acceptsAMessageFromThePinnedTopic() {
        SnsSignatureVerifier pinned = verifierWith(SnsTestMessages.KEY_PAIR.getPublic(), SnsTestMessages.TOPIC_ARN);
        JsonNode envelope = parse(SnsTestMessages.notification(
                "m-13", SnsTestMessages.bounce("Permanent", "x@example.test")));

        assertThat(pinned.isFromExpectedTopic(envelope)).isTrue();
    }

    /**
     * An unpinned topic is REFUSED while signature verification is on.
     *
     * <h2>This assertion was deliberately reversed, and the earlier reasoning is
     * kept here so it is not reinstated by accident</h2>
     * This test previously asserted the opposite - that unpinned accepts anything -
     * on the grounds that refusing "would break every deployment that has not set
     * the ARN yet with a symptom that takes weeks to notice". The premise was that
     * the refusal would be silent. It is not: every refused delivery logs a warning
     * naming the missing property, writes an audit row to {@code
     * ses_notification_events} recording why, and the blank ARN already produces a
     * warning at startup.
     *
     * <p>What settled it is the asymmetry between the two failure modes. Unpinned,
     * the signature check does not prove what it appears to prove - a valid AWS
     * signature says only that <em>some</em> topic in <em>some</em> AWS account sent
     * the message, and creating a topic and subscribing somebody else's public URL
     * to it costs an attacker nothing. So an unpinned endpoint accepts a
     * correctly-signed "permanent bounce" for any address a stranger names, and the
     * effect of that message is to stop mail to a customer of their choosing.
     * Against a remote attacker who needs only the URL, "bounce processing is off
     * until an operator sets one property" is the cheaper failure by a wide margin.
     *
     * <p>See {@link #anUnpinnedTopicIsStillAcceptedWhenSignatureCheckingIsOff} for
     * the escape hatch that keeps local replay working.
     */
    @Test
    void anUnpinnedTopicIsRefusedWhileSignatureCheckingIsOn() {
        JsonNode fromAnywhere = parse(SnsTestMessages.notification(
                "m-14", SnsTestMessages.bounce("Permanent", "x@example.test"),
                "arn:aws:sns:eu-west-1:999999999999:somebody-elses-topic", "2"));

        assertThat(verifier.isFromExpectedTopic(fromAnywhere)).isFalse();
    }

    /**
     * The escape hatch, and the reason the topic rule is tied to
     * {@code require-signature} rather than enforced unconditionally: the two are one
     * posture. A developer who has already switched signature verification off to
     * replay a captured payload against a local database has no topic ARN to give and
     * is not defending against anybody. Asking them for one would only teach them to
     * paste a fake value, which is worse than not asking.
     */
    @Test
    void anUnpinnedTopicIsStillAcceptedWhenSignatureCheckingIsOff() {
        SnsSignatureVerifier relaxed = new SnsSignatureVerifier(
                url -> SnsTestMessages.KEY_PAIR.getPublic(),
                new SesWebhookProperties(false, null, null, true, true,
                        Duration.ofSeconds(5), Duration.ofSeconds(10)));
        JsonNode fromAnywhere = parse(SnsTestMessages.notification(
                "m-14b", SnsTestMessages.bounce("Permanent", "x@example.test"),
                "arn:aws:sns:eu-west-1:999999999999:somebody-elses-topic", "2"));

        assertThat(relaxed.isFromExpectedTopic(fromAnywhere)).isTrue();
    }

    // ========================================================================
    // The canonical string itself.
    // ========================================================================

    /**
     * Asserted directly because the field order and the trailing newline are
     * specification details with no local justification: a mistake in either presents
     * only as "signature invalid", and debugging that from a boolean is miserable.
     */
    @Test
    void buildsTheCanonicalStringInAwsFieldOrder() {
        JsonNode envelope = parse(SnsTestMessages.notification("m-15", "{\"notificationType\":\"Delivery\"}"));

        String canonical = verifier.canonicalStringFor(envelope);

        assertThat(canonical)
                .startsWith("Message\n")
                .contains("\nMessageId\nm-15\n")
                .contains("\nTopicArn\n" + SnsTestMessages.TOPIC_ARN + "\n")
                .endsWith("Type\nNotification\n");
        // Signature, SignatureVersion and SigningCertURL are NOT signed - including
        // them would be self-referential and would fail every real message.
        assertThat(canonical).doesNotContain("Signature").doesNotContain("SigningCertURL");
    }

    @Test
    void refusesToBuildACanonicalStringForAnUnknownType() throws Exception {
        var envelope = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(
                SnsTestMessages.notification("m-16", "{}"));
        envelope.put("Type", "SomethingAwsInventedLater");

        assertThat(verifier.canonicalStringFor(envelope)).isNull();
    }

    @Test
    void reportsWhetherSignatureEnforcementIsSwitchedOn() {
        assertThat(verifier.isSignatureRequired()).isTrue();
        assertThat(new SnsSignatureVerifier(
                        url -> SnsTestMessages.KEY_PAIR.getPublic(),
                        new SesWebhookProperties(false, null, null, true, true, null, null))
                .isSignatureRequired())
                .isFalse();
    }

    // ------------------------------------------------------------------------

    private static SnsSignatureVerifier verifierWith(PublicKey key, String topicArn) {
        return new SnsSignatureVerifier(url -> key, properties(topicArn));
    }

    private static SesWebhookProperties properties(String topicArn) {
        return new SesWebhookProperties(
                true, topicArn, null, true, true, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
