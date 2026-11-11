package com.procurepal_services.stock_bridge_api.branch.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** {@code POST /api/stock-requests/{id}/none-in-store} (Part 5): the lines the area asked has none of. */
public record StockRequestNoneInStoreRequest(@NotEmpty @Size(max = 200) List<UUID> productIds) {
}
