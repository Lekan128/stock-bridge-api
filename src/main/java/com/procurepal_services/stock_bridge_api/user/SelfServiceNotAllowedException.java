package com.procurepal_services.stock_bridge_api.user;

public class SelfServiceNotAllowedException extends RuntimeException {

    public SelfServiceNotAllowedException() {
        super("You cannot change your own role or active status through this endpoint.");
    }
}
