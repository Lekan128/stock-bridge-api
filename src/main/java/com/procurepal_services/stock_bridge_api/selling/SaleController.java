package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.selling.dto.AddSalePriceRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.AskToCancelSaleRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.CancelSaleRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.ChangeSalePaymentRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import com.procurepal_services.stock_bridge_api.selling.dto.UndoSaleResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A sale and its fixes (SELLING_PRICE_PLAN.md sections 4.3, 4.3a). Recording a sale is not here: a
 * sale is recorded line by line through the ordinary stock-out, so every line keeps the outbox,
 * idempotency and Undo it already has (plan section 4.4).
 *
 * <p>The {@code @PreAuthorize} gates are the cheap first check; who may cancel WHICH sale is
 * {@link SaleService}'s, against permissions as they stand now.
 */
@RestController
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SaleController {

    private final SaleService saleService;
    private final SalesReportService salesReportService;
    private final SaleTilesService saleTilesService;
    private final SaleCancelService saleCancelService;

    /**
     * The picture tiles on Record a sale: the most-sold products first, then other priced ones. The same
     * products the person could find by searching, so the same permissions.
     */
    @GetMapping("/tiles")
    @PreAuthorize("hasAuthority('STOCK_OUT') and hasAuthority('VIEW_PRODUCTS')")
    public java.util.List<com.procurepal_services.stock_bridge_api.product.dto.ProductResponse> tiles(
            @RequestParam(defaultValue = "12") int limit,
            // V92: the till's branch - only products for it.
            @RequestParam(required = false) UUID branchId,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleTilesService.tiles(principal.getClientId(), limit, branchId);
    }

    /**
     * The person's own sales over a range - quick mode's "Your sales today: 14 · ₦86,500", the figure
     * a cashier checks against their own drawer. Their own lines only, so it needs no analytics
     * permission - and no cost or profit is in it, so none can reach the counter (plan principle 11).
     */
    @GetMapping("/mine/summary")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public SalesTotals mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return salesReportService.totals(
                principal.getClientId(), from, to,
                new SalesReportService.Filters(null, principal.getUserId(), false, false, false)).withoutCost();
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'MANAGE_INVENTORY', 'VIEW_ANALYTICS', 'CANCEL_SALES')")
    public SaleResponse get(@PathVariable UUID saleId) {
        return saleService.get(saleId);
    }

    /**
     * Undo, from a phone that still asks for it: since selling till Step 3 a sale is never erased, so this
     * cancels it - by the same rules, with no reason given - and answers in the old shape.
     */
    @PostMapping("/{saleId}/undo")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public UndoSaleResponse undo(@PathVariable UUID saleId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        SaleResponse sale = saleCancelService.cancel(saleId, SaleCancelReason.OTHER, null, principal.getUserId());
        java.util.List<UndoSaleResponse.Line> lines = sale.lines().stream()
                .map(line -> new UndoSaleResponse.Line(line.movementId(), line.productName(), line.cancelled(), null))
                .toList();
        int undone = (int) lines.stream().filter(UndoSaleResponse.Line::undone).count();
        return new UndoSaleResponse(saleId, lines, undone, lines.size() - undone);
    }

    /**
     * Cancel the whole sale (no line ids) with a reason, or some of its lines. Who may: anyone who can cancel
     * sales, or the person who recorded it in its first two minutes - see {@link SaleCancelService}.
     */
    @PostMapping("/{saleId}/cancel")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'CANCEL_SALES')")
    public SaleResponse cancel(
            @PathVariable UUID saleId,
            @Valid @RequestBody(required = false) CancelSaleRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        if (request != null && request.lineIds() != null && !request.lineIds().isEmpty()) {
            return saleService.cancel(saleId, request.lineIds(), principal.getUserId());
        }
        return saleCancelService.cancel(
                saleId, request == null ? null : request.reason(), request == null ? null : request.note(), principal.getUserId());
    }

    /** Past the first two minutes: the cashier asks; those who can cancel sales are told (selling till Step 3). */
    @PostMapping("/{saleId}/cancel-request")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public SaleResponse askToCancel(
            @PathVariable UUID saleId,
            @Valid @RequestBody AskToCancelSaleRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleCancelService.request(saleId, request.reason(), request.note(), principal.getUserId());
    }

    @PostMapping("/{saleId}/cancel-request/approve")
    @PreAuthorize("hasAuthority('CANCEL_SALES')")
    public SaleResponse approveCancel(@PathVariable UUID saleId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleCancelService.approve(saleId, principal.getUserId());
    }

    @PostMapping("/{saleId}/cancel-request/decline")
    @PreAuthorize("hasAuthority('CANCEL_SALES')")
    public SaleResponse declineCancel(@PathVariable UUID saleId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleCancelService.decline(saleId, principal.getUserId());
    }

    /**
     * The person's own sales today, newest first - "My sales today" on Record a sale, to reprint, share or cancel
     * one. Their own sales only, and no cost in it, so it needs no analytics permission.
     */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse mineToday(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return salesReportService.mine(principal.getClientId(), principal.getUserId());
    }

    /** "Change how they paid" (selling till Step 1) - see {@link SaleService#changePayment}. */
    @PutMapping("/{saleId}/payment")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'MANAGE_INVENTORY')")
    public SaleResponse changePayment(
            @PathVariable UUID saleId,
            @Valid @RequestBody ChangeSalePaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleService.changePayment(saleId, request.paymentMethod(), principal.getUserId());
    }

    /** Add the price to a sale line saved without one. */
    @PostMapping("/lines/{lineId}/price")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'MANAGE_INVENTORY')")
    public SaleResponse addPrice(
            @PathVariable UUID lineId,
            @Valid @RequestBody AddSalePriceRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleService.addPrice(lineId, request, principal.getUserId());
    }
}
