package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code quantity} is in whichever unit {@code unit} names, exactly like {@link
 * StockInRequest#quantity()} - converted to base units before anything is written; every stored
 * quantity stays in base units.
 *
 * <h2>Simple by default, advanced on request (MULTI_VENDOR_INVENTORY_DESIGN.md section 6)</h2>
 * When {@code allocations} is null or empty (the common case), {@code StockManagementService}
 * computes it itself via lot-level FIFO - oldest {@code IN} movement first, across every vendor
 * - locking candidate lots for the duration of the transaction. The resulting breakdown is
 * still returned, exactly as if the caller had specified it manually.
 *
 * <h2>allocations - the manual override</h2>
 * When present, this exact set of {@code (inMovementId, quantity)} pairs is drawn from instead
 * of FIFO - the "Choose vendor / unit manually" disclosure. Each is validated: the referenced
 * movement must be an {@code IN} movement belonging to this same product, and must have enough
 * remaining balance to cover the requested quantity (see {@code InvalidStockAllocationException}).
 * The sum of {@code quantity} across all lines need not equal this request's own {@code
 * quantity} field being VALIDATED against it - {@code StockManagementService} requires the two
 * to match exactly, since a manual allocation that under- or over-specifies the total requested
 * quantity has no defined meaning.
 *
 * <h2>allocations[].quantity is in BASE UNITS - and stays that way</h2>
 * Unlike this request's own {@code quantity}, which is in whatever {@code unit} names, every
 * allocation line's quantity is in the product's <b>stock unit</b>, always, with no {@code unit}
 * of its own. UNIT_UX_CONTRACT.md section 4 pins that and asks for it to be documented here.
 *
 * <p>It is stated rather than fixed because a lot IS a base-unit quantity: {@code
 * StockMovement.quantity} and the remaining balance derived from {@code StockMovementAllocation}
 * are both base units, and the picker shows the user a remaining figure in that unit. Letting a
 * line be expressed in bags would mean rounding each line to whole stock units and then hoping
 * the rounded lines still summed to the request's own converted total - a reconciliation with no
 * good answer when they did not.
 *
 * <p>So the UI is what converts, and the server validates the sum in base units. That asymmetry
 * is exactly the trap of P1-4 (UNIT_UX_REMEDIATION_PLAN.md section 3), where the modal compared
 * allocation lines against the quantity as TYPED - toggle to bags, enter 3, allocate 3, pass
 * client validation, then be told "allocations sum to 3 but the requested quantity is 150". The
 * shape is unchanged; what changed is that it is now written down, and that
 * {@code InvalidStockAllocationException} states the unit of both numbers when they disagree.
 *
 * <h2>unit - which of the product's units this request's own quantity is in</h2>
 * Same rule as {@code StockInRequest.unit}: it must name an option in the product's unit set
 * (contract section 2.1), resolved by {@code StockManagementService}. There is no pack override
 * here and never was - which vendor's stock a sale draws from is not known until FIFO resolves
 * it, so stock-out can only ever offer the PRODUCT's own units (MULTI_VENDOR_INVENTORY_DESIGN.md
 * section 5.3). Null or blank means the stock unit, unchanged from today.
 */
public record StockOutRequest(
        @NotNull @Positive Integer quantity,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @Size(max = 1000) String note,
        String unit,
        List<Allocation> allocations) {

    /** Convenience for callers that only ever supplied the pre-V19 three fields. */
    public StockOutRequest(Integer quantity, BigDecimal unitPrice, String note) {
        this(quantity, unitPrice, note, null, null);
    }

    /** One manually-chosen lot to draw from - see the class javadoc's "allocations" section. */
    public record Allocation(@NotNull UUID inMovementId, @NotNull @Positive Integer quantity) {
    }
}
