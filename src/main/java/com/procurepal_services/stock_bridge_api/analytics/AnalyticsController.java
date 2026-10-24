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
    private final BranchTableService branchTableService;
    private final BranchDigestService branchDigestService;
    private final com.procurepal_services.stock_bridge_api.branch.BranchAccessService branchAccessService;

    @GetMapping("/summary")
    public AnalyticsSummaryResponse summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            // V51: one branch, or (absent) every branch this person sees.
            @RequestParam(required = false) java.util.UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return analyticsService.summaryForClient(principal.getClientId(), from, to,
                branchAccessService.reportScope(principal.getUserId(), branchId));
    }

    /**
     * The owner's branch table (Phase 3, MULTI_BRANCH_UX_PLAN.md section 5.8): one row per branch the
     * person sees. A one-branch company never asks: the screen only exists in "All branches".
     */
    @GetMapping("/branches")
    public com.procurepal_services.stock_bridge_api.analytics.dto.BranchTableResponse branches(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return branchTableService.table(principal.getClientId(), principal.getUserId());
    }

    /** The daily digest switch for this person (UX section 5.13), shown under the branch table. */
    @org.springframework.web.bind.annotation.PutMapping("/branches/digest")
    public java.util.Map<String, Boolean> setDigest(
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, Boolean> body,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return java.util.Map.of("enabled", branchDigestService.set(principal.getUserId(), Boolean.TRUE.equals(body.get("enabled"))));
    }

    @GetMapping("/branches/digest")
    public java.util.Map<String, Boolean> digest(
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return java.util.Map.of("enabled", branchDigestService.isOn(principal.getUserId()));
    }

    @GetMapping("/movements-over-time")
    public List<MovementsOverTimePoint> movementsOverTime(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) java.util.UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return analyticsService.movementsOverTimeForClient(principal.getClientId(), from, to, granularity,
                branchAccessService.reportScope(principal.getUserId(), branchId));
    }

    @GetMapping("/top-products")
    public List<TopProductEntry> topProducts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "quantity") String by,
            @RequestParam(defaultValue = "in") String direction,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) java.util.UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return analyticsService.topProductsForClient(principal.getClientId(), from, to, by, direction, limit,
                branchAccessService.reportScope(principal.getUserId(), branchId));
    }

    @GetMapping("/low-stock-summary")
    public LowStockSummaryResponse lowStockSummary() {
        return analyticsService.lowStockSummary();
    }
}
