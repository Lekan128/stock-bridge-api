package com.procurepal_services.stock_bridge_api.selling;

/** A price fix with neither a price nor a line total - answered 400. */
public class InvalidSalePriceException extends RuntimeException {

    public InvalidSalePriceException() {
        super("Enter what each one sold for, or what the whole line came to.");
    }
}
