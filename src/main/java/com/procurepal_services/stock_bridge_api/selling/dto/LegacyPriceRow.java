package com.procurepal_services.stock_bridge_api.selling.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product in the "check your old prices" review (SELLING_PRICE_PLAN.md section 4.6.3): its old
 * price, per stock unit, beside what it costs - so a cost copied in by a marketplace receipt reads
 * as exactly that, and is not adopted as a selling price by mistake.
 */
public record LegacyPriceRow(
        UUID productId,
        String name,
        String sku,
        BigDecimal legacyUnitPrice,
        BigDecimal costPrice,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize) {

    public static LegacyPriceRow from(Product product) {
        return new LegacyPriceRow(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getLegacyUnitPrice(),
                product.getCostPrice(),
                product.getUnitOfMeasure(),
                product.getPackagingUnit(),
                product.getPackagingSize());
    }
}
