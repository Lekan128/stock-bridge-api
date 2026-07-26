package com.procurepal_services.stock_bridge_api.user;

public class LastActiveOwnerException extends RuntimeException {

    public LastActiveOwnerException() {
        super("This action would leave the organization with no active owner. "
                + "Assign another active owner before changing this user.");
    }
}
