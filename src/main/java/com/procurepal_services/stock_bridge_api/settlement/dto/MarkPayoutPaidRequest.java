package com.procurepal_services.stock_bridge_api.settlement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * An operator recording that they made the transfer.
 *
 * <h2>Why the reference is required</h2>
 * This module integrates no disbursement API and calls no bank. The only evidence
 * that a vendor was paid is a human saying so, and a human saying so without a bank
 * reference is not evidence - it is a checkbox. Requiring it means every PAID batch
 * can be tied to a line on a bank statement, which is the first thing anyone asks
 * for in a payment dispute.
 *
 * <p>Free text on purpose, and not validated beyond being present: transfer
 * references differ by bank and by channel, and a format rule here would eventually
 * refuse a real one.
 */
public record MarkPayoutPaidRequest(
        @NotBlank(message = "A bank transfer reference is required.")
                @Size(max = 100, message = "Reference must be at most 100 characters.")
                String paymentReference,
        @Size(max = 500, message = "Note must be at most 500 characters.") String note) {
}
