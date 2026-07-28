package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.AnalyticsService;
import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateClientRequest;
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

    /**
     * Edits a tenant's clients row: name, admin contact email, phone, payment
     * terms and - optionally, and loudly documented on UpdateClientRequest - the
     * login slug.
     *
     * <h2>Why this is general rather than /platform-owner-only</h2>
     * The user asked for ProcurePal's own row to be editable, and ProcurePal is a
     * client, so a platform-owner-only endpoint would have answered the question.
     * It is general anyway for two reasons. First, consistency: this controller is
     * already a per-client surface addressed by id (list, detail, status, three
     * analytics endpoints all take {id} and all accept ProcurePal's id like any
     * other), so a second, differently-shaped path for one row would be the odd
     * one out and would leave "how do I fix a customer's misspelled company name?"
     * with no answer at all. Second, blast radius: everything writable here is
     * account metadata a support agent would edit from a ticket - a name, a contact
     * address, a phone number, and the credit decision that is explicitly ops's to
     * make (Client.paymentTerms). None of it grants access to anything. Compare
     * SuperAdminPlatformOwnerUserController, where the writes ARE credential-
     * adjacent and are therefore narrowed to one tenant; that is the same judgement
     * applied to a different risk, not an inconsistency.
     */
    @PutMapping("/{id}")
    public SuperAdminClientDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateClientRequest request) {
        return superAdminClientService.update(id, request);
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
