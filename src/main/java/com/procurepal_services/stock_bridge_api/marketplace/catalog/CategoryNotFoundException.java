package com.procurepal_services.stock_bridge_api.marketplace.catalog;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException() {
        super("Category not found.");
    }
}
