package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;

/**
 * Sales over a set of sale lines (SELLING_PRICE_PLAN.md section 4.7). Cancelled lines are counted
 * in {@code cancelledLineCount} and left out of every other figure.
 *
 * @param salesAmount what every priced line went for.
 * @param profitKnownSalesAmount what the lines with BOTH a price and a cost went for - profit is
 *     only ever worked out over these.
 * @param costAmount what the stock of those same lines had cost.
 * @param grossProfit {@code profitKnownSalesAmount - costAmount}.
 * @param margin profit as a share of what those lines sold for (0.16 is 16%); absent with no such
 *     lines. "Margin", not markup - the plan's section 6.4 says why the UI shows naira first.
 * @param unpricedLineCount lines with no price - "8 sales have no price".
 * @param unknownCostLineCount priced lines whose cost is unknown - "3 have no cost".
 */
public record SalesTotals(
        long saleCount,
        long lineCount,
        BigDecimal salesAmount,
        BigDecimal profitKnownSalesAmount,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal margin,
        long unpricedLineCount,
        long unknownCostLineCount,
        long cancelledLineCount,
        long belowUsualPriceLineCount,
        long belowCostLineCount) {
}
