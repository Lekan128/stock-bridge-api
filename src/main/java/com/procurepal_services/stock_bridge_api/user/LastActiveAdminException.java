package com.procurepal_services.stock_bridge_api.user;

public class LastActiveAdminException extends RuntimeException {

    public LastActiveAdminException() {
        super("This action would leave the organization with no active admin. "
                + "Assign another active admin before changing this user.");
    }
}
