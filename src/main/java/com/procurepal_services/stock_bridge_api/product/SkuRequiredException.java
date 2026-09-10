package com.procurepal_services.stock_bridge_api.product;

/** Thrown by {@code ProductManagementService.create} when auto-generation is off and no SKU was supplied. */
public class SkuRequiredException extends RuntimeException {

    public SkuRequiredException() {
        super("SKU is required.");
    }
}
