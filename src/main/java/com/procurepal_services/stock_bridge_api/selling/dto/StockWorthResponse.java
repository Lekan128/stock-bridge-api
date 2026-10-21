package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;

/**
 * What the stock on the shelf is worth (SELLING_PRICE_PLAN.md section 6.5): "₦4.2m at cost, ₦5.1m at
 * your prices (40 have no selling price)". Active products only; each figure counts only the
 * products that have the price it needs, and says how many do not.
 */
public record StockWorthResponse(
        BigDecimal valueAtCost,
        BigDecimal valueAtSellingPrice,
        long activeProductCount,
        long productsWithoutCost,
        long productsWithoutSellingPrice) {
}
