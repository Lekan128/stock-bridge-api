package com.procurepal_services.stock_bridge_api.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Create/update payload for a delivery location.
 *
 * No country field: ProcurePal delivers within Nigeria only (contract §4.3), and
 * {@code state} is validated server-side against the 36 states + FCT rather than
 * trusted as free text - a typo'd state silently breaks delivery routing and skews
 * the regional analytics, and a select on the frontend is not a guarantee.
 *
 * {@code makeDefault} is part of the same payload rather than a separate call so
 * "add my first warehouse and make it the default" is one round trip; the swap
 * itself is transactional (see DeliveryAddressService).
 */
public record DeliveryAddressRequest(
        @NotBlank @Size(max = 100) String label,
        @NotBlank @Size(max = 255) String contactName,
        @NotBlank @Size(max = 50) String contactPhone,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String state,
        @Size(max = 255) String landmark,
        @Size(max = 500) String deliveryNotes,
        UUID branchId,
        Boolean makeDefault) {
}
