package com.procurepal_services.stock_bridge_api.cart;

/**
 * ProcurePal does not hold enough of this item to satisfy the requested quantity.
 * A 400 rather than a 404: the product is real and the buyer may simply ask for
 * fewer, so the message names the number actually available.
 */
public class InsufficientCatalogStockException extends RuntimeException {

    public InsufficientCatalogStockException(String productName, int available) {
        super(available <= 0
                ? "\"" + productName + "\" is out of stock."
                : "Only " + available + " of \"" + productName + "\" are available.");
    }
}
