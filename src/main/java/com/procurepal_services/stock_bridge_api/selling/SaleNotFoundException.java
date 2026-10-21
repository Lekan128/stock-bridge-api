package com.procurepal_services.stock_bridge_api.selling;

/** No sale (or sale line) by that id in this company - answered 404, never saying whether it exists elsewhere. */
public class SaleNotFoundException extends RuntimeException {

    public SaleNotFoundException() {
        super("That sale was not found.");
    }
}
