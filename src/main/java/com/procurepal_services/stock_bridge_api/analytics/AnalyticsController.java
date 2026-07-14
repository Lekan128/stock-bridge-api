package com.procurepal_services.stock_bridge_api.analytics;

import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.LowStockSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped reporting over stock_movements/products - see AnalyticsService for isolation and aggregation. */
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('VIEW_ANALYTICS')")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/summary")
    public AnalyticsSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return analyticsService.summary(from, to);
    }

    @GetMapping("/movements-over-time")
    public List<MovementsOverTimePoint> movementsOverTime(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "day") String granularity) {
        return analyticsService.movementsOverTime(from, to, granularity);
    }

    @GetMapping("/top-products")
    public List<TopProductEntry> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "quantity") String by,
            @RequestParam(defaultValue = "in") String direction,
            @RequestParam(defaultValue = "10") int limit) {
        return analyticsService.topProducts(from, to, by, direction, limit);
    }

    @GetMapping("/low-stock-summary")
    public LowStockSummaryResponse lowStockSummary() {
        return analyticsService.lowStockSummary();
    }
}
