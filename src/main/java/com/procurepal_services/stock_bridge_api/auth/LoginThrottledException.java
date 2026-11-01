package com.procurepal_services.stock_bridge_api.auth;

import java.time.Duration;

/**
 * Too many wrong passwords lately (owner, 2026-11-01). Answered 429, the wait in words - with no Retry-After header,
 * which some clients obey by sleeping. The same whether or not the login belongs to anybody.
 */
public class LoginThrottledException extends RuntimeException {

    public LoginThrottledException(Duration wait) {
        super("Too many tries. Try again in " + minutes(wait) + ".");
    }

    private static String minutes(Duration wait) {
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        return minutes == 1 ? "a minute" : minutes + " minutes";
    }
}
