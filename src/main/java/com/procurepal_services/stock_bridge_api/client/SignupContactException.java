package com.procurepal_services.stock_bridge_api.client;

/** Signup had neither an email nor a usable WhatsApp number to log in with. */
public class SignupContactException extends RuntimeException {

    public SignupContactException(String message) {
        super(message);
    }
}
