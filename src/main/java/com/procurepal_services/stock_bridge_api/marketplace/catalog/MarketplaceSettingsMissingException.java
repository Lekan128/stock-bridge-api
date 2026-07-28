package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/**
 * The single settings row is inserted by V6__marketplace.sql, so its absence means the
 * database is not what the application was built against. Failing loudly beats inventing
 * a delivery fee: a wrong number here is money, and it would be quoted to a buyer at
 * checkout before anyone noticed.
 *
 * 500, not 404 - nothing the caller did caused this.
 */
public class MarketplaceSettingsMissingException extends IllegalStateException {

    public MarketplaceSettingsMissingException() {
        super("Marketplace settings have not been initialised.");
    }
}
