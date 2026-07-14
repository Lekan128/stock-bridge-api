package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.AnalyticsService;
import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateClientStatusRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-management endpoints for super admins. No @PreAuthorize on top of
 * SecurityConfig's AUD_SUPERADMIN requirement for /api/superadmin/** - super
 * admin is a single flat role, so audience is the only check needed (see
 * SuperAdminAuthController for the same pattern).
 *
 * The /{id}/analytics/* endpoints delegate straight into AnalyticsService's
 * ...ForClient methods - the exact same aggregation queries AnalyticsController
 * uses for a tenant's own view, just with id (a path variable) in place of
 * TenantContext's value.
 */
@RestController
@RequestMapping("/api/superadmin/clients")
@RequiredArgsConstructor
public class SuperAdminClientController {

    private final SuperAdminClientService superAdminClientService;
    private final AnalyticsService analyticsService;

    @GetMapping
    public Page<SuperAdminClientSummary> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20) Pageable pageable) {
        return superAdminClientService.list(search, active, pageable);
    }

    @GetMapping("/{id}")
    public SuperAdminClientDetail get(@PathVariable UUID id) {
        return superAdminClientService.get(id);
    }

    @PutMapping("/{id}/status")
    public SuperAdminClientDetail updateStatus(
            @PathVariable UUID id, @Valid @RequestBody UpdateClientStatusRequest request) {
        return superAdminClientService.updateStatus(id, request.active());
    }

    @GetMapping("/{id}/analytics/summary")
    public AnalyticsSummaryResponse analyticsSummary(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return analyticsService.summaryForClient(id, from, to);
    }

    @GetMapping("/{id}/analytics/movements-over-time")
    public List<MovementsOverTimePoint> analyticsMovementsOverTime(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "day") String granularity) {
        return analyticsService.movementsOverTimeForClient(id, from, to, granularity);
    }

    @GetMapping("/{id}/analytics/top-products")
    public List<TopProductEntry> analyticsTopProducts(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "quantity") String by,
            @RequestParam(defaultValue = "in") String direction,
            @RequestParam(defaultValue = "10") int limit) {
        return analyticsService.topProductsForClient(id, from, to, by, direction, limit);
    }
}
