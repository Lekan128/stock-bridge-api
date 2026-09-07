package com.procurepal_services.stock_bridge_api.product.sku.dto;

import com.procurepal_services.stock_bridge_api.entity.SkuResetCadence;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code pattern}'s grammar (token shape, the required {@code {SEQ:N}}, worst-case rendered
 * length) is checked in {@code ProductSkuSettingsService.update} via {@code
 * SkuPatternValidator}, not here - Bean Validation can express "at most 100 characters" but not
 * "contains exactly one {@code {SEQ:N}}".
 */
public record UpdateProductSkuSettingsRequest(
        @NotNull Boolean enabled, @NotNull @Size(max = 100) String pattern, @NotNull SkuResetCadence resetCadence) {}
