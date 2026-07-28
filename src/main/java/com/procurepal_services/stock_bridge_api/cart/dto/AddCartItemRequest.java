package com.procurepal_services.stock_bridge_api.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * quantity is validated only as "positive" here; the real floor is the product's
 * own min_order_quantity, which bean validation cannot see. CartService clamps to
 * it rather than rejecting - a buyer asking for 1 of something sold in 5s meant to
 * buy some, and failing the request would just make them guess.
 */
public record AddCartItemRequest(@NotNull UUID productId, @NotNull @Positive Integer quantity) {
}
