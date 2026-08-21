package com.procurepal_services.stock_bridge_api.email.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF boundary. This endpoint is handed URLs in a request body and asked to
 * fetch them, and the certificate fetch necessarily happens <em>before</em> the
 * message is trusted - so this class is the only thing between a stranger's POST and
 * an outbound GET from inside the production network.
 *
 * <p>Most of what follows is a list of bypasses that defeat the naive version of
 * this check. They are here as regression tests rather than as trivia: every one of
 * them is a real technique, and the reason the implementation is an allow-list is
 * that a deny-list has to beat all of them and an allow-list has to beat none.
 */
class SnsEndpointGuardTest {

    private static final String VALID_CERT_URL =
            "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-a86cb10b4e1f29c941702d737128f7b6.pem";
    private static final String VALID_SUBSCRIBE_URL =
            "https://sns.eu-west-1.amazonaws.com/?Action=ConfirmSubscription&TopicArn=arn:aws:sns:eu-west-1:1:t&Token=abc";

    private final SnsEndpointGuard guard = new SnsEndpointGuard(properties(null));

    @Test
    void acceptsAGenuineSigningCertificateUrl() {
        assertThatCode(() -> guard.requireSigningCertificateUrl(VALID_CERT_URL)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAGenuineSubscribeUrl() {
        assertThatCode(() -> guard.requireSubscribeUrl(VALID_SUBSCRIBE_URL)).doesNotThrowAnyException();
    }

    // ========================================================================
    // The bypasses. Each one is refused by the host allow-list rather than by a
    // rule aimed at it specifically, which is the point.
    // ========================================================================

    /**
     * The prize an SSRF here would win: the instance metadata service hands out this
     * process's IAM role credentials to anything that can make it issue a GET.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "http://169.254.169.254/latest/meta-data/iam/security-credentials/",
        "https://169.254.169.254/latest/meta-data/",
        // The same address written as a decimal integer. Defeats every string-based
        // deny-list and resolves identically.
        "http://2852039166/latest/meta-data/",
        // IPv4-mapped IPv6, another spelling of the same address.
        "http://[::ffff:169.254.169.254]/latest/meta-data/",
        // A public DNS name that resolves to a private address. Nothing about the
        // string is suspicious; only the allow-list saves us.
        "https://169.254.169.254.nip.io/",
        "http://localhost:5432/",
        "http://127.0.0.1/",
        "http://[::1]/",
        "http://10.0.0.5/internal-admin"
    })
    void refusesAnythingThatIsNotAnAwsSnsHost(String url) {
        assertThatThrownBy(() -> guard.requireSubscribeUrl(url))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /**
     * The single most common way this check is written wrong: an unanchored
     * "contains sns.<region>.amazonaws.com" test, which every one of these passes
     * while pointing at a host the attacker controls.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://sns.eu-west-1.amazonaws.com.evil.example/",
        "https://evil-sns.eu-west-1.amazonaws.com.attacker.test/",
        "https://attacker.test/?next=https://sns.eu-west-1.amazonaws.com/",
        "https://attacker.test/sns.eu-west-1.amazonaws.com",
        "https://sns.eu-west-1.amazonaws.com.localhost/"
    })
    void refusesHostsThatMerelyContainTheAwsHostname(String url) {
        assertThatThrownBy(() -> guard.requireSubscribeUrl(url))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /**
     * {@code https://good-host@evil.example/} has host {@code evil.example} and reads
     * to a human as the opposite. Java parses it correctly today; the check exists so
     * that stays true through a refactor onto some other URL type.
     */
    @Test
    void refusesAUserInfoComponent() {
        assertThatThrownBy(() -> guard.requireSubscribeUrl(
                        "https://sns.eu-west-1.amazonaws.com@evil.example/"))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /**
     * {@code file:} needs no network at all, and a URL library will open it happily.
     * Neither will {@code gopher:}, which is the classic way to turn an SSRF into an
     * arbitrary TCP write.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "gopher://sns.eu-west-1.amazonaws.com/_test",
        "ftp://sns.eu-west-1.amazonaws.com/",
        "http://sns.eu-west-1.amazonaws.com/SimpleNotificationService-abc.pem"
    })
    void refusesAnySchemeOtherThanHttps(String url) {
        assertThatThrownBy(() -> guard.requireSubscribeUrl(url))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /** AWS never sends a port, so a present one is a signal rather than a preference. */
    @Test
    void refusesAnExplicitNonStandardPort() {
        assertThatThrownBy(() -> guard.requireSubscribeUrl("https://sns.eu-west-1.amazonaws.com:8080/"))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    @Test
    void refusesAMissingOrUnparseableUrl() {
        assertThatThrownBy(() -> guard.requireSubscribeUrl(null)).isInstanceOf(SnsEndpointRefusedException.class);
        assertThatThrownBy(() -> guard.requireSubscribeUrl("   ")).isInstanceOf(SnsEndpointRefusedException.class);
        assertThatThrownBy(() -> guard.requireSubscribeUrl("h ttp://not a url"))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    // ========================================================================
    // The certificate path, which is checked only for SigningCertURL.
    // ========================================================================

    /**
     * Constraining the path keeps the certificate cache from being an
     * attacker-growable map, and refuses a message pointing at some other object on
     * the SNS host. Note the SubscribeURL case below is deliberately NOT constrained
     * this way - its shape is not documented as stable, so pinning it would be
     * guessing.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "https://sns.eu-west-1.amazonaws.com/",
        "https://sns.eu-west-1.amazonaws.com/something-else.pem",
        "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-abc.pem/../../etc/passwd",
        "https://sns.eu-west-1.amazonaws.com/SimpleNotificationService-abc.txt"
    })
    void refusesACertificateUrlWhosePathIsNotAnSnsCertificate(String url) {
        assertThatThrownBy(() -> guard.requireSigningCertificateUrl(url))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    @Test
    void doesNotConstrainTheSubscribeUrlPath() {
        assertThatCode(() -> guard.requireSubscribeUrl("https://sns.us-east-2.amazonaws.com/anything?at=all"))
                .doesNotThrowAnyException();
    }

    // ========================================================================
    // Region pinning: a tightening on top of the pattern, not the control itself.
    // ========================================================================

    @Test
    void aPinnedRegionNarrowsTheAllowListToOneHost() {
        SnsEndpointGuard pinned = new SnsEndpointGuard(properties("eu-west-1"));

        assertThatCode(() -> pinned.requireSigningCertificateUrl(VALID_CERT_URL)).doesNotThrowAnyException();
        assertThatThrownBy(() -> pinned.requireSigningCertificateUrl(
                        VALID_CERT_URL.replace("eu-west-1", "us-east-1")))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /** Blank region still enforces the pattern - the guard is never off. */
    @Test
    void anUnpinnedRegionStillOnlyAcceptsAwsSnsHosts() {
        assertThat(properties(null).hasPinnedRegion()).isFalse();

        assertThatCode(() -> guard.requireSubscribeUrl("https://sns.ap-southeast-2.amazonaws.com/x"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireSubscribeUrl("https://sns.amazonaws.com/x"))
                .isInstanceOf(SnsEndpointRefusedException.class);
    }

    /** Host comparison is case-insensitive, as DNS is; the allow-list must not be fooled by case. */
    @Test
    void normalisesHostCaseBeforeMatching() {
        assertThatCode(() -> guard.requireSubscribeUrl("https://SNS.EU-WEST-1.AMAZONAWS.COM/x"))
                .doesNotThrowAnyException();
    }

    private static SesWebhookProperties properties(String region) {
        return new SesWebhookProperties(
                true, null, region, true, true, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }
}
