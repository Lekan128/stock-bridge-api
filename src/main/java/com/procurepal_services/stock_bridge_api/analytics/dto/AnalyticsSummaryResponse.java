package com.procurepal_services.stock_bridge_api.analytics.dto;

import java.math.BigDecimal;

/**
 * totalInValue/totalOutValue only sum movements that recorded a price - see
 * StockMovementRepository.sumValue. totalUnitsIn/Out count every movement in
 * the range regardless of price, so the two pairs can disagree in scale.
 */
public record AnalyticsSummaryResponse(
        BigDecimal totalInValue,
        BigDecimal totalOutValue,
        long totalUnitsIn,
        long totalUnitsOut,
        long lowStockProductCount,
        long activeProductCount) {
}
