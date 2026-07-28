package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * Only thrown for a slug the operator typed. An auto-derived slug never reaches here -
 * it is disambiguated with a numeric suffix instead, because "Beverages" colliding with
 * an existing "beverages" is not a mistake the operator made or can usefully act on.
 */
public class CategorySlugTakenException extends RuntimeException {

    public CategorySlugTakenException(String slug) {
        super("The category URL '" + slug + "' is already in use.");
    }
}
