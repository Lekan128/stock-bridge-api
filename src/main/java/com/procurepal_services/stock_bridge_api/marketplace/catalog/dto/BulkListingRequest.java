package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Select-all-then-list on the admin table. Capped at 500 ids because the handler
 * touches one row per id inside a single transaction; an unbounded list from a
 * scripted caller would hold locks across the whole catalog.
 */
public record BulkListingRequest(
        @NotEmpty @Size(max = 500) List<UUID> productIds, @NotNull Boolean listed) {
}
