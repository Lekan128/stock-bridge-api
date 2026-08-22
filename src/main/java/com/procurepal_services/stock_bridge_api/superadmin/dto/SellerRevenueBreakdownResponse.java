package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/superadmin/analytics/revenue/by-seller - who took what, and who is growing.
 *
 * <p>The two totals are echoed alongside the rows so a client can render "₦X of ₦Y" and
 * check that the shares add to one without re-summing, and so the table's total is
 * guaranteed to be the same total the shares were taken against rather than a second
 * definition computed elsewhere.
 *
 * <p>They are the totals OF THIS RESPONSE, so they honour the request's filters. With a
 * {@code sellerId} filter applied the response has one row and {@code totalRevenue} is that
 * seller's - which is the honest reading of "the total shown on this screen", but is worth
 * knowing before quoting it as the marketplace's. The unfiltered marketplace total is
 * {@code /revenue/summary}.
 */
public record SellerRevenueBreakdownResponse(
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime previousFrom,
        OffsetDateTime previousTo,
        BigDecimal totalRevenue,
        BigDecimal previousTotalRevenue,
        List<SellerRevenueEntry> sellers) {
}
