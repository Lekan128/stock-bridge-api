package com.procurepal_services.stock_bridge_api.marketplace;

/**
 * The caller's company is not ProcurePal, so it may not touch a marketplace-admin
 * surface. Maps to 403 via {@link MarketplaceAccessExceptionHandler}.
 *
 * This is a distinct failure from "you lack the permission": every tenant's OWNER
 * holds MANAGE_MARKETPLACE (permissions are attached to global roles, so they
 * cannot be granted to one tenant only), which is precisely why platform
 * ownership is checked separately. The message deliberately says nothing about who
 * the platform owner is.
 */
public class PlatformOwnerNotAllowedException extends RuntimeException {

    public PlatformOwnerNotAllowedException() {
        super("This action is restricted to the marketplace operator.");
    }
}
