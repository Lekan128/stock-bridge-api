package com.procurepal_services.stock_bridge_api.client;

public class ClientIdentifierTakenException extends RuntimeException {

    public ClientIdentifierTakenException(String identifier) {
        super("Client identifier '" + identifier + "' is already taken. Please choose a different one.");
    }
}
