package com.procurepal_services.stock_bridge_api.marketplace.moderation;

/**
 * Collapses four truths into one 404: no such product, it belongs to the platform owner,
 * it belongs to an ordinary buying company, or its seller has been deactivated.
 *
 * <p>Only the first is really "not found"; the rest are "not yours to moderate". They
 * are deliberately indistinguishable, matching {@code CatalogProductNotFoundException} -
 * a moderation endpoint that reported the difference would confirm the existence of
 * arbitrary tenants' private inventory rows by id.
 */
public class ModeratedProductNotFoundException extends RuntimeException {

    public ModeratedProductNotFoundException() {
        super("That listing is not available for moderation.");
    }
}
