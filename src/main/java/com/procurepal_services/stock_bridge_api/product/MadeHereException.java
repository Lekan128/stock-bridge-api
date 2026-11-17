package com.procurepal_services.stock_bridge_api.product;

/**
 * Something that only makes sense for stock, asked of a made-here item (V90) - "Pepper soup is made here, so it
 * has no stock to count" - or a made-here item asked for where "Sell things you make" is off.
 */
public class MadeHereException extends RuntimeException {

    public MadeHereException(String message) {
        super(message);
    }

    public static MadeHereException noStock(String productName) {
        return new MadeHereException(productName + " is made here, so it has no stock to change.");
    }
}
