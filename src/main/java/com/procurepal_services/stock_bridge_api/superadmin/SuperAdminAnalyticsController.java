package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.AnalyticsGranularity;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformAggregateResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenuePoint;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenueSummaryResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SellerRevenueBreakdownResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-wide (cross-tenant) reporting for super admins. Two unrelated families of
 * numbers live here and should not be confused:
 *
 * <ul>
 *   <li>{@code /aggregate} - STOCK MOVEMENT value per tenant, folded from every client's
 *       own inventory analytics. See {@link SuperAdminAggregateService}.</li>
 *   <li>{@code /revenue/**} - SALES across every seller on the marketplace: the total, its
 *       growth over time, and the per-seller breakdown behind it. See
 *       {@link PlatformRevenueService}. Added in M6.</li>
 * </ul>
 *
 * <h2>Why the revenue routes are here and not on a tenant surface</h2>
 * M6 narrowed {@code /api/marketplace/admin/analytics/**} to ProcurePal's OWN sales,
 * because the operator was reading a revenue figure that included third-party vendors'
 * money. "Every seller's revenue" is a platform-operations question, so it moved to the
 * platform operator's principal rather than being handed to a tenant - however privileged
 * that tenant is. ProcurePal's own tenant token does not reach these routes.
 *
 * <h2>How these are secured, which is not with {@code @PreAuthorize}</h2>
 * Nothing on this controller carries a permission annotation, deliberately and in line with
 * every other super admin controller. Super admins are a separate principal type
 * ({@code SuperAdminPrincipal}, issued by {@code SuperAdminAuthService} and never a
 * {@code TenantPrincipal}), they hold no permission codes at all, and the entire
 * {@code /api/superadmin/**} prefix is gated in one place - {@code SecurityConfig} requires
 * {@code JwtAuthenticationFilter.AUDIENCE_SUPERADMIN_AUTHORITY} on it, checked ahead of the
 * general {@code /api/**} tenant rule. A tenant token, ProcurePal's included, carries the
 * tenant audience and is refused by that rule before any handler runs. Adding a
 * {@code @PreAuthorize} here would be worse than redundant: it would suggest the gate is
 * per-route and invite somebody to add a route without one.
 *
 * <h2>Parameter conventions</h2>
 * {@code from}/{@code to} are ISO offset date-times, both optional, defaulting to
 * month-to-date - the same defaults every analytics surface in the application uses, so the
 * operator's screen, a vendor's own screen and this one open on the same period.
 * The window is half-open, {@code [from, to)}. {@code granularity}, {@code status},
 * {@code paymentStatus} and {@code sort} are enums, so an unknown value fails to bind and
 * becomes a 400 via {@link SuperAdminExceptionHandler} rather than reaching a query.
 */
@RestController
@RequestMapping("/api/superadmin/analytics")
@RequiredArgsConstructor
public class SuperAdminAnalyticsController {

    private final SuperAdminAggregateService superAdminAggregateService;
    private final PlatformRevenueService platformRevenueService;

    @GetMapping("/aggregate")
    public PlatformAggregateResponse aggregate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return superAdminAggregateService.aggregate(from, to);
    }

    /**
     * Total marketplace revenue across every seller for the window, next to the window
     * immediately before it so growth is readable without a second request.
     *
     * <p>{@code sellerId} narrows to one seller, which is the only place in the application
     * a seller id may legitimately arrive in a request - see {@code PlatformRevenueService.Filters}.
     */
    @GetMapping("/revenue/summary")
    public PlatformRevenueSummaryResponse revenueSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus) {
        return platformRevenueService.summary(from, to, sellerId, status, paymentStatus);
    }

    /** The growth curve: marketplace revenue bucketed by day, week or month, zero-filled. */
    @GetMapping("/revenue/over-time")
    public List<PlatformRevenuePoint> revenueOverTime(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "DAY") AnalyticsGranularity granularity,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus) {
        return platformRevenueService.revenueOverTime(from, to, granularity, sellerId, status, paymentStatus);
    }

    /**
     * Every seller that traded in either window, with revenue, orders, AOV, units and
     * period-over-period growth.
     *
     * <p>{@code ascending} is intentionally not defaulted: omitted means "the natural
     * direction for this sort key" - biggest-first for money and counts, A-to-Z for name.
     * See {@code PlatformRevenueService.sorted}.
     */
    @GetMapping("/revenue/by-seller")
    public SellerRevenueBreakdownResponse revenueBySeller(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(defaultValue = "REVENUE") SellerRevenueSort sort,
            @RequestParam(required = false) Boolean ascending) {
        return platformRevenueService.bySeller(from, to, sellerId, status, paymentStatus, sort, ascending);
    }
}
