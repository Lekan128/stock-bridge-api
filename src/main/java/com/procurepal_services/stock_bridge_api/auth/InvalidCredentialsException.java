package com.procurepal_services.stock_bridge_api.auth;

/**
 * Deliberately generic - never reveals whether the client, username, or
 * password was wrong. A suspended (inactive) client is NOT folded into this:
 * see ClientSuspendedException, which is intentionally specific instead.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
