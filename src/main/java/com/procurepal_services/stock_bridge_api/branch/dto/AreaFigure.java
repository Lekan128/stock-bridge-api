package com.procurepal_services.stock_bridge_api.branch.dto;

import java.util.UUID;

/**
 * One product in one area (STOCK_AREAS_PLAN.md section 4.3): "Store 30 · Kitchen 6". Listed for every
 * open area that keeps stock, at a branch with more than one area - an area holding none reads 0, and
 * the app hides it.
 */
public record AreaFigure(UUID areaId, UUID branchId, int quantityOnHand, java.time.OffsetDateTime lastCountedAt) {

    public AreaFigure(UUID areaId, UUID branchId, int quantityOnHand) {
        this(areaId, branchId, quantityOnHand, null);
    }

    /** This figure with when the area was last counted (Part 3.5): the product page's count sheet says it. */
    public AreaFigure withLastCountedAt(java.time.OffsetDateTime at) {
        return new AreaFigure(areaId, branchId, quantityOnHand, at);
    }
}
