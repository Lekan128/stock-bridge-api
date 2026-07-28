package com.procurepal_services.stock_bridge_api.cart.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Sets an absolute quantity (not a delta) - the UI's stepper always knows the number it wants. */
public record UpdateCartItemRequest(@NotNull @Positive Integer quantity) {
}
