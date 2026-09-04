package com.procurepal_services.stock_bridge_api.product.sku.dto;

import com.procurepal_services.stock_bridge_api.entity.ProductSkuSettings;
import com.procurepal_services.stock_bridge_api.entity.SkuResetCadence;

/**
 * Never carries {@code nextSequence}/{@code currentPeriodKey} - the counter is an internal
 * generation detail, not something the settings screen displays or edits (see {@code
 * SkuGenerationService}).
 */
public record ProductSkuSettingsResponse(boolean enabled, String pattern, SkuResetCadence resetCadence) {

    /** The implicit default for a tenant that has never configured this feature - see {@code ProductSkuSettings}'s javadoc. */
    public static ProductSkuSettingsResponse defaults() {
        return new ProductSkuSettingsResponse(false, "", SkuResetCadence.NEVER);
    }

    public static ProductSkuSettingsResponse from(ProductSkuSettings settings) {
        return new ProductSkuSettingsResponse(settings.isEnabled(), settings.getPattern(), settings.getResetCadence());
    }
}
