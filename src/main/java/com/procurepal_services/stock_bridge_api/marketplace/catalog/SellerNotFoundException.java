package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * Covers four different truths behind one message on the public storefront: the
 * id/slug matches no client at all, it matches an ordinary buying company, it matches
 * a vendor whose account has been deactivated, or it matches a vendor with nothing
 * listed yet.
 *
 * Collapsing them is deliberate, and for a sharper reason than the product equivalent:
 * {@code clients} holds every buying company on the platform. A 404 that distinguished
 * "no such client" from "not a seller" would turn this endpoint into a free oracle for
 * confirming which companies use ProcurePaddy, from an unauthenticated request. See
 * {@link CatalogProductNotFoundException}, which makes the same trade.
 */
public class SellerNotFoundException extends RuntimeException {

    public SellerNotFoundException() {
        super("That seller is not available.");
    }
}
