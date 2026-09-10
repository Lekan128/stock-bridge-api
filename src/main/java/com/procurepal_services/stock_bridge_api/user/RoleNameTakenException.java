package com.procurepal_services.stock_bridge_api.user;

public class RoleNameTakenException extends RuntimeException {

    public RoleNameTakenException(String name) {
        super("A role named '" + name + "' already exists in this organization.");
    }
}
