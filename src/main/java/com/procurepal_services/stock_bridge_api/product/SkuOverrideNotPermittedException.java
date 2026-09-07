package com.procurepal_services.stock_bridge_api.product;

/**
 * Thrown by {@code ProductManagementService.update} when auto-generation is on for the product's
 * tenant, the caller changed {@code sku}, and they don't hold {@code PRODUCT_SKU_OVERRIDE} (V23).
 */
public class SkuOverrideNotPermittedException extends RuntimeException {

    public SkuOverrideNotPermittedException() {
        super("This product's SKU is generated automatically and can't be edited without the "
                + "SKU override permission.");
    }
}
