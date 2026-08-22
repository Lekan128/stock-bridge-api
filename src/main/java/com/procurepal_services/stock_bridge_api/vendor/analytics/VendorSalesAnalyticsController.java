package com.procurepal_services.stock_bridge_api.vendor.analytics;

import com.procurepal_services.stock_bridge_api.marketplace.analytics.AnalyticsGranularity;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.ProductRankMetric;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorOrderStatusCount;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorRevenuePoint;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorSalesSummaryResponse;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorStockOutEntry;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorTopProductEntry;
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
 * A SELLER's own sales figures - a vendor's, or ProcurePal's. The surface that
 * finally answers V11's VIEW_OWN_SALES_ANALYTICS.
 *
 * <h2>hasAnyAuthority, not hasAuthority, and why that is not a loosening</h2>
 * VIEW_OWN_SALES_ANALYTICS was minted for the VENDOR role so a vendor would never
 * need VIEW_MARKETPLACE_ANALYTICS, which at the time meant the whole marketplace
 * (M6 narrowed those routes to ProcurePal's own sales, but the code kept its name -
 * see {@code VendorGuard} for that drift). The
 * consequence, flagged in {@code VendorGuard}'s Javadoc: ProcurePal's staff do NOT
 * hold the new code - and ProcurePal is a seller with own-sales numbers like
 * anybody else. Requiring only the new code would lock the platform owner out of
 * its own sales figures; requiring only the old one would put us back where V11
 * started. So this accepts either, and leans on the second gate for the work.
 *
 * <p>That second gate is {@code VendorGuard.requireSeller()}, called at the top of
 * every service method, plus a {@code seller_client_id} predicate on every
 * statement. Accepting VIEW_MARKETPLACE_ANALYTICS here is therefore not a way in
 * for a buying company's OWNER, who also holds it: they are refused by the guard,
 * with a 403 that says their account does not sell. Three gates, and the
 * permission is the weakest of them.
 *
 * <h2>What this surface will not answer</h2>
 * There is no top-customers route, no repeat-buyer rate and no cross-seller
 * comparison, here or in the service. VENDOR_RESEARCH.md Section C item 7 asked
 * for that line to be drawn explicitly rather than left to a reviewer, and this is
 * where it is drawn: own revenue, own orders, own products, own stock. See
 * {@code VendorSalesPeriodMetrics}.
 *
 * <h2>Parameter conventions</h2>
 * {@code from}/{@code to} are ISO offset date-times, both optional, defaulting to
 * month-to-date - matching both the marketplace analytics screen and the tenant
 * dashboard, so all three open on the same period. The window is half-open,
 * {@code [from, to)}. {@code granularity} and {@code metric} are the same enums the
 * marketplace module uses, so an unknown value fails to bind and becomes a 400
 * rather than reaching a query.
 *
 * <h2>Why separate endpoints rather than one bundle</h2>
 * The page's controls move independently - the granularity toggle should not
 * re-fetch the stock-out list, and the metric toggle should not re-fetch the
 * summary. One response would make every control a full-page reload.
 */
@RestController
@RequestMapping("/api/vendor/analytics")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')")
public class VendorSalesAnalyticsController {

    private final VendorSalesAnalyticsService vendorSalesAnalyticsService;

    /** Headline figures for the window plus the preceding window of the same length. */
    @GetMapping("/summary")
    public VendorSalesSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return vendorSalesAnalyticsService.summary(from, to);
    }

    @GetMapping("/revenue-over-time")
    public List<VendorRevenuePoint> revenueOverTime(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "DAY") AnalyticsGranularity granularity) {
        return vendorSalesAnalyticsService.revenueOverTime(from, to, granularity);
    }

    @GetMapping("/top-products")
    public List<VendorTopProductEntry> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
            @RequestParam(defaultValue = "REVENUE") ProductRankMetric metric) {
        return vendorSalesAnalyticsService.topProducts(from, to, limit, metric);
    }

    @GetMapping("/order-status-breakdown")
    public List<VendorOrderStatusCount> orderStatusBreakdown(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return vendorSalesAnalyticsService.orderStatusBreakdown(from, to);
    }

    /**
     * Takes no date range, unlike its neighbours: a stock-out is a fact about now.
     * See the service for why the ordering puts listed products first.
     */
    @GetMapping("/stock-outs")
    public List<VendorStockOutEntry> stockOuts(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return vendorSalesAnalyticsService.stockOuts(limit);
    }
}
