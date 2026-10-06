package com.procurepal_services.stock_bridge_api.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.email.ResendProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Against Svix's own published test vector (the secret, id, timestamp, payload and
 * signature from its manual-verification docs, which Resend's docs repeat), not
 * against fixtures this codebase produced - a vector we signed ourselves would only
 * prove the verifier agrees with itself.
 */
class ResendSignatureVerifierTest {

    private static final String SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw";
    private static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";
    private static final String TIMESTAMP = "1614265330";
    private static final byte[] BODY = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

    private static final Instant SIGNED_AT = Instant.ofEpochSecond(Long.parseLong(TIMESTAMP));

    @Test
    void acceptsSvixsPublishedTestVector() {
        assertThat(verifier(SECRET, SIGNED_AT).isValid(ID, TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void acceptsASecretPastedWithoutItsPrefix() {
        assertThat(verifier(SECRET.substring("whsec_".length()), SIGNED_AT).isValid(ID, TIMESTAMP, SIGNATURE, BODY))
                .isTrue();
    }

    /** During a secret rotation Svix sends one signature per secret; any match passes. */
    @Test
    void acceptsWhenAnyOfSeveralSignaturesMatches() {
        String header = "v1,bm90LXRoZS1zaWduYXR1cmU= " + SIGNATURE;

        assertThat(verifier(SECRET, SIGNED_AT).isValid(ID, TIMESTAMP, header, BODY)).isTrue();
    }

    @Test
    void rejectsATamperedBody() {
        byte[] tampered = "{\"test\": 2432232315}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier(SECRET, SIGNED_AT).isValid(ID, TIMESTAMP, SIGNATURE, tampered)).isFalse();
    }

    /** The id is inside the signed content, so it cannot be swapped to dodge the dedupe. */
    @Test
    void rejectsASwappedMessageId() {
        assertThat(verifier(SECRET, SIGNED_AT).isValid("msg_other", TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void rejectsTheWrongSecret() {
        assertThat(verifier("whsec_" + "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB", SIGNED_AT)
                        .isValid(ID, TIMESTAMP, SIGNATURE, BODY))
                .isFalse();
    }

    /** A captured, genuinely-signed delivery replayed later is refused. */
    @Test
    void rejectsAValidSignatureOutsideTheTolerance() {
        Instant tenMinutesLater = SIGNED_AT.plus(Duration.ofMinutes(10));

        assertThat(verifier(SECRET, tenMinutesLater).isValid(ID, TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void acceptsAValidSignatureInsideTheTolerance() {
        Instant fourMinutesLater = SIGNED_AT.plus(Duration.ofMinutes(4));

        assertThat(verifier(SECRET, fourMinutesLater).isValid(ID, TIMESTAMP, SIGNATURE, BODY)).isTrue();
    }

    @Test
    void failsClosedWithNoSecretConfigured() {
        assertThat(verifier("  ", SIGNED_AT).isValid(ID, TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void failsClosedOnASecretThatIsNotBase64() {
        assertThat(verifier("whsec_not base64!", SIGNED_AT).isValid(ID, TIMESTAMP, SIGNATURE, BODY)).isFalse();
    }

    @Test
    void rejectsMissingHeadersAndUnknownVersions() {
        ResendSignatureVerifier verifier = verifier(SECRET, SIGNED_AT);

        assertThat(verifier.isValid(null, TIMESTAMP, SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(ID, null, SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(ID, TIMESTAMP, null, BODY)).isFalse();
        assertThat(verifier.isValid(ID, "not-a-number", SIGNATURE, BODY)).isFalse();
        assertThat(verifier.isValid(ID, TIMESTAMP, SIGNATURE.replace("v1,", "v2,"), BODY)).isFalse();
    }

    private static ResendSignatureVerifier verifier(String secret, Instant now) {
        ResendProperties properties = new ResendProperties("re_test", null, null, null, secret, null);
        return new ResendSignatureVerifier(properties, Clock.fixed(now, ZoneOffset.UTC));
    }
}
