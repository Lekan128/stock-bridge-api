package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.Size;
import java.util.UUID;

/** PATCH-like PUT - see UpdateMarketplaceDetailsRequest and the existing UpdateProductRequest. */
public record UpdateCategoryRequest(
        @Size(max = 120) String name,
        @Size(max = 120) String slug,
        UUID parentId,
        // True detaches this category from its parent; parentId alone cannot express that.
        Boolean clearParent,
        Integer sortOrder,
        Boolean active) {
}
