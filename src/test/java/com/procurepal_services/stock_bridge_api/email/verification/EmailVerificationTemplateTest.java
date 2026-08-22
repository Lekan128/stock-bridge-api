package com.procurepal_services.stock_bridge_api.email.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.email.template.AccountEmails;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The verification-bearing templates, asserted on the rendered strings the same way
 * {@code EmailTemplateTest} does - they are pure functions of their arguments, so
 * there is nothing to stand up.
 *
 * <p>Kept as a separate file rather than added to {@code EmailTemplateTest} because
 * the property that matters most here is not a rendering detail at all: it is that
 * every one of these carries {@link EmailKind#VERIFICATION}. That is a decision
 * about eligibility, not about copy, and it belongs next to the flow whose entire
 * existence depends on it.
 */
class EmailVerificationTemplateTest {

    private static final String BASE_URL = "https://app.procurepal.test";
    private static final String VERIFY_URL = BASE_URL + "/verify-email?token=abc123";
    private static final String TTL = "24 hours";
    private static final List<String> TO = List.of("owner@demo.test");

    /**
     * The single most important assertion in this module.
     *
     * <p>A brand-new user is unverified by definition - the V8 column defaults to
     * FALSE - so a TRANSACTIONAL verification email would be dropped by
     * {@code EmailEligibility} for every recipient it could ever have. The flag
     * could then never be set by anybody, and the whole feature would be a table
     * nothing writes to. If this assertion ever fails, the flow is dead and nothing
     * else in this file matters.
     */
    @Test
    void everyVerificationBearingEmailBypassesEligibilityGating() {
        List<EmailMessage> messages = List.of(
                AccountEmails.welcome(TO, "Demo Retail Co", "owner@demo.test", BASE_URL, VERIFY_URL, TTL),
                AccountEmails.userInvited(TO, "Demo Retail Co", "lead", "MANAGER", BASE_URL, VERIFY_URL, TTL),
                AccountEmails.verifyEmailAddress(TO, "Demo Retail Co", "owner@demo.test", VERIFY_URL, TTL));

        assertThat(messages).allSatisfy(message -> {
            assertThat(message.kind()).isEqualTo(EmailKind.VERIFICATION);
            assertThat(message.kind().bypassesVerification()).isTrue();
        });
    }

    /**
     * Both bodies, always. HTML alone gets filed as spam and some corporate clients
     * strip it outright - and for this particular email, a stripped body means the
     * only link that can ever verify the account is gone.
     */
    @Test
    void theStandaloneVerificationEmailCarriesTheLinkInBothBodies() {
        EmailMessage message =
                AccountEmails.verifyEmailAddress(TO, "Demo Retail Co", "owner@demo.test", VERIFY_URL, TTL);

        assertThat(message.subject()).isEqualTo("Confirm your ProcurePal email address");
        assertThat(message.htmlBody())
                .contains(VERIFY_URL)
                .contains("Confirm your email address")
                .contains("24 hours")
                // Names the address it was actually sent to, which is how a reader
                // discovers a typo instead of waiting for mail that never comes.
                .contains("owner@demo.test");
        assertThat(message.textBody()).contains(VERIFY_URL).contains("24 hours").contains("owner@demo.test");
    }

    /**
     * One email at signup, not two. The link rides on the welcome; see
     * {@code AccountEmails.welcome} for why that is worth the extra overload.
     */
    @Test
    void theWelcomeCarriesTheVerificationLinkAlongsideTheSignInLink() {
        EmailMessage message =
                AccountEmails.welcome(TO, "Demo Retail Co", "owner@demo.test", BASE_URL, VERIFY_URL, TTL);

        assertThat(message.htmlBody()).contains(VERIFY_URL).contains(BASE_URL + "/login");
        assertThat(message.textBody()).contains(VERIFY_URL).contains(BASE_URL + "/login");
    }

    /**
     * An invited sub-user needs the link at least as much as an account holder:
     * their address was typed by somebody else, so it is the one most likely to be
     * wrong, and being unverified silently costs them every delivery notification.
     */
    @Test
    void theInvitationCarriesTheVerificationLink() {
        EmailMessage message =
                AccountEmails.userInvited(TO, "Demo Retail Co", "lead", "MANAGER", BASE_URL, VERIFY_URL, TTL);

        assertThat(message.htmlBody()).contains(VERIFY_URL);
        assertThat(message.textBody()).contains(VERIFY_URL);
        // Still never carries a password - the rule AccountEmails opens with.
        assertThat(message.htmlBody()).contains("Your password is not in this email");
    }

    /**
     * No token to offer - no plausible address, or an unconfigured base URL - must
     * render the email exactly as it did before this flow existed, not a confirm
     * button pointing nowhere. Same rule {@code EmailLayout.button} already applies.
     */
    @Test
    void theConfirmBlockIsOmittedEntirelyWhenThereIsNoLink() {
        EmailMessage message = AccountEmails.welcome(TO, "Demo Retail Co", "owner@demo.test", BASE_URL);

        assertThat(message.htmlBody()).doesNotContain("Confirm your email address");
        assertThat(message.textBody()).doesNotContain("Confirm your email address");
        // The rest of the email is untouched.
        assertThat(message.htmlBody()).contains(BASE_URL + "/login").contains("Demo Retail Co");
        assertThat(message.kind()).isEqualTo(EmailKind.VERIFICATION);
    }

    /**
     * The address reaches a mail client that renders markup and comes from a
     * user-editable profile field, so it goes through {@code EmailLayout.escape}
     * like everything else.
     */
    @Test
    void userSuppliedTextCannotInjectMarkup() {
        EmailMessage message = AccountEmails.verifyEmailAddress(
                TO, "<script>alert(1)</script> Co", "\"evil\"@example.com", VERIFY_URL, TTL);

        assertThat(message.htmlBody()).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(message.htmlBody()).doesNotContain("\"evil\"@example.com").contains("&quot;evil&quot;");
    }

    /**
     * The expiry sentence is rendered from configuration rather than hardcoded, so
     * an email can never confidently state a number the server does not use.
     */
    @Test
    void expiryIsRenderedFromTheConfiguredDuration() {
        assertThat(VerificationLink.humanise(Duration.ofHours(24))).isEqualTo("24 hours");
        assertThat(VerificationLink.humanise(Duration.ofHours(72))).isEqualTo("3 days");
        assertThat(VerificationLink.humanise(Duration.ofHours(2))).isEqualTo("2 hours");
        assertThat(VerificationLink.humanise(Duration.ofHours(1))).isEqualTo("1 hour");
        assertThat(VerificationLink.humanise(Duration.ofMinutes(30))).isEqualTo("30 minutes");
        // Nothing sensible to say - the sentence is dropped rather than printed as
        // "expires in PT0S".
        assertThat(VerificationLink.humanise(null)).isEmpty();
        assertThat(VerificationLink.humanise(Duration.ZERO)).isEmpty();
    }
}
