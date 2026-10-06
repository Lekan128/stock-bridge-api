package com.procurepal_services.stock_bridge_api.client;

import java.time.Duration;
import lombok.Getter;

/**
 * Too many signups from this address or this network. The message points at signing in,
 * because the commonest real cause is somebody who already has an account trying again.
 */
@Getter
public class SignupThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public SignupThrottledException(Duration retryAfter) {
        super("Too many sign-ups from this email address or network. If you already have an account, "
                + "sign in instead - or try again later.");
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }
}
