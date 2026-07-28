package com.procurepal_services.stock_bridge_api.cart.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The company's shared cart. Every mutating endpoint returns this whole object
 * rather than a delta, because the frontend's CartContext applies one authoritative
 * server state instead of patching its local copy - two users editing the same
 * company cart makes any client-side reconciliation wrong within seconds.
 *
 * Field names mirror {@code stock-bridge-ui/src/features/cart/types.ts}.
 */
public record CartResponse(
        UUID id,
        List<CartItemResponse> items,
        int itemCount,
        int distinctItemCount,
        BigDecimal subtotal,
        OffsetDateTime updatedAt) {

    public static CartResponse of(UUID cartId, List<CartItemResponse> items, OffsetDateTime updatedAt) {
        return new CartResponse(
                cartId,
                items,
                items.stream().mapToInt(CartItemResponse::quantity).sum(),
                items.size(),
                items.stream().map(CartItemResponse::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                updatedAt);
    }
}
