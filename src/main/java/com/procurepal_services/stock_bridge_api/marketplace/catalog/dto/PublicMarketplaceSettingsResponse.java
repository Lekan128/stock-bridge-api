package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import java.math.BigDecimal;

/**
 * The PUBLIC subset of marketplace_settings, matching the frontend's
 * {@code MarketplaceSettings} in src/features/storefront/types.ts.
 *
 * Separate record from the admin one rather than a nullable-fields compromise, because
 * the difference is a security boundary and a separate type makes forgetting it a
 * compile error rather than a leak. Two fields are held back on purpose:
 *
 * - payOnDeliveryMaxOrderValue: the cap ProcurePal is willing to extend on trust. An
 *   anonymous visitor who knows it knows exactly how large an unpaid order they can
 *   place. Checkout enforces it server-side (contract §5); the buyer is told the reason
 *   only when they actually hit it.
 * - the row's id/timestamps: internal.
 *
 * payOnDeliveryEnabled IS public - the storefront has to advertise that COD exists at
 * all, and eligibility still depends on the buyer's own payment_terms, which is not
 * knowable here anyway.
 */
public record PublicMarketplaceSettingsResponse(
        BigDecimal deliveryFee,
        BigDecimal freeDeliveryThreshold,
        BigDecimal minimumOrderValue,
        boolean payOnDeliveryEnabled,
        String supportPhone,
        String supportEmail) {

    public static PublicMarketplaceSettingsResponse from(MarketplaceSettings settings) {
        return new PublicMarketplaceSettingsResponse(
                settings.getDeliveryFee(),
                settings.getFreeDeliveryThreshold(),
                settings.getMinimumOrderValue(),
                settings.isPayOnDeliveryEnabled(),
                settings.getSupportPhone(),
                settings.getSupportEmail());
    }
}
