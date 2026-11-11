package com.procurepal_services.stock_bridge_api.branch;

/** A request id that isn't this company's (Part 5). 404, like a move not found. */
public class StockRequestNotFoundException extends RuntimeException {

    public StockRequestNotFoundException() {
        super("That request was not found.");
    }
}
