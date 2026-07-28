package com.procurepal_services.stock_bridge_api.order;

/**
 * No order with that id belongs to the caller's company. "Not yours" and "does not
 * exist" are the same 404 on purpose: a buyer must not be able to learn that another
 * company's order id is real by watching the status code change.
 */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("That order was not found.");
    }
}
