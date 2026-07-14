package com.procurepal_services.stock_bridge_api.product;

public class SkuTakenException extends RuntimeException {

    public SkuTakenException(String sku) {
        super("SKU '" + sku + "' is already in use within this organization");
    }
}
