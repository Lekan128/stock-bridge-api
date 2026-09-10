package com.procurepal_services.stock_bridge_api.vendor.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests - no Spring, no server, no database. The subject is a pure
 * function of its configuration and the clock, exactly like
 * {@code EmailVerificationRateLimiterTest}'s, so a context here would buy nothing
 * and cost seconds.
 *
 * <h2>What this covers that the integration test cannot</h2>
 * {@link VendorWaitlistRateLimitIntegrationTest} proves the wiring - that a refusal
 * becomes a 429 with a usable Retry-After and that nothing is written. It can only
 * ever exercise ONE key, though, because every request in a test comes from the same
 * remote address and the window is an hour with no clock to advance.
 *
 * <p>The two-key namespacing is the part of this class that is genuinely its own
 * rather than inherited from the class it was copied from, and it is the part that
 * would fail silently: keys that collided would let a submitted email spend an IP's
 * budget, and budgets that were not independent would make the limit mean something
 * other than what {@code VendorWaitlistService} reads it as. Neither shows up as an
 * error - both show up as a limit that binds at the wrong time.
 *
 * <p>The rolling-window arithmetic itself is deliberately NOT re-tested here. It is
 * copied verbatim in intent from {@code EmailVerificationRateLimiter} and covered by
 * that class's own unit test; asserting it twice would be testing a deque twice.
 */
class VendorWaitlistRateLimiterTest {

    private static final Duration WINDOW = Duration.ofHours(1);

    @Test
    void allowsUpToTheLimitAndThenRefuses() {
        VendorWaitlistRateLimiter limiter = limiter(3);

        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isFalse();
    }

    /**
     * A refusal has to say how long, or the frontend has nothing to disable its
     * button with and a caller learns only that they may not, not when they may.
     */
    @Test
    void aRefusalCarriesTheWaitUntilTheOldestSlotAgesOut() {
        VendorWaitlistRateLimiter limiter = limiter(1);
        limiter.tryAcquire("ip:203.0.113.7");

        VendorWaitlistRateLimiter.Verdict refused = limiter.tryAcquire("ip:203.0.113.7");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isPositive().isLessThanOrEqualTo(WINDOW);
    }

    /**
     * The reason keys are namespaced by their caller. Without the prefixes, a
     * submitted email that happened to read like an address could spend the source
     * address's budget - and, worse, an attacker could exhaust a victim IP's budget
     * by choosing what to type into a public form.
     */
    @Test
    void differentKeysHaveIndependentBudgets() {
        VendorWaitlistRateLimiter limiter = limiter(1);

        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:203.0.113.7").allowed()).isFalse();
        assertThat(limiter.tryAcquire("ip:198.51.100.4").allowed())
                .as("a second source address is a second caller and gets its own budget")
                .isTrue();
        assertThat(limiter.tryAcquire("email:someone@example.com").allowed())
                .as("the email budget is not the IP budget, which is what the prefixes buy")
                .isTrue();
    }

    /** Two spellings of one address are one applicant, or the email budget is trivially evaded. */
    @Test
    void keysAreCaseInsensitive() {
        VendorWaitlistRateLimiter limiter = limiter(1);

        assertThat(limiter.tryAcquire("email:Someone@Example.com").allowed()).isTrue();
        assertThat(limiter.tryAcquire("email:someone@example.com").allowed()).isFalse();
    }

    /**
     * A request with no resolvable remote address is refused rather than waved
     * through. It is the one branch an attacker would aim for if the alternative
     * were "allow", and a form nobody can submit is a far cheaper failure than a
     * form with no bound at all.
     */
    @Test
    void anUnusableKeyIsRefusedRatherThanWavedThrough() {
        VendorWaitlistRateLimiter limiter = limiter(3);

        assertThat(limiter.tryAcquire(null).allowed()).isFalse();
        assertThat(limiter.tryAcquire("   ").allowed()).isFalse();
    }

    /**
     * The properties record substitutes a default for anything absent, zero or
     * negative. Asserted here rather than trusted, because that substitution is
     * exactly what turns a failed binding into a limit that quietly reads 3 - see
     * VendorWaitlistRateLimitIntegrationTest, which lost seventy minutes to a
     * related silent fallback.
     */
    @Test
    void invalidConfigurationFallsBackToTheDocumentedDefaults() {
        assertThat(new VendorWaitlistProperties(null, null).submitLimit()).isEqualTo(3);
        assertThat(new VendorWaitlistProperties(0, Duration.ZERO).submitLimit()).isEqualTo(3);
        assertThat(new VendorWaitlistProperties(-1, Duration.ofSeconds(-1)).submitWindow())
                .isEqualTo(Duration.ofHours(1));
        assertThat(new VendorWaitlistProperties(2, Duration.ofMinutes(30)).submitLimit())
                .as("a valid value is left alone")
                .isEqualTo(2);
    }

    private static VendorWaitlistRateLimiter limiter(int limit) {
        return new VendorWaitlistRateLimiter(new VendorWaitlistProperties(limit, WINDOW));
    }
}
