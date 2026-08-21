package com.procurepal_services.stock_bridge_api.email.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring context - the limiter is a pure function of its
 * configuration and the clock, exactly like {@code EmailSenderTest}'s subject, so
 * standing up a context would only slow it down.
 *
 * <p>The window is deliberately tiny in the recovery test rather than mocking a
 * clock. Injecting a {@code Clock} would be the textbook answer and was rejected:
 * it would put a field on the production class whose only purpose is this file, and
 * the property under test - that entries genuinely age out of a rolling window - is
 * one a frozen clock cannot demonstrate.
 */
class EmailVerificationRateLimiterTest {

    private static EmailVerificationRateLimiter limiter(int limit, Duration window) {
        return new EmailVerificationRateLimiter(new EmailVerificationProperties(null, limit, window));
    }

    @Test
    void allowsExactlyTheConfiguredNumberOfSendsAndThenRefuses() {
        EmailVerificationRateLimiter limiter = limiter(3, Duration.ofHours(1));
        UUID user = UUID.randomUUID();

        assertThat(limiter.tryAcquire(user).allowed()).isTrue();
        assertThat(limiter.tryAcquire(user).allowed()).isTrue();
        assertThat(limiter.tryAcquire(user).allowed()).isTrue();

        EmailVerificationRateLimiter.Verdict refused = limiter.tryAcquire(user);
        assertThat(refused.allowed()).isFalse();
        // Actionable, not just a refusal: this is what becomes the Retry-After
        // header and what lets the frontend disable the button for the right time.
        assertThat(refused.retryAfter()).isPositive();
    }

    /**
     * The budget is per account. A shared counter would mean one abusive tenant
     * could stop every other tenant on the platform from confirming an address,
     * which is a denial of service delivered by the anti-abuse control.
     */
    @Test
    void budgetsAreIndependentPerUser() {
        EmailVerificationRateLimiter limiter = limiter(1, Duration.ofHours(1));
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();

        assertThat(limiter.tryAcquire(one).allowed()).isTrue();
        assertThat(limiter.tryAcquire(one).allowed()).isFalse();
        assertThat(limiter.tryAcquire(two).allowed()).isTrue();
    }

    /**
     * The point of a rolling window: a spent slot comes back once it is older than
     * the window, without any sweep or scheduled job having run.
     */
    @Test
    void slotsBecomeAvailableAgainOnceTheyAgeOutOfTheWindow() throws InterruptedException {
        EmailVerificationRateLimiter limiter = limiter(1, Duration.ofMillis(60));
        UUID user = UUID.randomUUID();

        assertThat(limiter.tryAcquire(user).allowed()).isTrue();
        assertThat(limiter.tryAcquire(user).allowed()).isFalse();

        Thread.sleep(120);

        assertThat(limiter.tryAcquire(user).allowed()).isTrue();
    }

    /**
     * A fat-fingered {@code EMAIL_VERIFICATION_RESEND_LIMIT=0} must not mean "no
     * verification emails, ever" - that would take the whole feature down through a
     * typo in an environment variable, silently. Same for a negative or zero window,
     * which would otherwise expire every slot instantly and make the limit
     * unenforceable.
     */
    @Test
    void nonsensicalConfigurationFallsBackToTheDefaults() {
        EmailVerificationProperties zeroed =
                new EmailVerificationProperties(Duration.ZERO, 0, Duration.ofSeconds(-5));

        assertThat(zeroed.tokenTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(zeroed.resendLimit()).isEqualTo(3);
        assertThat(zeroed.resendWindow()).isEqualTo(Duration.ofHours(1));

        EmailVerificationProperties unset = new EmailVerificationProperties(null, null, null);
        assertThat(unset.tokenTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(unset.resendLimit()).isEqualTo(3);
        assertThat(unset.resendWindow()).isEqualTo(Duration.ofHours(1));
    }
}
