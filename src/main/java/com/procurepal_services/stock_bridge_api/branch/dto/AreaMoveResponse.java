package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One move between areas, as its screens and the Moves list show it (STOCK_AREAS_PART_2.md).
 *
 * @param toKeepsStock false for a give to an area that uses what it's given: each line was recorded as Used
 *     there.
 * @param status {@code DONE}, or {@code VOIDED} once undone.
 * @param undoUntil until when its mover may undo it; absent once that is past or not possible.
 */
public record AreaMoveResponse(
        UUID id,
        UUID branchId,
        UUID fromAreaId,
        String fromAreaName,
        UUID toAreaId,
        String toAreaName,
        boolean toKeepsStock,
        String status,
        UUID movedById,
        String movedByName,
        OffsetDateTime movedAt,
        String note,
        OffsetDateTime undoUntil,
        List<Line> lines) {

    /** @param quantity in the product's stock unit; {@code enteredQuantity}/{@code enteredUnit} as typed. */
    public record Line(
            UUID productId, String productName, int quantity, BigDecimal enteredQuantity, String enteredUnit,
            boolean used) {
    }
}
