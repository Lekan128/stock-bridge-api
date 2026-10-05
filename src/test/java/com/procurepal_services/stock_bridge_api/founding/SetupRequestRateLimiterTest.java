package com.procurepal_services.stock_bridge_api.founding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SetupRequestRateLimiterTest {

    private final SetupRequestRateLimiter limiter = new SetupRequestRateLimiter(
            new FoundingOfferProperties(null, null, null, null, 2, Duration.ofHours(1), null));

    @Test
    void aCallerGetsItsLimitThenWaits() {
        assertThat(limiter.tryAcquire("address:1.2.3.4")).isNull();
        assertThat(limiter.tryAcquire("address:1.2.3.4")).isNull();
        Duration wait = limiter.tryAcquire("address:1.2.3.4");
        assertThat(wait).isNotNull().isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
        // Someone else is unaffected.
        assertThat(limiter.tryAcquire("address:5.6.7.8")).isNull();
    }
}
