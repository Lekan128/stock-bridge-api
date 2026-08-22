package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceCategoryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceSellerResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.PublicMarketplaceSettingsResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The marketplace's public shop window. Every path here is in
 * SecurityConfig.PERMIT_ALL_PATHS and is served to anonymous visitors, so there is no
 * @PreAuthorize on anything - and, critically, no tenant context either. See
 * MarketplaceCatalogService for how these reads are scoped without one.
 *
 * <p>It is no longer ProcurePal's shop window alone: since the multi-vendor module,
 * every route here spans ProcurePal AND every active vendor, and each product carries
 * the seller who is selling it.
 *
 * <h2>Why pagination is hand-rolled instead of using Pageable</h2>
 * Spring's PageableHandlerMethodArgumentResolver claims the {@code sort} request
 * parameter and parses it as {@code property,direction}. This API's {@code sort} is a
 * closed enum of merchandising options (see CatalogSort), so letting the resolver near it
 * would turn {@code ?sort=PRICE_ASC} into Sort.by("PRICE_ASC") and a 500 from Hibernate -
 * and {@code ?sort=costPrice,desc} into a working query that publishes ProcurePal's
 * margins. Binding page/size directly is what keeps both from being possible.
 */
@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
public class MarketplaceCatalogController {

    private final MarketplaceCatalogService marketplaceCatalogService;

    /**
     * The catalog grid, and - when {@code ids} is present - the cart's batch lookup.
     *
     * One handler for both because they are the same resource selected two ways, and
     * because a second path would have meant a second place to get the
     * listed/active/owner predicate right. {@code ids} wins over the filter parameters
     * when both are sent; that combination has no sensible meaning and silently ignoring
     * the filters is better than a 400 in the middle of rendering someone's cart.
     */
    @GetMapping("/catalog")
    public Page<MarketplaceProductResponse> catalog(
            @RequestParam(required = false) List<UUID> ids,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID sellerId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false, defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false, defaultValue = "RELEVANCE") CatalogSort sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        if (ids != null && !ids.isEmpty()) {
            return marketplaceCatalogService.findByIds(ids);
        }
        return marketplaceCatalogService.browse(
                q, categoryId, sellerId, minPrice, maxPrice, inStockOnly, sort, page, size);
    }

    /**
     * The seller directory: everyone a buyer can currently buy from. Powers the
     * storefront's "Sold by" filter and the vendor index.
     *
     * <p>Not a general client listing, and the distinction is the security property:
     * it returns active SELLERS only, so no amount of paging reveals the buying
     * companies that also live in {@code clients}.
     */
    @GetMapping("/sellers")
    public List<MarketplaceSellerResponse> sellers() {
        return marketplaceCatalogService.sellers();
    }

    /**
     * One seller's storefront header. {@code idOrSlug} takes either, matching the
     * product route.
     *
     * <p>The seller's PRODUCTS are not returned here - the grid is
     * {@code GET /api/marketplace/catalog?sellerId=...}, which reuses every filter,
     * sort and pagination rule the main grid already has rather than growing a second,
     * subtly different catalog endpoint that would drift from it.
     */
    @GetMapping("/sellers/{idOrSlug}")
    public MarketplaceSellerResponse seller(@PathVariable String idOrSlug) {
        return marketplaceCatalogService.seller(idOrSlug);
    }

    /** {@code idOrSlug} takes either - storefront links carry the slug, the cart carries the id. */
    @GetMapping("/catalog/{idOrSlug}")
    public MarketplaceProductResponse product(@PathVariable String idOrSlug) {
        return marketplaceCatalogService.get(idOrSlug);
    }

    @GetMapping("/catalog/{idOrSlug}/related")
    public List<MarketplaceProductResponse> related(
            @PathVariable String idOrSlug, @RequestParam(required = false) Integer limit) {
        return marketplaceCatalogService.related(idOrSlug, limit);
    }

    @GetMapping("/categories")
    public List<MarketplaceCategoryResponse> categories() {
        return marketplaceCatalogService.categories();
    }

    /** Public subset only - see PublicMarketplaceSettingsResponse for what is withheld and why. */
    @GetMapping("/settings")
    public PublicMarketplaceSettingsResponse settings() {
        return marketplaceCatalogService.settings();
    }
}
