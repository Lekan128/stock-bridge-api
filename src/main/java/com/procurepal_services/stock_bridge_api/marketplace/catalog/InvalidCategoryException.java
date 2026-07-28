package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/** A category edit that describes something the tree cannot be. Maps to 400. */
public class InvalidCategoryException extends RuntimeException {

    public InvalidCategoryException(String message) {
        super(message);
    }
}
