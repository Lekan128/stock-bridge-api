package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/** See CategorySlugTakenException: only an explicitly supplied slug can collide loudly. */
public class ProductSlugTakenException extends RuntimeException {

    public ProductSlugTakenException(String slug) {
        super("The product URL '" + slug + "' is already used by another product.");
    }
}
