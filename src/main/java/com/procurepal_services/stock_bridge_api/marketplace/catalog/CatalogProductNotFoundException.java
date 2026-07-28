package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * Covers three different truths behind one message on the public storefront: the id/slug
 * matches nothing, it matches something ProcurePal has unlisted or deactivated, or it
 * matches another tenant's private inventory row. Collapsing them is deliberate - a 404
 * that distinguished them would turn the public catalog into an oracle for probing
 * whether a given SKU exists in someone else's stock.
 */
public class CatalogProductNotFoundException extends RuntimeException {

    public CatalogProductNotFoundException() {
        super("That product is not available.");
    }
}
