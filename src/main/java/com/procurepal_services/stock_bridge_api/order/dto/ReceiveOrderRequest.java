package com.procurepal_services.stock_bridge_api.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.UUID;

/**
 * Confirming a delivery, optionally line by line. Partial receipt is normal in
 * wholesale - 8 of 10 bags arrive today, 2 follow tomorrow - so each line carries its
 * own quantity and the remainder stays as incoming stock.
 *
 * An empty or absent {@code lines} means "everything that is still outstanding",
 * which is the overwhelmingly common case and should not require the UI to echo back
 * a list it did not change.
 */
public record ReceiveOrderRequest(@Valid List<ReceiveOrderLine> lines) {

    public record ReceiveOrderLine(@NotNull UUID orderItemId, @NotNull @Positive Integer quantity) {
    }
}
