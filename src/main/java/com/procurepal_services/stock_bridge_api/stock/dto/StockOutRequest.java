package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.OutReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
        List<Allocation> allocations,
        // A4: when the sale actually happened. Null means now. A phone that recorded it offline
        // sends the moment it was entered, so the ledger dates it truthfully once it arrives.
        OffsetDateTime occurredAt,
        // A4: recorded offline and arriving late - see StockInRequest.recordedOffline.
        Boolean recordedOffline,
        // V46 (SELLING_PRICE_PLAN.md section 4.3). Why it went out; null is "No reason given". A
        // request carrying a price, a sale id or a line total is a SALE whatever it says here is
        // absent - the rule that keeps an old client's priced stock-out meaning what it always did.
        OutReason reason,
        // The sale this line belongs to, made on the phone when the sale started so every line of an
        // offline sale shares it. Absent on a sale: the server makes one for this line alone.
        UUID saleId,
        // What the customer paid for this whole line, when the seller typed a total ("the six for
        // 10,800") rather than a price each. Stored exactly as the sale amount.
        @DecimalMin(value = "0", inclusive = true) BigDecimal lineTotal,
        // Multiple branches (V51): where this was recorded. Absent means the company's only branch;
        // refused at a company with several (BranchStockService.resolveForWrite). Left out of the
        // idempotency hash when absent, so a write queued before this field existed replays as before.
        UUID branchId) {

    /** The shape before {@code branchId} (multiple branches, Phase 0). */
    public StockOutRequest(
            Integer quantity,
            BigDecimal unitPrice,
            String note,
            String unit,
            List<Allocation> allocations,
            OffsetDateTime occurredAt,
            Boolean recordedOffline,
            OutReason reason,
            UUID saleId,
            BigDecimal lineTotal) {
        this(quantity, unitPrice, note, unit, allocations, occurredAt, recordedOffline, reason, saleId, lineTotal, null);
    }

    /** The shape before V46's reason, sale id and line total. */
    public StockOutRequest(
            Integer quantity,
            BigDecimal unitPrice,
            String note,
            String unit,
            List<Allocation> allocations,
            OffsetDateTime occurredAt,
            Boolean recordedOffline) {
        this(quantity, unitPrice, note, unit, allocations, occurredAt, recordedOffline, null, null, null);
    }

    /** Convenience for callers that only ever supplied the pre-V19 three fields. */
    public StockOutRequest(Integer quantity, BigDecimal unitPrice, String note) {
        this(quantity, unitPrice, note, null, null);
    }

    /** The shape before {@code occurredAt} and {@code recordedOffline} (A4). */
    public StockOutRequest(Integer quantity, BigDecimal unitPrice, String note, String unit, List<Allocation> allocations) {
        this(quantity, unitPrice, note, unit, allocations, null, null, null, null, null);
    }

    public boolean isRecordedOffline() {
        return Boolean.TRUE.equals(recordedOffline);
    }

    /** One manually-chosen lot to draw from - see the class javadoc's "allocations" section. */
    public record Allocation(@NotNull UUID inMovementId, @NotNull @Positive Integer quantity) {
    }
}
