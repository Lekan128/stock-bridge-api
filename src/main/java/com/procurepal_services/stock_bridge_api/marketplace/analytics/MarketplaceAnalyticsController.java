package com.procurepal_services.stock_bridge_api.marketplace.analytics;

import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.CategoryMixResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.FulfilmentFunnelResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.MarketplaceAnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.RevenuePoint;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.TopCustomerEntry;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.TopSellingProductEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProcurePal's own sales reporting - the platform owner reading its numbers as a SELLER,
 * not as the marketplace.
 *
 * <h2>What every route on this controller reports since M6</h2>
 * Orders whose {@code seller_client_id} is ProcurePal's, and only those. Third-party
 * vendors' sales are excluded from every metric here: revenue, orders, AOV, units, top
 * products, top customers, category mix and the fulfilment funnel alike. Before M6 these
 * routes aggregated every seller, which meant the operator's revenue card included money
 * it does not receive. The per-metric ruling - including the two that could plausibly have
 * stayed marketplace-wide, and the one nearby surface that deliberately did - is written
 * out in {@link MarketplaceAnalyticsService}'s class doc.
 *
 * <p><b>Cross-seller revenue lives elsewhere.</b> Total marketplace revenue, the per-seller
 * breakdown and its growth are a super admin surface: {@code /api/superadmin/analytics/revenue/**},
 * behind the super admin principal. No tenant token reaches it, including ProcurePal's.
 *
 * <h2>Three gates on every route</h2>
 * The same arrangement the fulfilment queue uses. {@code @PreAuthorize} proves the caller
 * has the right job; {@code PlatformOwnerGuard.requirePlatformOwner()} - called at the top
 * of every service method - proves they work for the right company; and the
 * {@code seller_client_id} predicate decides which rows are theirs. The permission alone
 * proves nothing, because VIEW_MARKETPLACE_ANALYTICS hangs off a global role and every
 * tenant's OWNER therefore holds it. The 403 needs no advice here:
 * {@code MarketplaceAccessExceptionHandler} is global.
 *
 * <h2>Parameter conventions</h2>
 * {@code from}/{@code to} are ISO offset date-times, both optional, defaulting to
 * month-to-date - matching {@code analytics.AnalyticsController}, so the two analytics
 * screens open on the same period. The window is half-open, {@code [from, to)}.
 * {@code granularity} and {@code metric} are enums, so an unknown value fails to bind and
 * is turned into a 400 by this feature's advice rather than reaching a query.
 *
 * <h2>Why these are separate endpoints rather than one bundle</h2>
 * The page's controls move independently - the granularity toggle should not re-fetch the
 * customer ranking, and the metric toggle should not re-fetch the summary. One response
 * would make every control a full-page reload.
 */
@RestController
@RequestMapping("/api/marketplace/admin/analytics")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('VIEW_MARKETPLACE_ANALYTICS')")
public class MarketplaceAnalyticsController {

    private final MarketplaceAnalyticsService marketplaceAnalyticsService;

    /** ProcurePal's headline figures for the window plus the preceding window of the same length. */
    @GetMapping("/summary")
    public MarketplaceAnalyticsSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return marketplaceAnalyticsService.summary(from, to);
    }

    @GetMapping("/revenue-over-time")
    public List<RevenuePoint> revenueOverTime(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "DAY") AnalyticsGranularity granularity) {
        return marketplaceAnalyticsService.revenueOverTime(from, to, granularity);
    }

    /**
     * ProcurePal's best customers, ranked by what they spent WITH PROCUREPAL. Not to be
     * confused with {@code /api/marketplace/admin/customers}, which is a roster of every
     * buying company on the platform - prospects included - and deliberately stays that
     * wide. This one is a revenue ranking, so it narrows; that one is an ops list, so it
     * does not. See MarketplaceOrderAdminService.customers for the older half of that
     * decision.
     */
    @GetMapping("/top-customers")
    public List<TopCustomerEntry> topCustomers(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "REVENUE") CustomerRankMetric metric) {
        return marketplaceAnalyticsService.topCustomers(from, to, limit, metric);
    }

    @GetMapping("/top-products")
    public List<TopSellingProductEntry> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "REVENUE") ProductRankMetric metric) {
        return marketplaceAnalyticsService.topProducts(from, to, limit, metric);
    }

    /**
     * Share of ProcurePal's own goods revenue by category - a revenue split, not
     * marketplace-wide category demand. See CategoryMixResponse for why those are
     * different questions and why only the first one belongs on this page.
     */
    @GetMapping("/category-mix")
    public CategoryMixResponse categoryMix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return marketplaceAnalyticsService.categoryMix(from, to);
    }

    /** ProcurePal's own fulfilment queue, as a funnel. Matches the queue's own scope exactly. */
    @GetMapping("/fulfilment-funnel")
    public FulfilmentFunnelResponse fulfilmentFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return marketplaceAnalyticsService.fulfilmentFunnel(from, to);
    }
}
