package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/stock-requests} (STOCK_AREAS_PART_5.md): one area asking another of its branch for stock.
 *
 * @param id made on the phone, so a resend is recorded once (D-E11).
 * @param branchId absent: the person's branch, as for any stock write.
 * @param fromAreaId the area asked - the one that has it (the Store).
 * @param toAreaId the area asking (the Kitchen); the asker works there.
 * @param occurredAt when it was asked, as recorded on the phone; absent means now.
 */
public record StockRequestCreateRequest(
        @NotNull UUID id,
        UUID branchId,
        @NotNull UUID fromAreaId,
        @NotNull UUID toAreaId,
        OffsetDateTime occurredAt,
        @Size(max = 1000) String note,
        @NotEmpty @Size(max = 200) List<@Valid Line> lines) {

    /** @param quantity in {@code unit}, or the stock unit when it is absent - whole numbers, as on every sheet. */
    public record Line(@NotNull UUID productId, @NotNull @Positive Integer quantity, String unit) {
    }
}
