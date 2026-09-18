package com.procurepal_services.stock_bridge_api.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException() {
        super("Product not found");
    }

    /** With something more useful to say than "not found" - as a scan that matches nothing has. */
    public ProductNotFoundException(String message) {
        super(message);
    }
}
