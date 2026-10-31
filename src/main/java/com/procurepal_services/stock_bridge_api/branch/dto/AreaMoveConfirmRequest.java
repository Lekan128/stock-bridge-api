package com.procurepal_services.stock_bridge_api.branch.dto;

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
 * @param rest what happened to whatever didn't arrive (D-A9): {@code BACK} (absent) - it's still where it was
 *     moved from, and goes back to that area's figure - or {@code DAMAGED}, recorded as damaged there.
 * @param occurredAt when it was confirmed, as recorded on the phone; absent means now.
 * @param recordedOffline sent late from a phone that was offline: a later count may already have seen it.
 */
public record AreaMoveConfirmRequest(
        @Size(max = 200) List<@Valid Line> lines,
        Rest rest,
        OffsetDateTime occurredAt,
        Boolean recordedOffline) {

    public record Line(@NotNull UUID productId, @NotNull @PositiveOrZero Integer got) {
    }

    public enum Rest {
        BACK,
        DAMAGED
    }

    /** Everything arrived. */
    public static AreaMoveConfirmRequest everything() {
        return new AreaMoveConfirmRequest(List.of(), null, null, null);
    }
}
