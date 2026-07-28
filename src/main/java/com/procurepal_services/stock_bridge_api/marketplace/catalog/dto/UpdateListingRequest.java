package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Boxed Boolean with @NotNull rather than a primitive: a primitive would silently
 * default a missing/misspelled field to false and UNLIST a product the operator only
 * meant to leave alone.
 */
public record UpdateListingRequest(@NotNull Boolean listed) {
}
