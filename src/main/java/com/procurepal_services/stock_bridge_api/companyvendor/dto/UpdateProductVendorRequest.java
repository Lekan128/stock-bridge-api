package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

/**
 * {@code PATCH /api/products/{productId}/vendors/{vendorId}} body - every field optional/patch-
 * semantics, matching {@code ProductManagementService.update}'s own convention. {@code
 * isPreferred} is the one field that is not a plain set when {@code true} - see {@code
 * ProductVendorService.update}'s javadoc on the atomic swap. Sending {@code isPreferred: false}
 * (or omitting it) is a no-op for that field: there is deliberately no operation that clears
 * preferred to "nobody" - MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4.
 */
public record UpdateProductVendorRequest(
        String vendorSku,
        String defaultPackagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal defaultPackagingSize,
        Boolean isPreferred) {
}
