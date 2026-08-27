package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code POST .../vendors/{vendorId}/price-tiers} body. Only {@code @NotNull} is enforced here -
 * the sign/duplicate-breakpoint rules are cross-field or need a database lookup, so they stay in
 * {@code ProductVendorService.addPriceTier} ({@code InvalidPriceTierException}) rather than being
 * duplicated as bean-validation annotations, the same split {@code CreateProductRequest} already
 * uses for its own cross-field rules.
 */
public record AddPriceTierRequest(@NotNull BigDecimal minQuantity, @NotNull BigDecimal unitPrice) {
}
