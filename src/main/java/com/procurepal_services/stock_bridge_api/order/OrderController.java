package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.order.dto.CancelOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.OrderItemMatchSuggestionResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.OrderSummaryResponse;
import com.procurepal_services.stock_bridge_api.order.dto.PlaceOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReceiveOrderRequest;
import com.procurepal_services.stock_bridge_api.order.dto.ReorderResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * The buyer's purchases. Three different permissions guard three genuinely different
 * acts (contract §4.11): PLACE_ORDERS spends the company's money, VIEW_ORDERS sees
 * what was spent, RECEIVE_DELIVERIES signs for goods and writes them into inventory.
 * A storekeeper holds only the last of those, and that is the point of the split.
 *
 * Reorder is guarded by PLACE_ORDERS rather than VIEW_ORDERS: it only fills a cart,
 * but "rebuild last month's ₦900k order" is a procurement act, not a browsing one.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @PreAuthorize("hasAuthority('PLACE_ORDERS')")
    public OrderResponse place(
            @Valid @RequestBody PlaceOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return orderService.place(request, principal.getUserId());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('VIEW_ORDERS')")
    public Page<OrderSummaryResponse> list(
            @RequestParam(required = false) OrderStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return orderService.list(status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('VIEW_ORDERS')")
    public OrderResponse get(@PathVariable UUID id) {
        return orderService.get(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('PLACE_ORDERS')")
    public OrderResponse cancel(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CancelOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return orderService.cancel(id, request, principal.getUserId());
    }

    @PostMapping("/{id}/receive")
    @PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
    public OrderResponse receive(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) ReceiveOrderRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return orderService.receive(id, request, principal.getUserId());
    }

    /**
     * The section 7.2 duplicate nudge: candidates for "this looks like it might already be in
     * your inventory as '[name]' — same item?", per outstanding line, ahead of confirming
     * receipt. Same permission as receiving itself - this is read-only prep for that action, not
     * a separate capability.
     */
    @GetMapping("/{id}/receive-suggestions")
    @PreAuthorize("hasAuthority('RECEIVE_DELIVERIES')")
    public List<OrderItemMatchSuggestionResponse> receiveSuggestions(@PathVariable UUID id) {
        return orderService.receiveSuggestions(id);
    }

    @PostMapping("/{id}/reorder")
    @PreAuthorize("hasAuthority('PLACE_ORDERS')")
    public ReorderResponse reorder(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return orderService.reorder(id, principal.getUserId());
    }
}
