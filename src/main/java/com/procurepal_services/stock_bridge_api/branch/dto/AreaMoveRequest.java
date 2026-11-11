package com.procurepal_services.stock_bridge_api.branch.dto;

import java.math.BigDecimal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/area-moves} (STOCK_AREAS_PART_2.md): stock moved from one area of a branch to another.
 *
 * @param id made on the phone, so a resend - after a lost reply, or late from a phone that was offline -
 *     is recorded once.
 * @param branchId absent: the person's branch, as for any stock write.
 * @param occurredAt when it happened, as recorded on the phone; absent means now.
 * @param recordedOffline sent late from a phone that was offline: a later count may already have seen it.
 * @param requestId the request it fills (Part 5): the area asked moving to the area that asked. A request that
 *     was cancelled or filled meanwhile doesn't stop the move - it's simply not counted towards it.
 */
public record AreaMoveRequest(
        @NotNull UUID id,
        UUID branchId,
        @NotNull UUID fromAreaId,
        @NotNull UUID toAreaId,
        OffsetDateTime occurredAt,
        Boolean recordedOffline,
        @Size(max = 1000) String note,
        @NotEmpty @Size(max = 200) List<@Valid Line> lines,
        UUID requestId) {

    /** The shape before {@code requestId} (Part 5). */
    public AreaMoveRequest(UUID id, UUID branchId, UUID fromAreaId, UUID toAreaId, OffsetDateTime occurredAt,
            Boolean recordedOffline, String note, List<Line> lines) {
        this(id, branchId, fromAreaId, toAreaId, occurredAt, recordedOffline, note, lines, null);
    }

    /**
     * @param quantity in {@code unit}, or the stock unit when it is absent - whole numbers, as on every sheet.
     * @param usedNow used straight away where it goes (Part 3.5): a crate of tomatoes for today's stew. The line is
     *     recorded as used there at once - a give (D-E7) - and doesn't join the area's stock.
     */
    public record Line(@NotNull UUID productId, @NotNull @Positive BigDecimal quantity, String unit, Boolean usedNow) {

        public Line(UUID productId, BigDecimal quantity, String unit) {
            this(productId, quantity, unit, null);
        }
    }
}
