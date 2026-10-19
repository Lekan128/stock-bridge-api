package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import com.procurepal_services.stock_bridge_api.selling.dto.AddSalePriceRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.CancelSaleRequest;
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

    /**
     * The person's own sales over a range - quick mode's "Your sales today: 14 · ₦86,500", the figure
     * a cashier checks against their own drawer. Their own lines only, so it needs no analytics
     * permission; no cost or profit in it is shown at the counter (the UI's rule, plan principle 11).
     */
    @GetMapping("/mine/summary")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public SalesTotals mine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return salesReportService.totals(
                principal.getClientId(), from, to,
                new SalesReportService.Filters(null, principal.getUserId(), false, false, false));
    }

    @GetMapping("/{saleId}")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'MANAGE_INVENTORY', 'VIEW_ANALYTICS')")
    public SaleResponse get(@PathVariable UUID saleId) {
        return saleService.get(saleId);
    }

    /** Undo, within two minutes, line by line - see {@link UndoSaleResponse}. */
    @PostMapping("/{saleId}/undo")
    @PreAuthorize("hasAuthority('STOCK_OUT')")
    public UndoSaleResponse undo(@PathVariable UUID saleId, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleService.undo(saleId, principal.getUserId());
    }

    /** Cancel the whole sale (no body, or no line ids) or some of its lines. Online only by nature. */
    @PostMapping("/{saleId}/cancel")
    @PreAuthorize("hasAnyAuthority('STOCK_OUT', 'MANAGE_INVENTORY')")
    public SaleResponse cancel(
            @PathVariable UUID saleId,
            @RequestBody(required = false) CancelSaleRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return saleService.cancel(saleId, request == null ? null : request.lineIds(), principal.getUserId());
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
