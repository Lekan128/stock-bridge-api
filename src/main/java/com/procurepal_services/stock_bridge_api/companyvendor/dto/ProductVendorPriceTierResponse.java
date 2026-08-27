package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.ProductVendorPriceTier;
import java.math.BigDecimal;
import java.util.UUID;

/** One quantity-break row on a {@code ProductVendor} line - see that entity's own javadoc. */
public record ProductVendorPriceTierResponse(UUID id, BigDecimal minQuantity, BigDecimal unitPrice) {

    public static ProductVendorPriceTierResponse from(ProductVendorPriceTier tier) {
        return new ProductVendorPriceTierResponse(tier.getId(), tier.getMinQuantity(), tier.getUnitPrice());
    }
}
