package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** The full settings row - see PublicMarketplaceSettingsResponse for what the storefront is not told. */
public record AdminMarketplaceSettingsResponse(
        BigDecimal deliveryFee,
        BigDecimal freeDeliveryThreshold,
        BigDecimal minimumOrderValue,
        boolean payOnDeliveryEnabled,
        BigDecimal payOnDeliveryMaxOrderValue,
        String supportPhone,
        String supportEmail,
        OffsetDateTime updatedAt) {

    public static AdminMarketplaceSettingsResponse from(MarketplaceSettings settings) {
        return new AdminMarketplaceSettingsResponse(
                settings.getDeliveryFee(),
                settings.getFreeDeliveryThreshold(),
                settings.getMinimumOrderValue(),
                settings.isPayOnDeliveryEnabled(),
                settings.getPayOnDeliveryMaxOrderValue(),
                settings.getSupportPhone(),
                settings.getSupportEmail(),
                settings.getUpdatedAt());
    }
}
