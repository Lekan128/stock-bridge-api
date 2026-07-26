package com.procurepal_services.stock_bridge_api.user;

/**
 * An admin-initiated reset sets a password the admin knows, which would hand
 * them the account holder's login. The account holder changes their own
 * password through POST /api/me/password, which requires the current one.
 */
public class RootPasswordResetNotAllowedException extends RuntimeException {

    public RootPasswordResetNotAllowedException() {
        super("Only the account owner can change the account owner's password.");
    }
}
