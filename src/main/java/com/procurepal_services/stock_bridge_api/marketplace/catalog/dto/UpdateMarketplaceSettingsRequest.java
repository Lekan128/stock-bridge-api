package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A full replacement, not a patch: these six values are commercial policy that only
 * makes sense read together (a free-delivery threshold means nothing without the fee,
 * a COD cap means nothing without the switch). Requiring the whole set means an admin
 * form always round-trips what it displayed, so two operators editing the same screen
 * cannot half-merge each other's policy.
 *
 * {@code @Digits} mirrors NUMERIC(14,2): rejecting three-decimal money here gives the
 * operator a field-level 400 instead of a rounding surprise that only shows up on
 * someone's invoice. Cross-field sanity (a COD cap below the minimum order value would
 * make COD unusable while appearing enabled) is checked in the service - bean validation
 * cannot see two fields at once without a custom constraint.
 */
public record UpdateMarketplaceSettingsRequest(
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal deliveryFee,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal freeDeliveryThreshold,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal minimumOrderValue,
        @NotNull Boolean payOnDeliveryEnabled,
        @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal payOnDeliveryMaxOrderValue,
        @Size(max = 50) String supportPhone,
        @Email @Size(max = 255) String supportEmail) {
}
