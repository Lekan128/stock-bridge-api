package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.time.OffsetDateTime;

/**
 * GET /api/superadmin/analytics/revenue/summary - total marketplace revenue for a window,
 * next to the window immediately before it.
 *
 * <h2>Why the comparison window is returned rather than just a delta</h2>
 * Same reason {@code MarketplaceAnalyticsSummaryResponse} does it: the client diffs two
 * identical records field by field, so adding a metric does not mean adding a matching
 * "delta" field to the contract, and a reader can always see WHICH period a growth figure
 * is against. The rule is {@code [from - span, from)} - same length, ending exactly where
 * this one starts - which makes "this month vs last month" and "last 7 days vs the 7
 * before" the same rule rather than two. That rule is copied from the per-seller analytics
 * services rather than reinvented, so a growth number quoted from this screen means the
 * same thing as one quoted from a seller's own.
 *
 * <p>The four timestamps are echoed back because both windows are resolved server-side
 * (month-to-date when a bound is omitted), so a client that sent nothing still knows
 * exactly what it is looking at.
 */
public record PlatformRevenueSummaryResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime previousFrom,
        OffsetDateTime previousTo,
        PlatformRevenuePeriodMetrics current,
        PlatformRevenuePeriodMetrics previous) {
}
