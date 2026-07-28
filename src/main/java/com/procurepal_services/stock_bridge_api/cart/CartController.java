package com.procurepal_services.stock_bridge_api.cart;

import com.procurepal_services.stock_bridge_api.cart.dto.AddCartItemRequest;
import com.procurepal_services.stock_bridge_api.cart.dto.CartResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.MergeCartRequest;
import com.procurepal_services.stock_bridge_api.cart.dto.UpdateCartItemRequest;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's shared cart. Guarded by BROWSE_MARKETPLACE rather than
 * PLACE_ORDERS: everyone who can see the catalog can build the requisition, and
 * only checkout (PLACE_ORDERS) commits the company's money. That split is the whole
 * point of a shared cart in B2B procurement.
 *
 * Every mutation returns the full cart so the frontend's CartContext can replace its
 * state wholesale instead of patching - with two colleagues editing the same cart,
 * a locally-patched copy is stale the moment it is written.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('BROWSE_MARKETPLACE')")
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart() {
        return cartService.getCart();
    }

    @PostMapping("/items")
    public CartResponse addItem(
            @Valid @RequestBody AddCartItemRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cartService.addItem(request, principal.getUserId());
    }

    @PutMapping("/items/{productId}")
    public CartResponse updateItem(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateCartItemRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cartService.updateItemQuantity(productId, request.quantity(), principal.getUserId());
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@PathVariable UUID productId) {
        return cartService.removeItem(productId);
    }

    @DeleteMapping
    public CartResponse clear() {
        return cartService.clear();
    }

    @PostMapping("/merge")
    public CartResponse merge(
            @Valid @RequestBody MergeCartRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return cartService.merge(request, principal.getUserId());
    }
}
