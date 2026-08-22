package com.procurepal_services.stock_bridge_api.marketplace.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why a listing was refused.
 *
 * <p>{@code @NotBlank} is the point of the record existing. VENDOR_RESEARCH.md Section
 * C item 4 requires a rejection reason and a resubmission loop together, because they
 * are one mechanism: a vendor who is told "no" with no reason cannot fix anything, so
 * the rejection converts directly into a support ticket and the moderation queue
 * becomes a source of work rather than a filter on it. Making the reason structurally
 * mandatory is cheaper than a policy nobody enforces at 5pm on a Friday.
 *
 * <p>Sized to {@code products.rejection_reason}'s 1000 characters so an over-long
 * reason is a 400 naming the field, not a database error naming a constraint.
 */
public record RejectProductRequest(@NotBlank @Size(max = 1000) String reason) {
}
