package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code POST /api/area-moves/{id}/returns} (STOCK_AREAS_PART_6.md): an area that uses stock up gives part of a give
 * back, or reports it spoilt, expired, damaged.
 *
 * @param id made on the phone: a resend is recorded once.
 * @param quantity whole numbers in {@code unit}, or the stock unit when it's absent, as on every sheet.
 * @param reason for a report: SPOILT, EXPIRED, DAMAGED, OTHER.
 * @param occurredAt when it was done on the phone; absent means now.
 * @param recordedOffline sent late from a phone that was offline: a later count may already have seen it.
 */
public record AreaReturnRequest(
        @NotNull UUID id,
        @NotNull UUID productId,
        @NotNull Kind kind,
        @NotNull @Positive Integer quantity,
        @Size(max = 32) String unit,
        Reason reason,
        @Size(max = 500) String note,
        OffsetDateTime occurredAt,
        Boolean recordedOffline) {

    public enum Kind {
        GIVE_BACK,
        REPORT
    }

    public enum Reason {
        SPOILT,
        EXPIRED,
        DAMAGED,
        OTHER
    }
}
