package com.procurepal_services.stock_bridge_api.product.unit;

/**
 * A product's own unit that can't be kept as asked (UNITS_PLAN.md Part 1): no name, no size, a name that is already a
 * unit, one used where it can't be. The message is for the person who typed it, and the API answers 400 with it.
 */
public class InvalidMeasureException extends RuntimeException {

    public InvalidMeasureException(String message) {
        super(message);
    }
}
