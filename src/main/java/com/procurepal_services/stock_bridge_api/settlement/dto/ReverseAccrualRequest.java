package com.procurepal_services.stock_bridge_api.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An operator reversing a delivered order's accrual - the refund, return or
 * post-delivery cancellation path.
 *
 * @param reason required, and shown verbatim to the vendor on every reversal line of
 *     their statement. A negative figure on a money statement with no explanation is
 *     the single most likely cause of a support call this module can produce.
 * @param markOrderRefunded whether to also move the ORDER's payment status to
 *     REFUNDED. Defaults to true, and is separable for one real case: goods returned
 *     under a replacement arrangement, where the vendor's accrual must come back but
 *     the buyer is not getting their money. Note that the money going back to the
 *     buyer happens outside this system either way - there is no refund API here, and
 *     this flag records a decision rather than moving anything.
 */
public record ReverseAccrualRequest(
        @NotBlank(message = "A reason is required when reversing an accrual.")
                @Size(max = 500, message = "Reason must be at most 500 characters.")
                String reason,
        Boolean markOrderRefunded) {
}
