package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.StockMovementAllocation;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of {@code GET /api/stock-movements/{inMovementId}/allocations} - "what did this
 * delivery go on to fund", the recall/dispute trace MULTI_VENDOR_INVENTORY_DESIGN.md section
 * 8/10 exists to answer. {@code inMovementId} itself is not repeated here - it is already the
 * path parameter the caller supplied.
 */
public record AllocationResponse(UUID outMovementId, int quantity, OffsetDateTime createdAt) {

    public static AllocationResponse from(StockMovementAllocation allocation) {
        return new AllocationResponse(
                // .getId() on a lazy association never triggers a DB fetch - Hibernate already
                // knows the FK value, same reasoning StockMovementResponse.from documents.
                allocation.getOutMovement().getId(), allocation.getQuantity(), allocation.getCreatedAt());
    }
}
