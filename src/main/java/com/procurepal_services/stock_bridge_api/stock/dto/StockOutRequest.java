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
