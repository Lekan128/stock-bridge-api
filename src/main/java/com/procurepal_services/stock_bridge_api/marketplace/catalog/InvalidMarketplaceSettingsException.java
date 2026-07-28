package com.procurepal_services.stock_bridge_api.marketplace.catalog;

/** Cross-field settings validation that @Valid cannot express - see UpdateMarketplaceSettingsRequest. */
public class InvalidMarketplaceSettingsException extends RuntimeException {

    public InvalidMarketplaceSettingsException(String message) {
        super(message);
    }
}
