package com.procurepal_services.stock_bridge_api.user;

/** Thrown for both "doesn't exist" and "belongs to another tenant" - the two are indistinguishable from the caller's side. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User not found");
    }
}
