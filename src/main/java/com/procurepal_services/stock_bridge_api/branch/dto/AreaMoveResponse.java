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
 * @param status {@code DONE}, or {@code VOIDED} once undone; for a move to an area that confirms (Part 4)
 *     {@code WAITING} until it does, then {@code DONE} (got everything) or {@code CONFIRMED_SHORT}, or
 *     {@code CANCELLED} if it was taken back while waiting.
 * @param undoUntil until when its mover may undo it; absent once that is past or not possible.
 * @param waits it went to an area that confirms, so its lines waited (Part 4).
 * @param confirmedByName who confirmed it, and when; absent until then.
 * @param canConfirm the person asking may confirm it: it waits for an area they work in, and they can record
 *     stock in.
 * @param canCancel the person asking may take it back while it waits: they moved it, or work where it was moved
 *     from.
 * @param confirmNote the confirmer's words about what didn't arrive (Part 6).
 * @param canReturn the person asking may give back or report part of it (Part 6): it arrived in an area they work
 *     in, within {@code AreaReturns.WINDOW}, and wasn't itself a give back.
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
        List<Line> lines,
        boolean waits,
        String confirmedByName,
        OffsetDateTime confirmedAt,
        boolean canConfirm,
        boolean canCancel,
        String confirmNote,
        boolean canReturn) {

    /**
     * @param quantity in the product's stock unit; {@code enteredQuantity}/{@code enteredUnit} as typed.
     * @param got what arrived, once a move that waited is confirmed (Part 4); absent before.
     * @param damaged of what didn't arrive, what was recorded as damaged.
     * @param unitOfMeasure the product's stock unit, which {@code quantity} and {@code got} are in.
     * @param restReason what happened to what didn't arrive (Part 6): BACK, DAMAGED, EXPIRED, LOST, OTHER.
     * @param returns what an area that uses stock up gave back of it, or reported (Part 6), oldest first.
     * @param returnable what's left of it to give back or report: what was used, less those.
     */
    public record Line(
            UUID productId, String productName, BigDecimal quantity, BigDecimal enteredQuantity, String enteredUnit,
            boolean used, BigDecimal got, BigDecimal damaged, String unitOfMeasure, String restReason, List<Return> returns,
            BigDecimal returnable) {
    }

    /** Given back, or reported spoilt / expired / damaged / other (Part 6). {@code quantity} in the stock unit. */
    public record Return(String kind, BigDecimal quantity, String reason, String note, String byName, OffsetDateTime at) {
    }
}
