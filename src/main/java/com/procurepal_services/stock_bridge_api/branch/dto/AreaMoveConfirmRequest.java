package com.procurepal_services.stock_bridge_api.branch.dto;

import com.procurepal_services.stock_bridge_api.entity.OutReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/area-moves/{id}/confirm} (STOCK_AREAS_PART_4.md): what an area that confirms got of a move.
 *
 * @param lines what arrived of each line, in the product's stock unit; a line left out arrived whole.
 * @param rest what happened to whatever didn't arrive, for lines that don't say (D-A9; a phone from before Part 6
 *     sends only this): {@link Rest#BACK} (absent) - it's still where it was moved from, and goes back to that
 *     area's figure - or written off there for the reason given.
 * @param note the confirmer's words about it (owner, 2026-11-01: "adding notes when moved stock … is reduced").
 * @param occurredAt when it was confirmed, as recorded on the phone; absent means now.
 * @param recordedOffline sent late from a phone that was offline: a later count may already have seen it.
 */
public record AreaMoveConfirmRequest(
        @Size(max = 200) List<@Valid Line> lines,
        Rest rest,
        @Size(max = 500) String note,
        OffsetDateTime occurredAt,
        Boolean recordedOffline) {

    /** @param rest what happened to this line's shortfall (STOCK_AREAS_PART_6.md); absent: the move's {@code rest}. */
    public record Line(@NotNull UUID productId, @NotNull @PositiveOrZero java.math.BigDecimal got, Rest rest) {
    }

    /** What happened to what didn't arrive: still where it was, or written off there as one of the stock-out reasons. */
    public enum Rest {
        BACK(null),
        DAMAGED(OutReason.DAMAGED),
        EXPIRED(OutReason.EXPIRED),
        /** Missing. */
        LOST(OutReason.LOST),
        OTHER(OutReason.OTHER);

        private final OutReason outReason;

        Rest(OutReason outReason) {
            this.outReason = outReason;
        }

        /** The stock-out it's written off as; null: it goes back. */
        public OutReason outReason() {
            return outReason;
        }
    }

    /** Everything arrived. */
    public static AreaMoveConfirmRequest everything() {
        return new AreaMoveConfirmRequest(List.of(), null, null, null, null);
    }
}
