package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SELLING_PRICE_PLAN.md section 9 as numbers - what Phase 7's field test and rollout are judged on,
 * across every company, for super admins.
 *
 * @param rollout "pilot" or "open" ({@code app.selling.rollout}).
 * @param companiesSelling companies with selling on now.
 * @param turnedOnVia for those companies, how it was last turned on: FIRST_WEEK, IN_CONTEXT, OWNER,
 *     SUPPORT. "OWNER" (the settings page) near zero means the in-context offers do the work.
 * @param pricedShare sale lines with a price when they were recorded (target above 0.95).
 * @param knownCostShare priced lines whose cost is known (target above 0.90).
 * @param pricesAddedPer100 lines priced later, per 100 lines (target near 0).
 * @param cancelledPer100 lines cancelled, per 100 recorded.
 * @param priceEditShare lines sold at other than the usual price, of those with a usual price.
 * @param productsToCheck products whose price is changed at the till most of the time - their
 *     usual price is probably wrong.
 */
public record SellingHealthResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        String rollout,
        long companiesSelling,
        Map<String, Long> turnedOnVia,
        long saleCount,
        long lineCount,
        BigDecimal pricedShare,
        BigDecimal knownCostShare,
        BigDecimal pricesAddedPer100,
        BigDecimal cancelledPer100,
        BigDecimal priceEditShare,
        List<ProductToCheck> productsToCheck,
        List<CompanyHealth> companies) {

    public record ProductToCheck(UUID clientId, String clientName, UUID productId, String productName, long lines,
            BigDecimal editShare) {
    }

    public record CompanyHealth(UUID clientId, String clientName, long saleCount, long lineCount, BigDecimal pricedShare,
            BigDecimal knownCostShare, BigDecimal priceEditShare, OffsetDateTime lastSaleAt) {
    }
}
