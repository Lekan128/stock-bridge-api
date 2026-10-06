package com.procurepal_services.stock_bridge_api.auth.passwordreset;

/**
 * Any link that cannot be used: unknown, expired, already used, superseded by a newer
 * one, its account deactivated, or its address changed. One public message for all of
 * them, on purpose - telling a stranger holding a link which of those it is helps only
 * the stranger. The real reason goes to the debug log.
 */
public class InvalidPasswordResetTokenException extends RuntimeException {

    static final String MESSAGE =
            "This reset link has expired or has already been used. Ask for a new one.";

    public InvalidPasswordResetTokenException() {
        super(MESSAGE);
    }
}
