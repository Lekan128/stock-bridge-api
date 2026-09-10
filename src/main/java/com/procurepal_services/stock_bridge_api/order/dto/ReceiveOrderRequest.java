package com.procurepal_services.stock_bridge_api.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
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

    /**
     * {@code linkToExistingProductId} is the buyer answering the MULTI_VENDOR_INVENTORY_DESIGN.md
     * section 7.2 duplicate nudge - "yes, this is the same item as one I already have" - rather
     * than receiving into whatever product {@code IncomingStockService} auto-matched or created
     * at PLACED. Optional and ignored outside the narrow case it applies to; see
     * {@code IncomingStockService.receive}.
     *
     * <p>{@code packagingUnit}/{@code packagingSize} answer the follow-up question a relink can
     * raise: the order line's unit (a seller's catalog product's) is not guaranteed to be one the
     * chosen product already accepts. Both null when no conversion needed defining - the ordinary
     * case, where the units already match or fall in the same static category (kg/g/t). When set,
     * they extend the target's unit set for this receipt exactly the way a manual stock-in's "this
     * delivery came in a different pack" override does (contract section 3.1) - {@code
     * packagingUnit} is literally the order line's own unit string, and {@code packagingSize} is
     * however many of the target's stock units the buyer says one of it holds.
     *
     * <p>{@code saveAsSupplierDefault} - contract section 3.4's explicit opt-in - keeps that
     * conversion for the next delivery from this same seller rather than applying it once; false/
     * absent (the default) means once only. Both are ignored unless {@code linkToExistingProductId}
     * is also being honoured; there is nothing to extend a unit set for otherwise.
     */
    public record ReceiveOrderLine(
            @NotNull UUID orderItemId,
            @NotNull @Positive Integer quantity,
            UUID linkToExistingProductId,
            String packagingUnit,
            @DecimalMin(value = "0", inclusive = false) BigDecimal packagingSize,
            Boolean saveAsSupplierDefault) {

        /** Convenience for the pre-pack-override three-field shape - additive, same reasoning as
         *  {@code StockInRequest}'s own constructors. */
        public ReceiveOrderLine(UUID orderItemId, Integer quantity, UUID linkToExistingProductId) {
            this(orderItemId, quantity, linkToExistingProductId, null, null, null);
        }
    }
}
