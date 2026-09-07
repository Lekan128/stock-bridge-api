package com.procurepal_services.stock_bridge_api.user;

public class RoleNotFoundException extends RuntimeException {

    public RoleNotFoundException() {
        super("Role not found.");
    }
}
