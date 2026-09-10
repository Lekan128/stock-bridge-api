package com.procurepal_services.stock_bridge_api.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An operator recording that the transfer did not happen.
 *
 * <p>The reason is required by the database as well
 * ({@code chk_vendor_payout_batches_failure_shape}), because failing a batch
 * releases its lines back into the next run - a consequential action, and one that
 * is indistinguishable from a misclick if nobody wrote down why.
 */
public record MarkPayoutFailedRequest(
        @NotBlank(message = "A reason is required when a payout batch is marked failed.")
                @Size(max = 500, message = "Reason must be at most 500 characters.")
                String reason) {
}
