package com.procurepal_services.stock_bridge_api.user;

public class InvalidRoleException extends RuntimeException {

    public InvalidRoleException(String role) {
        super("Invalid role '" + role + "'. Must be one of ADMIN, MANAGER, STAFF.");
    }
}
