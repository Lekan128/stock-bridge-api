package com.procurepal_services.stock_bridge_api.email.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring and no Mockito - the class under test is a pure function
 * of its properties, so anything more would only be ceremony. Same shape as
 * MonnifySignatureVerifierTest, and for the same reason: this is signature
 * verification on a public endpoint, and the tests that matter are the ones that
 * prove a forgery is refused.
 */
class UnsubscribeTokenServiceTest {

    private static final String SECRET = "an-unsubscribe-signing-secret-for-tests";

    private final UnsubscribeTokenService tokens =
            new UnsubscribeTokenService(new UnsubscribeProperties(SECRET, "https://api.procurepal.test"));

    @Test
    void aFreshlyMintedTokenResolvesBackToItsAddress() {
        String token = tokens.tokenFor("buyer@example.com");

        assertThat(tokens.addressFrom(token)).isEqualTo("buyer@example.com");
    }

    /**
     * Case and whitespace must not matter, because the address a token is minted
     * from is whatever was sitting in a To: header, and the address it is later
     * compared against is lower(email) in SQL. A divergence here would sign one
     * string and unsubscribe another - and would only show up in production.
     */
    @Test
    void normalisesTheAddressSoAnyCasingProducesTheSameToken() {
        assertThat(tokens.tokenFor("  Buyer@Example.COM  ")).isEqualTo(tokens.tokenFor("buyer@example.com"));
        assertThat(tokens.addressFrom(tokens.tokenFor("  Buyer@Example.COM  "))).isEqualTo("buyer@example.com");
    }

    /** Deterministic on purpose - see the class doc on why there is no nonce. */
    @Test
    void isDeterministicSoAResendCarriesTheSameLink() {
        assertThat(tokens.tokenFor("buyer@example.com")).isEqualTo(tokens.tokenFor("buyer@example.com"));
    }

    @Test
    void differentAddressesGetDifferentTokens() {
        assertThat(tokens.tokenFor("one@example.com")).isNotEqualTo(tokens.tokenFor("two@example.com"));
    }

    // ========================================================================
    // Forgery. The whole point of signing.
    // ========================================================================

    /**
     * THE test. Without a signature the link would carry a bare address and anyone
     * could unsubscribe anyone by editing a query string - silently, since a message
     * that is never sent leaves no trace in anybody's inbox.
     */
    @Test
    void rejectsAnAddressSwappedIntoSomebodyElsesSignature() {
        String victimsToken = tokens.tokenFor("victim@example.com");
        String signature = victimsToken.substring(victimsToken.lastIndexOf('.') + 1);
        String forged = base64Url("someone-else@example.com") + "." + signature;

        assertThat(tokens.addressFrom(forged)).isNull();
    }

    /**
     * Tampers with the FIRST character of the signature, not the last. The last
     * base64 character of an unpadded 32-byte MAC carries two bits that decode to
     * nothing, and Java's decoder tolerates non-canonical values there - so flipping
     * it would produce identical bytes and the test would pass while proving
     * nothing.
     */
    @Test
    void rejectsATamperedSignature() {
        String token = tokens.tokenFor("buyer@example.com");
        int separator = token.lastIndexOf('.');
        String tampered = token.substring(0, separator + 1)
                + flip(token.charAt(separator + 1))
                + token.substring(separator + 2);

        assertThat(tokens.addressFrom(tampered)).isNull();
    }

    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        UnsubscribeTokenService other =
                new UnsubscribeTokenService(new UnsubscribeProperties("a-completely-different-secret", "https://x"));

