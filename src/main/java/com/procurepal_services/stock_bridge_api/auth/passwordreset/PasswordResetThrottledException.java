package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import java.time.Duration;
import lombok.Getter;

/** Answered 429 with Retry-After. The wording is the same whether or not the email has an account. */
@Getter
public class PasswordResetThrottledException extends RuntimeException {

    private final Duration retryAfter;

    public PasswordResetThrottledException(Duration retryAfter) {
        super("Too many reset emails for this address. Try again in an hour.");
        this.retryAfter = retryAfter;
    }
}
