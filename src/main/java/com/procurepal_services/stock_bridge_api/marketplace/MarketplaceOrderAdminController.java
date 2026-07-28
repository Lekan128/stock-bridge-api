package com.procurepal_services.stock_bridge_api.marketplace;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.marketplace.dto.AdvanceOrderStatusRequest;
import com.procurepal_services.stock_bridge_api.marketplace.dto.MarketplaceCustomerResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * ProcurePal's fulfilment queue. Two independent gates on every route (contract §6):
 * {@code @PreAuthorize} proves the caller has the right job, and
 * {@code PlatformOwnerGuard} - invoked inside the service, and again inside
 * {@code readAcrossTenants} - proves they work for the right company. The permission
 * alone is not enough, because MANAGE_MARKETPLACE_ORDERS is held by every tenant's
 * OWNER: permissions hang off global roles and cannot be granted to one tenant only.
 *
 * The 403 needs no advice here - MarketplaceAccessExceptionHandler is global.
 */
@RestController
@RequestMapping("/api/marketplace/admin")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_MARKETPLACE_ORDERS')")
public class MarketplaceOrderAdminController {

    private final MarketplaceOrderAdminService marketplaceOrderAdminService;

    @GetMapping("/orders")
    public Page<OrderSummaryResponse> queue(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 20) Pageable pageable) {
        return marketplaceOrderAdminService.queue(status, paymentStatus, clientId, q, from, to, pageable);
    }

    @GetMapping("/orders/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        return marketplaceOrderAdminService.get(id);
    }

    @PostMapping("/orders/{id}/status")
    public OrderResponse advanceStatus(
            @PathVariable UUID id,
            @Valid @RequestBody AdvanceOrderStatusRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return marketplaceOrderAdminService.advanceStatus(id, request, principal.getUserId());
    }

    @PostMapping("/orders/{id}/payment-received")
    public OrderResponse recordPaymentReceived(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return marketplaceOrderAdminService.recordPaymentReceived(id, principal.getUserId());
    }

    @GetMapping("/customers")
    public Page<MarketplaceCustomerResponse> customers(
            @RequestParam(required = false) String q, @PageableDefault(size = 20) Pageable pageable) {
        return marketplaceOrderAdminService.customers(q, pageable);
    }
}
