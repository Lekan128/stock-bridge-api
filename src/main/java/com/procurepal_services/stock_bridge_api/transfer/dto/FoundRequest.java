package com.procurepal_services.stock_bridge_api.transfer.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Missing stock that turned up (MULTI_BRANCH_UX_PLAN.md section 5.7).
 *
 * @param id made on the phone, so pressing it twice records it once.
 * @param branchId where it was found: the sending branch (it never left) or the receiving one.
 * @param quantity in the product's stock unit; at most what is still missing on the line.
 */
public record FoundRequest(
        @NotNull UUID id, @NotNull UUID productId, @NotNull @Positive Integer quantity, @NotNull UUID branchId,
        OffsetDateTime occurredAt) {
}
