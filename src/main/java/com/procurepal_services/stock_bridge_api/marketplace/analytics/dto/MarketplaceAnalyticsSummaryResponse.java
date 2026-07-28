package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.time.OffsetDateTime;

/**
 * The summary endpoint's payload: the same {@link MarketplacePeriodMetrics} twice, plus
 * the four boundaries they were computed over.
 *
 * The comparison window is the immediately preceding window of identical length -
 * {@code [from - (to - from), from)} - so "this month vs last month" and "last 7 days vs
 * the 7 before" both fall out of one rule and the UI never has to ask for a second range.
 * The bounds are echoed back because a delta whose baseline the reader cannot see is a
 * number nobody can check.
 *
 * Deltas themselves are deliberately NOT computed here: percentage change is undefined
 * when the previous value is zero, which for a young marketplace is most of them, and
 * that is a presentation decision ("new" vs "+∞" vs a dash) rather than an arithmetic one.
 */
public record MarketplaceAnalyticsSummaryResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime previousFrom,
        OffsetDateTime previousTo,
        MarketplacePeriodMetrics current,
        MarketplacePeriodMetrics previous) {
}
