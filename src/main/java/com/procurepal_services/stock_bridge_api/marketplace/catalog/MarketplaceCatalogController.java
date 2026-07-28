package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceCategoryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceProductResponse;
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
 * ProcurePal's public shop window. Every path here is in SecurityConfig.PERMIT_ALL_PATHS
 * and is served to anonymous visitors, so there is no @PreAuthorize on anything - and,
 * critically, no tenant context either. See MarketplaceCatalogService for how these reads
 * are scoped without one.
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
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false, defaultValue = "false") boolean inStockOnly,
            @RequestParam(required = false, defaultValue = "RELEVANCE") CatalogSort sort,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        if (ids != null && !ids.isEmpty()) {
            return marketplaceCatalogService.findByIds(ids);
        }
        return marketplaceCatalogService.browse(q, categoryId, minPrice, maxPrice, inStockOnly, sort, page, size);
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
