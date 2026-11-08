package com.procurepal_services.stock_bridge_api.stock.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

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
 * @param branchId the branch whose shelf was counted (V51); absent means the company's only branch -
 *     see {@code StockInRequest.branchId}.
 * @param areaId the area of that branch that was counted (V60) - see {@code StockInRequest.areaId}.
 * @param shortAs what a shortfall is, for this count only (Part 3.5): {@code USED} or {@code MISSING}. Absent:
 *     what the area says - used where stock is worked with (a kitchen), missing where it's stored.
 */
public record StockCountRequest(
        @NotNull @Min(0) BigDecimal countedQuantity,
        OffsetDateTime countedAt,
        @Size(max = 1000) String note,
        UUID branchId,
        UUID areaId,
        ShortAs shortAs) {

    /** What a count's shortfall was (Part 3.5, D-A12). */
    public enum ShortAs {
        USED,
        MISSING
    }

    /** The shape before {@code shortAs} (areas, Part 3.5). */
    public StockCountRequest(BigDecimal countedQuantity, OffsetDateTime countedAt, String note, UUID branchId, UUID areaId) {
        this(countedQuantity, countedAt, note, branchId, areaId, null);
    }

    /** The shape before {@code areaId} (areas, Part 0). */
    public StockCountRequest(BigDecimal countedQuantity, OffsetDateTime countedAt, String note, UUID branchId) {
        this(countedQuantity, countedAt, note, branchId, null, null);
    }

    /** The shape before {@code branchId} (multiple branches, Phase 0). */
    public StockCountRequest(BigDecimal countedQuantity, OffsetDateTime countedAt, String note) {
        this(countedQuantity, countedAt, note, null, null, null);
    }

    /** This count, at {@code branch} - how a caller states the branch it has resolved. */
    public StockCountRequest withBranchId(UUID branch) {
        return new StockCountRequest(countedQuantity, countedAt, note, branch, areaId, shortAs);
    }
}
