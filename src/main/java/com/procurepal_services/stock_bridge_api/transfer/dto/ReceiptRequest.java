package com.procurepal_services.stock_bridge_api.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Receive stock off a waybill (MULTI_BRANCH_UX_PLAN.md section 5.5).
 *
 * @param id made on the phone, so a receipt sent twice is recorded once.
 * @param asSent "Received as sent": everything still on the way, received. {@code lines} is then ignored.
 * @param lines per product, in the stock unit: what arrived, what didn't, what arrived unusable. A line
 *     left out is still on the way.
 */
public record ReceiptRequest(
        @NotNull UUID id, OffsetDateTime occurredAt, Boolean asSent, @Valid List<Line> lines) {

    public record Line(@NotNull UUID productId, @Min(0) Integer received, @Min(0) Integer missing, @Min(0) Integer damaged) {
    }

    public boolean receivesAsSent() {
        return Boolean.TRUE.equals(asSent);
    }
}
