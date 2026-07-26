package com.procurepal_services.stock_bridge_api.profile;

public class IncorrectCurrentPasswordException extends RuntimeException {

    public IncorrectCurrentPasswordException() {
        super("Your current password is incorrect.");
    }
}
