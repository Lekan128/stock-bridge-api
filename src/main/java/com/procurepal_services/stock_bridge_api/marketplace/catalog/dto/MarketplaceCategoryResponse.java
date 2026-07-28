package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import java.util.UUID;

/**
 * Matches the frontend's {@code MarketplaceCategory} (src/features/storefront/types.ts).
 *
 * {@code productCount} is optional there and always populated here. On the public
 * endpoint it counts only LISTED, ACTIVE products belonging to the platform owner -
 * i.e. exactly what the buyer would see after clicking the category - so the storefront
 * menu can hide empty categories instead of leading someone to an empty grid. On the
 * admin endpoint it counts everything in the category, because "0 listed, 7 products"
 * is precisely the state ProcurePal's merchandiser is there to fix.
 */
public record MarketplaceCategoryResponse(
        UUID id, String name, String slug, UUID parentId, int sortOrder, boolean active, long productCount) {

    public static MarketplaceCategoryResponse from(ProductCategory category, long productCount) {
        return new MarketplaceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                // getParent() is a LAZY association; taking only the id keeps this to
                // the FK Hibernate already holds in the proxy and never triggers a
                // per-category SELECT.
                category.getParent() == null ? null : category.getParent().getId(),
                category.getSortOrder(),
                category.isActive(),
                productCount);
    }
}
