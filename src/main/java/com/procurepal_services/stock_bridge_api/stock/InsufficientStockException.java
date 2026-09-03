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
 * <p>{@code unitSymbol} is the product's stock unit written the way a person writes it after a
 * number - "kg", not "Kilogram (kg)" and not "KG" - supplied by the throw site (see {@code
 * StockManagementService.stockOut}), falling back to "units" for a product that has none
 * configured.
 *
 * <h2>Both numbers state their unit, and that is a rule rather than a flourish</h2>
 * UNIT_UX_CONTRACT.md non-negotiable 2 and UNIT_UX_REMEDIATION_PLAN.md section 3's P2: a bare
 * "Only 34 available, 50 requested" is exactly the ambiguity this whole remediation is about,
 * because the user typed 50 in a box that may have said bags. Naming the unit on BOTH figures is
 * what makes the sentence answer the question the user actually has - "34 of what, and is my 50
 * the same what?" - rather than restating the numbers they already saw.
 *
 * <p>The message contains no id and no column name (non-negotiable 6, contract section 4). The
 * frontend never needs to parse it in any case; it prefers the raw {@link #availableQuantity}/
 * {@link #requestedQuantity} numbers, which are and stay in the product's stock unit.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final int availableQuantity;
    private final int requestedQuantity;

    public InsufficientStockException(int available, int requested) {
        this(available, requested, null);
    }

    /**
     * @param available how much is on hand, in the product's stock unit.
     * @param requested how much the request asked for, converted into that same stock unit -
     *     never the number as typed, or the two figures in the message would be comparing
     *     different things, which is the P1-4 defect one layer up.
     * @param unitSymbol the stock unit's short symbol ("kg"), or null for a product with none.
     */
    public InsufficientStockException(int available, int requested, String unitSymbol) {
        super("Only " + available + " " + symbolOrUnits(unitSymbol) + " available — you asked for " + requested + " "
                + symbolOrUnits(unitSymbol) + ".");
        this.availableQuantity = available;
        this.requestedQuantity = requested;
    }

    private static String symbolOrUnits(String unitSymbol) {
        return unitSymbol == null || unitSymbol.isBlank() ? "units" : unitSymbol;
    }
}
