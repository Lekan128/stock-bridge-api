package com.procurepal_services.stock_bridge_api.companyvendor;

/**
 * No {@code product_vendors} row with that id belongs to both the caller's tenant AND the
 * product named in the URL. 404 covers "wrong product", "wrong tenant" and "no such id" alike,
 * on the same reasoning {@code CompanyVendorNotFoundException} gives for its own scope.
 */
public class ProductVendorNotFoundException extends RuntimeException {

    public ProductVendorNotFoundException() {
        super("That vendor is not linked to this product.");
    }
}
