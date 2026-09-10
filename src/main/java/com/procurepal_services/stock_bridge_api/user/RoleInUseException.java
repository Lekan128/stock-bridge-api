package com.procurepal_services.stock_bridge_api.user;

public class RoleInUseException extends RuntimeException {

    public RoleInUseException(long userCount) {
        super("This role is still assigned to " + userCount + " user" + (userCount == 1 ? "" : "s")
                + ". Reassign them to a different role before deleting it.");
    }
}
