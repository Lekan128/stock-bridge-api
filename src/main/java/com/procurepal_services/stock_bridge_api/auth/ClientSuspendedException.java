package com.procurepal_services.stock_bridge_api.auth;

/**
 * Thrown when a tenant login (or refresh) is attempted against a client a
 * super admin has deactivated. Deliberately specific (unlike
 * InvalidCredentialsException) - a suspended client's admin should be told
 * exactly why they're locked out rather than left guessing at a wrong
 * password, since the fix ("contact support") is different.
 */
public class ClientSuspendedException extends RuntimeException {

    public ClientSuspendedException() {
        super("This account has been suspended. Please contact support.");
    }
}
