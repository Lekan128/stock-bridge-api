package com.procurepal_services.stock_bridge_api.superadmin;

public class ClientNotFoundException extends RuntimeException {

    public ClientNotFoundException() {
        super("Client not found");
    }
}
