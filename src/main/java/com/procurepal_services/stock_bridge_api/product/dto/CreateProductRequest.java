package com.procurepal_services.stock_bridge_api.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code companyVendorId} is optional and points at an entry in this company's OWN vendor
 * directory - it is resolved against the caller's tenant before it is stored, so a vendor id
 * belonging to another company is a field error rather than a link. Marketplace purchases set
 * it themselves; this is for stock sourced off-platform.
 */
public record CreateProductRequest(
        @NotBlank String name,
        @NotBlank String sku,
        String description,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @DecimalMin(value = "0", inclusive = true) BigDecimal costPrice,
        @Min(0) Integer lowStockThreshold,
        UUID companyVendorId) {
}
