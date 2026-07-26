package com.procurepal_services.stock_bridge_api.user;

/** Covers both PUT active=false and DELETE - losing the account holder would orphan the tenant. */
public class RootUserDeactivationNotAllowedException extends RuntimeException {

    public RootUserDeactivationNotAllowedException() {
        super("The account owner cannot be deactivated or deleted.");
    }
}
