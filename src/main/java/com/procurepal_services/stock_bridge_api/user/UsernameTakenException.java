package com.procurepal_services.stock_bridge_api.user;

public class UsernameTakenException extends RuntimeException {

    public UsernameTakenException(String username) {
        super("Username '" + username + "' is already in use within this organization.");
    }
}
