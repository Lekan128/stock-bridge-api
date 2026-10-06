package com.procurepal_services.stock_bridge_api.client;

/**
 * The honeypot field was filled in. The message is deliberately generic: telling a bot which
 * field gave it away is telling it what to leave blank next time.
 */
public class SignupRejectedException extends RuntimeException {

    public SignupRejectedException() {
        super("We could not create this account. Please try again.");
    }
}
