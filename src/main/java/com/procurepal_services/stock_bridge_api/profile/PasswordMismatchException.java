package com.procurepal_services.stock_bridge_api.profile;

public class PasswordMismatchException extends RuntimeException {

    public PasswordMismatchException() {
        super("New password and confirmation do not match");
    }
}
