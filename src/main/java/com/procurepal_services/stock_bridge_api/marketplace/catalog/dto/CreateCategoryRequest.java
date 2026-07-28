package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * {@code slug} is optional - omitted, it is derived from the name, which is what an
 * operator typing "Cooking Oils & Fats" into a form expects. Supplied, it is honoured
 * exactly (after normalisation) and a collision is a 409 rather than a silent rename,
 * because a slug someone chose is usually a slug something already links to.
 *
 * {@code sortOrder} and {@code active} default at the service, not here, so a JSON body
 * that omits them means "sensible default" rather than 0/false.
 */
public record CreateCategoryRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String slug,
        UUID parentId,
        Integer sortOrder,
        Boolean active) {
}
