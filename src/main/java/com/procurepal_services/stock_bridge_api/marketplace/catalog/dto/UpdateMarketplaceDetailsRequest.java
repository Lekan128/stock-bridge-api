package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The marketplace-only facets of a product. Everything else about it (name, price,
 * image, SKU) is still edited through /api/products, because ProcurePal's catalog rows
 * are ordinary tenant products that happen to be for sale - duplicating that editing
 * surface here would give the same row two writers.
 *
 * <p>{@code unitOfMeasure} used to be here too, alongside brand, but has moved onto
 * /api/products create/update - the same request as everything else a seller sets on a
 * product - now paired there with the {@code packagingUnit}/{@code packagingSize} pair (unit
 * "KG" + packaging "BAG" + size 50 = "a 50kg bag"). It is validated against the fixed
 * {@code product.unit.UnitOfMeasure}
 * catalog on that path now, not here. This record keeps {@code brand}: it has no equivalent
 * on an ordinary buying company's product, unlike the unit fields which every tenant now
 * benefits from recording, so it stays a marketplace-only facet edited on this route.
 *
 * Every field is nullable and PATCH-like even though the verb is PUT, matching
 * UpdateProductRequest's existing convention in this codebase. The one asymmetry worth
 * knowing: {@code categoryId} null means "leave as is", not "uncategorise" - see
 * {@code clearCategory}, which exists because a nullable field cannot express both.
 */
public record UpdateMarketplaceDetailsRequest(
        UUID categoryId,
        Boolean clearCategory,
        @Min(1) Integer minOrderQuantity,
        @Size(max = 120) String brand,
        @Size(max = 160) String slug) {
}
