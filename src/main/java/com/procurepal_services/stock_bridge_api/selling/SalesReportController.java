package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.StockWorthResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Sales report and the stock-worth figure (SELLING_PRICE_PLAN.md sections 4.7, 6.5, 6.11). Under
 * VIEW_ANALYTICS like the rest of /api/analytics: profit and cost are an owner's figures.
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('VIEW_ANALYTICS')")
public class SalesReportController {

    private final SalesReportService salesReportService;
    private final SalesExportService salesExportService;
    /** One branch, or (absent) every branch the person sees (V51) - as the other reports. */
    private final com.procurepal_services.stock_bridge_api.branch.BranchAccessService branchAccessService;

    /**
     * @param groupBy {@code sale} (default - today's sales as a list), {@code product}, {@code day},
     *     {@code user}, {@code category} or {@code branch}.
     * @param branchId one branch; absent is every branch the person sees.
     * @param from start of the range; with {@code to}, both absent means today in Lagos.
     */
    @GetMapping("/api/analytics/sales")
    public SalesReportResponse sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "false") boolean belowUsualPrice,
            @RequestParam(defaultValue = "false") boolean belowCost,
            @RequestParam(defaultValue = "false") boolean cancelled,
            @RequestParam(defaultValue = "false") boolean unpriced,
            @RequestParam(defaultValue = "false") boolean noCost,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return salesReportService.report(
                requireTenantId(), from, to, groupBy,
                new SalesReportService.Filters(productId, userId, belowUsualPrice, belowCost, cancelled, unpriced, noCost)
                        .atBranches(branchAccessService.reportScope(principal.getUserId(), branchId)),
                limit);
    }

    /** The same report as a spreadsheet - same rows, same footer (plan section 6.11). */
    @GetMapping("/api/analytics/sales/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "false") boolean belowUsualPrice,
            @RequestParam(defaultValue = "false") boolean belowCost,
            @RequestParam(defaultValue = "false") boolean cancelled,
            @RequestParam(defaultValue = "false") boolean unpriced,
            @RequestParam(defaultValue = "false") boolean noCost,
            @RequestParam(required = false) UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        byte[] bytes = salesExportService.export(salesReportService.report(
                requireTenantId(), from, to, groupBy,
                new SalesReportService.Filters(productId, userId, belowUsualPrice, belowCost, cancelled, unpriced, noCost)
                        .atBranches(branchAccessService.reportScope(principal.getUserId(), branchId)),
                SalesReportService.MAX_ROWS));
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sales.xlsx\"")
                .body(bytes);
    }

    /** At one branch with {@code branchId} (V51); absent, the company's. */
    @GetMapping("/api/analytics/stock-worth")
    public StockWorthResponse stockWorth(
            @RequestParam(required = false) UUID branchId,
            @org.springframework.security.core.annotation.AuthenticationPrincipal
                    com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal principal) {
        return salesReportService.stockWorth(requireTenantId(),
                branchId == null ? null : branchAccessService.reportScope(principal.getUserId(), branchId));
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
