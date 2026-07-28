package com.procurepal_services.stock_bridge_api.cart;

/**
 * The requested product is not something this marketplace sells right now - it does
 * not exist, belongs to a tenant other than the operator, has been unlisted, or has
 * been deactivated.
 *
 * All of those deliberately collapse into one 404: distinguishing them would let a
 * buyer probe another tenant's product ids for existence, and none of the
 * distinctions changes what the buyer can do about it.
 */
public class CatalogProductUnavailableException extends RuntimeException {

    public CatalogProductUnavailableException() {
        super("That product is not available on the marketplace.");
    }
}
