package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * A stock count: how much was on the shelf, and when it was counted (A4, decision D1). Replaces
 * "set the quantity to N" as what Adjust means.
 *
 * <p>The count is a fact about the shelf at {@code countedAt}, not a new quantity to overwrite
 * today's with. Anything recorded as happening after that moment - a sale another phone made while
 * this one was offline - is carried forward on top of it, so a count that arrives late never
 * erases somebody else's work.
 *
 * @param countedQuantity in the product's stock unit, like every stored quantity.
 * @param countedAt when the shelf was counted. Null means now - the online case, where this is
 *     exactly the old absolute adjustment.
 * @param note optional; the history shows "Stock count" when it is absent.
 */
public record StockCountRequest(
        @NotNull @Min(0) Integer countedQuantity, OffsetDateTime countedAt, @Size(max = 1000) String note) {
}
