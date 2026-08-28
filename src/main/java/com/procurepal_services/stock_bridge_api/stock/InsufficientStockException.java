package com.procurepal_services.stock_bridge_api.stock;

import lombok.Getter;

/**
 * A {@code stockOut} would take a product's on-hand (or, since V19, its lot ledger) negative.
 * Carries {@link #availableQuantity}/{@link #requestedQuantity} as fields, not just baked into
 * the message, so a controller can map this to a 409 body naming both numbers explicitly -
 * MULTI_VENDOR_INVENTORY_DESIGN.md section 8: "409/insufficient-stock response states the
 * actual available quantity rather than a bare rejection - never silently goes negative."
 *
 * <p>Since V19, {@code availableQuantity} is the sum of unconsumed quantity across every lot
 * (IN movement) for the product - the ledger total, not the simple {@code
 * Product.quantityOnHand} counter alone, though the two agree by construction as long as
 * nothing has bypassed {@code StockManagementService}.
 *
 * <p>{@code unitLabel} is optional - a human-readable label ("Kilogram (kg)") for the product's
 * base unit, when the throw site has it handy (see {@code StockManagementService.stockOut}),
 * purely to make {@link #getMessage()} read naturally ("Only 34 kg available, 50 requested"
 * rather than a bare "34 available, 50 requested"). {@code StockManagementExceptionHandler}
 * falls back to a generic "units" when absent - the frontend never actually needs to parse this
 * message; it prefers the raw {@link #availableQuantity}/{@link #requestedQuantity} numbers.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final int availableQuantity;
    private final int requestedQuantity;

    public InsufficientStockException(int available, int requested) {
        this(available, requested, null);
    }

    public InsufficientStockException(int available, int requested, String unitLabel) {
        super("Only " + available + " " + (unitLabel == null ? "units" : unitLabel) + " available, " + requested + " requested");
        this.availableQuantity = available;
        this.requestedQuantity = requested;
    }
}
