package com.procurepal_services.stock_bridge_api.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Send stock (MULTI_BRANCH_UX_PLAN.md section 5.4).
 *
 * @param id made on the phone: it is the waybill's id, so sending it twice records it once.
 * @param occurredAt when it left, as recorded on the phone; null is now.
 * @param receiveNow "It's already there - record it as received" (section 5.6): both ends at once, for
 *     someone who works at both branches.
 */
public record TransferRequest(
        @NotNull UUID id,
        @NotNull UUID fromBranchId,
        @NotNull UUID toBranchId,
        OffsetDateTime occurredAt,
        @Size(max = 120) String carriedByName,
        @Size(max = 50) String carriedByPhone,
        @Size(max = 1000) String note,
        @NotEmpty(message = "Add at least one thing to send.") @Valid List<Line> lines,
        Boolean receiveNow) {

    /** {@code quantity} in {@code unit} - a pack ("CRATE") or the stock unit when absent. */
    public record Line(@NotNull UUID productId, @NotNull @Positive Integer quantity, String unit) {
    }

    public boolean receivesNow() {
        return Boolean.TRUE.equals(receiveNow);
    }
}
