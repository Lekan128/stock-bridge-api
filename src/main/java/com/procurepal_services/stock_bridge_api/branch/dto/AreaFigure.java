package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product in one area (STOCK_AREAS_PLAN.md section 4.3): "Store 30 · Kitchen 6". Listed for every
 * open area that keeps stock, at a branch with more than one area - an area holding none reads 0, and
 * the app hides it.
 */
public record AreaFigure(
        UUID areaId, UUID branchId, BigDecimal quantityOnHand, java.time.OffsetDateTime lastCountedAt, boolean carried,
        BigDecimal waiting,
        // V85: how that last count was typed - "1 bag + 2 kg" - when it was sent box by box. Absent otherwise.
        java.util.List<com.procurepal_services.stock_bridge_api.stock.dto.StockCountRequest.CountedLine> lastCountedAs) {

    public AreaFigure(
            UUID areaId, UUID branchId, BigDecimal quantityOnHand, java.time.OffsetDateTime lastCountedAt, boolean carried,
            BigDecimal waiting) {
        this(areaId, branchId, quantityOnHand, lastCountedAt, carried, waiting, null);
    }

    /**
     * @param carried the area has had this product - holds it, or ran out of it (Part 3.6): its list in an area
     *     view, and "Out" only for these. A product it never had isn't "out" there, just not its.
     * @param waiting moved to this area and not confirmed yet (Part 4): in the branch's figure, in no area's -
     *     "2 waiting for Kitchen".
     */
    public AreaFigure(UUID areaId, UUID branchId, BigDecimal quantityOnHand, boolean carried, BigDecimal waiting) {
        this(areaId, branchId, quantityOnHand, null, carried, waiting);
    }

    /** This figure with when the area was last counted (Part 3.5): the product page's count sheet says it. */
    public AreaFigure withLastCountedAt(java.time.OffsetDateTime at) {
        return new AreaFigure(areaId, branchId, quantityOnHand, at, carried, waiting, lastCountedAs);
    }
}