        assertThat(tokens.addressFrom(other.tokenFor("buyer@example.com"))).isNull();
    }

    @Test
    void rejectsMalformedAndAbsentTokens() {
        assertThat(tokens.addressFrom(null)).isNull();
        assertThat(tokens.addressFrom("")).isNull();
        assertThat(tokens.addressFrom("   ")).isNull();
        assertThat(tokens.addressFrom("no-separator-at-all")).isNull();
        assertThat(tokens.addressFrom(".")).isNull();
        assertThat(tokens.addressFrom(base64Url("buyer@example.com") + ".")).isNull();
        assertThat(tokens.addressFrom("." + base64Url("buyer@example.com"))).isNull();
        assertThat(tokens.addressFrom("not!base64.not!base64")).isNull();
        // A well-formed payload with no signature attached is the naive attack.
        assertThat(tokens.addressFrom(base64Url("buyer@example.com"))).isNull();
    }

    /** Truncating the MAC must not truncate the comparison. */
    @Test
    void rejectsAShortenedSignature() {
        String token = tokens.tokenFor("buyer@example.com");

        assertThat(tokens.addressFrom(token.substring(0, token.length() - 4))).isNull();
    }

    // ========================================================================
    // Links and configuration.
    // ========================================================================

    @Test
    void buildsAnAbsoluteLinkAtThisApiRatherThanTheFrontend() {
        String url = tokens.unsubscribeUrlFor("buyer@example.com");

        assertThat(url).startsWith("https://api.procurepal.test/api/email/unsubscribe?token=");
        assertThat(tokens.addressFrom(url.substring(url.indexOf("token=") + 6))).isEqualTo("buyer@example.com");
    }

    /** A doubled slash is a 404 on some providers' normalisation and not on others. */
    @Test
    void stripsATrailingSlashFromTheConfiguredBaseUrl() {
        UnsubscribeTokenService trailing =
                new UnsubscribeTokenService(new UnsubscribeProperties(SECRET, "https://api.procurepal.test/"));

        assertThat(trailing.unsubscribeUrlFor("buyer@example.com"))
                .startsWith("https://api.procurepal.test/api/email/unsubscribe?token=");
    }

    @Test
    void issuesNothingWhenTheSecretIsNotConfigured() {
        UnsubscribeTokenService unconfigured =
                new UnsubscribeTokenService(new UnsubscribeProperties("  ", "https://api.procurepal.test"));

        assertThat(unconfigured.canIssueLinks()).isFalse();
        assertThat(unconfigured.tokenFor("buyer@example.com")).isNull();
        assertThat(unconfigured.unsubscribeUrlFor("buyer@example.com")).isNull();
    }

    /**
     * Fail-closed: with no key nothing can be checked, and "cannot check" must never
     * read as "valid" - that would let anyone unsubscribe anyone on a deploy that
     * simply forgot to set the variable.
     */
    @Test
    void verifiesNothingWhenTheSecretIsNotConfigured() {
        String genuine = tokens.tokenFor("buyer@example.com");
        UnsubscribeTokenService unconfigured =
                new UnsubscribeTokenService(new UnsubscribeProperties(null, "https://api.procurepal.test"));

        assertThat(unconfigured.addressFrom(genuine)).isNull();
    }

    /**
     * The asymmetry that matters operationally: an environment that has lost its
     * api-base-url must still honour the links it already mailed, or genuine
     * unsubscribes start failing and turn into spam reports.
     */
    @Test
    void stillVerifiesExistingTokensWhenOnlyTheBaseUrlIsMissing() {
        UnsubscribeTokenService noBaseUrl = new UnsubscribeTokenService(new UnsubscribeProperties(SECRET, "  "));

        assertThat(noBaseUrl.canIssueLinks()).isFalse();
        assertThat(noBaseUrl.unsubscribeUrlFor("buyer@example.com")).isNull();
        assertThat(noBaseUrl.addressFrom(tokens.tokenFor("buyer@example.com"))).isEqualTo("buyer@example.com");
    }

    @Test
    void mintsNothingForAnAddressThatIsNotThere() {
        assertThat(tokens.tokenFor(null)).isNull();
        assertThat(tokens.tokenFor("   ")).isNull();
        assertThat(tokens.unsubscribeUrlFor(null)).isNull();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static char flip(char c) {
        return c == 'A' ? 'B' : 'A';
    }
}
