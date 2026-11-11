package com.procurepal_services.stock_bridge_api.branch;

/** A move id that isn't this company's. 404, like a transfer not found. */
public class AreaMoveNotFoundException extends RuntimeException {

    public AreaMoveNotFoundException() {
        super("That move was not found.");
    }
}
