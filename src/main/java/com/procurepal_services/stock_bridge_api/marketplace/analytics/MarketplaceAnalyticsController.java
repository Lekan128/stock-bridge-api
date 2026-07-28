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
 * The marketplace operator's reporting surface. Two independent gates on every route, the
 * same arrangement the fulfilment queue uses: {@code @PreAuthorize} proves the caller has
 * the right job, and {@code PlatformOwnerGuard.requirePlatformOwner()} - called at the top
 * of every service method - proves they work for the right company. The permission alone
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

    /** Headline figures for the window plus the preceding window of the same length. */
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

    @GetMapping("/category-mix")
    public CategoryMixResponse categoryMix(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return marketplaceAnalyticsService.categoryMix(from, to);
    }

    @GetMapping("/fulfilment-funnel")
    public FulfilmentFunnelResponse fulfilmentFunnel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return marketplaceAnalyticsService.fulfilmentFunnel(from, to);
    }
}
