package com.procurepal_services.stock_bridge_api.analytics.dto;

import java.math.BigDecimal;

/**
 * totalInValue/totalOutValue only sum movements that recorded a price - see
 * StockMovementRepository.sumValue. totalUnitsIn/Out count every movement in
 * the range regardless of price, so the two pairs can disagree in scale.
 *
 * outOfStockProductCount and wellStockedProductCount exist so this dashboard has a figure for
 * every active product, not only the ones in trouble - UX_CONSISTENCY_DESIGN_PLAN.md's Pattern B.
 * outOfStockProductCount does not overlap lowStockProductCount in the UI's framing (the StatCard
 * for "Out of Stock" and the one for "Low Stock" each own their own slice), but the two underlying
 * counts are NOT mutually exclusive by construction - a zero-on-hand product with a threshold set
 * satisfies both. wellStockedProductCount is activeProductCount minus the de-duplicated union of
 * both, computed as its own query rather than by subtracting the two counts above, specifically to
 * avoid double-subtracting that overlap.
 */
public record AnalyticsSummaryResponse(
        BigDecimal totalInValue,
        BigDecimal totalOutValue,
        long totalUnitsIn,
        long totalUnitsOut,
        long lowStockProductCount,
        long activeProductCount,
        long outOfStockProductCount,
        long wellStockedProductCount) {
}
