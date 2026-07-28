package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * Delete is blocked while anything still points at the category. The FK is
 * ON DELETE SET NULL, so the database would happily accept the delete and quietly
 * uncategorise every product in it - a change that is invisible on the admin screen and
 * only shows up as products missing from the storefront's category filter. Blocking
 * makes the consequence a decision instead of an accident; deactivating the category
 * (PUT with active=false) is the reversible way to take it off the storefront menu while
 * keeping the mapping.
 */
public class CategoryInUseException extends RuntimeException {

    public CategoryInUseException(String message) {
        super(message);
    }
}
