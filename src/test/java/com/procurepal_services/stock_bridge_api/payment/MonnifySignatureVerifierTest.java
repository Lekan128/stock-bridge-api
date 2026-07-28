package com.procurepal_services.stock_bridge_api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The webhook's only authentication. Everything here is deliberately checked
 * against a hash computed the way Monnify documents it - HMAC-SHA512 of the raw
 * body keyed with the secret key - rather than against our own implementation's
 * output, so a bug in {@code computeSignature} cannot make the tests agree with
 * themselves.
 */
class MonnifySignatureVerifierTest {

    private static final String SECRET = "A663NRZA544DDPEM7KDN7Z8HRV6YXD8S";

    /**
     * Note the ugly formatting: two spaces after a colon, a trailing space. It is
     * here on purpose. This is what a signed body from a foreign sender actually
     * looks like, and it is exactly what a DTO round trip would silently normalise
     * away - which is the failure mode this whole design exists to avoid.
     */
    private static final String RAW_BODY =
            "{\"eventType\":\"SUCCESSFUL_TRANSACTION\",  \"eventData\":{\"amountPaid\":\"100.00\"} }";

    private final MonnifySignatureVerifier verifier = new MonnifySignatureVerifier(properties(SECRET, true));

    @Test
    void acceptsABodySignedWithTheSecretKey() {
        assertThat(verifier.isValid(RAW_BODY, verifier.computeSignature(RAW_BODY))).isTrue();
    }

    /**
     * The hash must be over the raw bytes. If someone later "helpfully" makes the
     * controller bind a DTO and re-serialize, the reordered/normalised body
     * produces a different digest and this fails - which is the point.
     */
    @Test
    void aBodyWhoseWhitespaceOrKeyOrderChangedNoLongerMatches() {
        String reserialized = "{\"eventData\":{\"amountPaid\":\"100.00\"},\"eventType\":\"SUCCESSFUL_TRANSACTION\"}";
        String signatureForOriginal = verifier.computeSignature(RAW_BODY);

        assertThat(verifier.isValid(reserialized, signatureForOriginal)).isFalse();
    }

    @Test
    void rejectsATamperedAmount() {
        String signature = verifier.computeSignature(RAW_BODY);
        String tampered = RAW_BODY.replace("100.00", "1.00");

        assertThat(verifier.isValid(tampered, signature)).isFalse();
    }

    @Test
    void rejectsAMissingOrBlankSignature() {
        assertThat(verifier.isValid(RAW_BODY, null)).isFalse();
        assertThat(verifier.isValid(RAW_BODY, "   ")).isFalse();
    }

    @Test
    void rejectsASignatureComputedWithADifferentKey() {
        MonnifySignatureVerifier attacker = new MonnifySignatureVerifier(properties("not-the-real-key", true));

        assertThat(verifier.isValid(RAW_BODY, attacker.computeSignature(RAW_BODY))).isFalse();
    }

    /**
     * "Cannot verify" must never read as "valid". Without this, a deployment that
     * forgot MONNIFY_SECRET_KEY would accept any callback from anyone.
     */
    @Test
    void rejectsEverythingWhenNoSecretKeyIsConfigured() {
        MonnifySignatureVerifier unconfigured = new MonnifySignatureVerifier(properties("", true));

        assertThat(unconfigured.isValid(RAW_BODY, verifier.computeSignature(RAW_BODY))).isFalse();
    }

    /** Hex case is not a secret; a provider switching to upper-case must not read as a forgery. */
    @Test
    void acceptsAnUpperCasedHexSignature() {
        String upper = verifier.computeSignature(RAW_BODY).toUpperCase(Locale.ROOT);

        assertThat(verifier.isValid(RAW_BODY, upper)).isTrue();
    }

    @Test
    void reportsWhetherSignatureEnforcementIsSwitchedOn() {
        assertThat(verifier.isSignatureRequired()).isTrue();
        assertThat(new MonnifySignatureVerifier(properties(SECRET, false)).isSignatureRequired()).isFalse();
    }

    private static MonnifyProperties properties(String secretKey, boolean requireSignature) {
        return new MonnifyProperties(
                "MK_TEST_GC3B8XG2XX",
                secretKey,
                "5867418298",
                "https://sandbox.monnify.com",
                "http://localhost:5173/checkout/return",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                requireSignature,
                null);
    }
}
