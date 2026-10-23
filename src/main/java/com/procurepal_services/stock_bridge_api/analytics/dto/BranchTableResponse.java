package com.procurepal_services.stock_bridge_api.analytics.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The owner's branch table (MULTI_BRANCH_UX_PLAN.md section 5.8): one row per branch the person sees,
 * then the company. Every alarm column has its census beside it (Pattern B): low and out against the
 * products there are, missing against what is on the way.
 *
 * @param asOf when the server answered - the phone shows it when it is showing a saved copy.
 */
public record BranchTableResponse(List<Row> rows, Totals totals, OffsetDateTime asOf) {

    /**
     * @param stockWorth what the stock there cost (products with a cost); {@code productsWithoutCost}
     *     says how many it leaves out.
     * @param onTheWayIn / onTheWayOut open waybills to and from the branch.
     * @param missing missing or damaged on waybills to the branch that nobody has checked yet.
     * @param salesToday sales recorded there today (Lagos), as the Sales report counts them.
     * @param lastCountAt the branch's latest stock count; absent if it has never been counted.
     * @param phonesSeen phones that reported from this branch in the last 7 days; {@code waiting} and
     *     {@code needsYou} what they still hold; {@code phonesLastSeenAt} the most recent report.
     */
    public record Row(
            UUID branchId,
            String name,
            boolean main,
            BigDecimal stockWorth,
            long productsWithoutCost,
            long productsInStock,
            long activeProducts,
            long low,
            long out,
            long onTheWayIn,
            long onTheWayOut,
            long missing,
            BigDecimal salesToday,
            OffsetDateTime lastCountAt,
            long phonesSeen,
            long waiting,
            long needsYou,
            OffsetDateTime oldestWaitingAt,
            OffsetDateTime phonesLastSeenAt) {
    }

    public record Totals(
            BigDecimal stockWorth, long low, long out, long onTheWay, long missing, BigDecimal salesToday, long waiting,
            long needsYou) {
    }
}
