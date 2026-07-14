package com.procurepal_services.stock_bridge_api.client;

public class PasswordMismatchException extends RuntimeException {

    public PasswordMismatchException() {
        super("Password and confirmation do not match");
    }
}
