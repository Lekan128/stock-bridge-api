package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovementAllocation;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/stock-movements/{inMovementId}/allocations} - "what did this
 * delivery go on to fund", the recall/dispute trace MULTI_VENDOR_INVENTORY_DESIGN.md section
 * 8/10 exists to answer. {@code inMovementId} itself is not repeated here - it is already the
 * path parameter the caller supplied.
 *
 * <p>V53: stock sent to another branch is followed there (MULTI_BRANCH_BUILD_PLAN.md section 4.3).
 * The TRANSFER_OUT row is listed, then what the stock funded at the receiving branch, each such row
 * naming the lot it came through ({@code throughLotId}, the TRANSFER_IN lot).
 */
public record AllocationResponse(
        UUID outMovementId,
        int quantity,
        OffsetDateTime createdAt,
        // V46: true on the row that handed stock BACK to this delivery when a sale was cancelled.
        // Its quantity is negative and outMovementId is the cancellation, not a stock-out.
        boolean returnedByCancellation,
        /** What drew on it: OUT (a sale or other stock out), TRANSFER_OUT (sent), ADJUSTMENT (a cancellation). */
        MovementType outMovementType,
        /** The branch it was drawn at. */
        UUID branchId,
        /** Null for this lot's own draws; for stock followed across a transfer, the lot it arrived as. */
        UUID throughLotId) {

    public static AllocationResponse from(StockMovementAllocation allocation) {
        return from(allocation, null);
    }

    public static AllocationResponse from(StockMovementAllocation allocation, UUID throughLotId) {
        return new AllocationResponse(
                // .getId() on a lazy association never triggers a DB fetch - Hibernate already
                // knows the FK value, same reasoning StockMovementResponse.from documents.
                allocation.getOutMovement().getId(),
                allocation.getQuantity(),
                allocation.getCreatedAt(),
                allocation.getQuantity() < 0,
                allocation.getOutMovement().getMovementType(),
                allocation.getOutMovement().getBranchId(),
                throughLotId);
    }
}
