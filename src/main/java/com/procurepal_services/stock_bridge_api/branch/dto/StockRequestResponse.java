package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One request, as the Store's line, the Requests list and the Move it opens show it (STOCK_AREAS_PART_5.md).
 *
 * @param fromAreaName the area asked (the Store); {@code toAreaName} the one asking (the Kitchen).
 * @param status {@code OPEN} until every line is moved or none in the store ({@code DONE}), or {@code CANCELLED}.
 * @param canFill the person asking may move it: they work in the area asked and can record a stock-out.
 * @param canCancel the person asking may take it back: they asked, or work in the asking area - while nothing
 *     on it has been moved.
 * @param movers who can move it and has a WhatsApp number, for "Tell Amaka on WhatsApp" (owner, 2026-11-01) - only
 *     to the person who asked, while it's open; empty otherwise.
 */
public record StockRequestResponse(
        UUID id,
        UUID branchId,
        UUID fromAreaId,
        String fromAreaName,
        UUID toAreaId,
        String toAreaName,
        String status,
        UUID askedById,
        String askedByName,
        OffsetDateTime askedAt,
        String note,
        List<Line> lines,
        boolean canFill,
        boolean canCancel,
        List<Mover> movers) {

    /** Someone who works in the area asked and can move it: their name and WhatsApp number (+234...). */
    public record Mover(UUID id, String name, String whatsapp) {
    }

    /**
     * @param quantity asked, in the product's stock unit; {@code enteredQuantity}/{@code enteredUnit} as typed.
     * @param moved how much of it has been moved, by moves that weren't undone or taken back.
     * @param noneInStore the area asked said it has none.
     */
    public record Line(
            UUID productId, String productName, String unitOfMeasure, BigDecimal quantity, BigDecimal enteredQuantity,
            String enteredUnit, BigDecimal moved, boolean noneInStore) {
    }
}
