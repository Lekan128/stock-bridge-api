package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import java.time.OffsetDateTime;

/**
 * The summary endpoint's payload: the same {@link VendorSalesPeriodMetrics} twice,
 * plus the four boundaries they were computed over.
 *
 * The comparison window is the immediately preceding window of identical length -
 * {@code [from - (to - from), from)} - so "this month vs last month" and "last 7
 * days vs the 7 before" both fall out of one rule. The bounds are echoed back
 * because a delta whose baseline the reader cannot see is a number nobody can
 * check.
 *
 * Deltas themselves are deliberately NOT computed here, matching the marketplace
 * summary: percentage change is undefined when the previous value is zero, which
 * for a new vendor is most of them, and choosing between "new", "+∞" and a dash is
 * a presentation decision.
 */
public record VendorSalesSummaryResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime previousFrom,
        OffsetDateTime previousTo,
        VendorSalesPeriodMetrics current,
        VendorSalesPeriodMetrics previous) {
}
