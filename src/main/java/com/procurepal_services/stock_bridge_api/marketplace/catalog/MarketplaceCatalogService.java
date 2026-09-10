package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import com.procurepal_services.stock_bridge_api.marketplace.SellerDirectory;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceCategoryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceSellerResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.PublicMarketplaceSettingsResponse;
import com.procurepal_services.stock_bridge_api.order.CatalogStockService;
import com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductCategoryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public storefront catalog. Read-only, unauthenticated, and therefore written
 * defensively.
 *
 * <h2>The one rule this class exists to keep</h2>
 * Its callers have no TenantContext, so the Hibernate tenant filter is OFF for the whole
 * request (see the PERMIT_ALL_PATHS comment in SecurityConfig). Nothing scopes these
 * queries except the predicates built here. Every read therefore goes through
 * {@link MarketplaceProductSpecifications}, which pins client_id to the ACTIVE SELLERS
 * alongside is_marketplace_listed, is_active and approval_status - never through a bare
 * repository findById, and never through TenantScopedRepository, whose "current tenant"
 * is nobody here.
 *
 * <h2>Several sellers now, and the shape that did not change</h2>
 * This class used to resolve one id - the platform owner's - and pin every query to it.
 * It now resolves a SET from {@link SellerDirectory}: the platform owner plus every
 * active vendor. The pin itself is unchanged in kind, and that is the point. The
 * tempting simplification when a filter stops naming a single value is to delete it;
 * here that would turn the storefront into a cross-tenant product browser, because
 * every buying company on the platform keeps its private inventory in the same table.
 * An empty seller set therefore yields an empty catalog, never an unfiltered one.
 *
 * <h2>What a buyer learns about a seller</h2>
 * Name, slug and logo, via {@link MarketplaceSellerResponse}. Not the email, phone or
 * address on the same clients row. A vendor agreed to sell on a marketplace, not to
 * publish their contact details to anonymous visitors, and the storefront has no
 * feature that needs them - see that record for the full allowlist argument.
 *
 * <h2>Why an unseeded marketplace is an empty catalog, not an error</h2>
 * A production database on day one has no platform owner. The storefront is the
 * application's front door, so answering it with a 500 would make the whole product look
 * broken to the first visitor. Every method degrades to empty/absent instead, which is
 * also the honest answer: there is nothing for sale yet.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceCatalogService {

    /**
     * Cap on {@code ?ids=}. Generous enough for any realistic B2B cart, small enough that
     * a scripted caller cannot use the batch form to page the entire catalog in one query
     * or to build a giant IN list.
     */
    static final int MAX_BATCH_IDS = 100;

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RELATED = 12;
    private static final int DEFAULT_RELATED = 4;

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final MarketplaceSettingsRepository marketplaceSettingsRepository;
    private final SellerDirectory sellerDirectory;
    private final CatalogStockService catalogStockService;

    /**
     * @param sellerId optional "only this seller", for the storefront's seller filter
     *     and the per-vendor storefront page. It NARROWS the active-seller pin rather
     *     than replacing it, so naming a suspended vendor or an ordinary buying company
     *     returns an empty grid rather than their inventory.
     */
    @Transactional(readOnly = true)
    public Page<MarketplaceProductResponse> browse(
            String query,
            UUID categoryId,
            UUID sellerId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            boolean inStockOnly,
            CatalogSort sort,
            int page,
            int size) {
        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        Pageable pageable = catalogPageable(page, size);
        if (sellerIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // Needed for two different jobs, and skipped when neither applies: inStockOnly
        // uses it as an exclusion predicate, and RELEVANCE uses it to sort sellable
        // products first. A price- or name-sorted grid with no stock filter never asks.
        Set<UUID> fullyCommitted = inStockOnly || sort == CatalogSort.RELEVANCE
                ? catalogStockService.fullyCommittedProductIds(sellerIds)
                : Set.of();

        Page<Product> results = productRepository.findAll(
                MarketplaceProductSpecifications.publicCatalog(
                        sellerIds,
                        sellerId,
                        query,
                        categoryId,
                        minPrice,
                        maxPrice,
                        inStockOnly,
                        fullyCommitted,
                        sort),
                pageable);
        return withSellableStock(results);
    }

    /**
     * The sellers a buyer may browse: ProcurePal and every active vendor, each with the
     * number of products actually listed and approved under them.
     *
     * <p>Sellers with nothing live are dropped for the same reason empty categories are:
     * clicking through to an empty storefront reads as a broken site. A vendor who has
     * been onboarded but whose first listings are still in moderation is therefore
     * invisible to buyers, which is correct - there is nothing to buy from them yet.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceSellerResponse> sellers() {
        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        List<MarketplaceSellerResponse> visible = new ArrayList<>();
        for (Client seller : sellerDirectory.activeSellers()) {
            long count = countListedFor(sellerIds, seller.getId());
            if (count > 0) {
                visible.add(MarketplaceSellerResponse.from(seller, count));
            }
        }
        return visible;
    }

    /**
     * One seller's storefront header. 404s for a client that is not an active seller,
     * so a buying company's id in the URL cannot be used to confirm that company exists
     * or read its name.
     */
    @Transactional(readOnly = true)
    public MarketplaceSellerResponse seller(String idOrSlug) {
        Client seller = sellerDirectory.findActiveSeller(idOrSlug).orElseThrow(SellerNotFoundException::new);
        return MarketplaceSellerResponse.from(
                seller, countListedFor(sellerDirectory.activeSellerIds(), seller.getId()));
    }

    private long countListedFor(Set<UUID> sellerIds, UUID sellerId) {
        return productRepository.count(MarketplaceProductSpecifications.publicCatalog(
                sellerIds, sellerId, null, null, null, null, false, Set.of(), CatalogSort.NAME_ASC));
    }

    /**
     * Batch lookup behind the same path as {@link #browse}, for the anonymous cart.
     *
     * The anonymous cart holds only {productId, quantity} in localStorage and re-fetches
     * prices on every render (contract §8, so a cached price can never be quoted), which
     * previously meant one HTTP round trip per line. This collapses that to one.
     *
     * Unknown, unlisted and foreign ids are dropped rather than rejected: a cart is a
     * client-side artefact that legitimately outlives the catalog, and 400-ing the whole
     * request would leave a shopper with a cart they cannot open. The caller sees which
     * ids survived and prunes accordingly.
     *
     * Returns a Page, not a List, so this endpoint has one response shape regardless of
     * which query parameters were used - the frontend's PageResponse&lt;MarketplaceProduct&gt;
     * keeps working and axios needs no per-call generic.
     */
    @Transactional(readOnly = true)
    public Page<MarketplaceProductResponse> findByIds(List<UUID> ids) {
        // LinkedHashSet: de-duplicate (a malformed cart can repeat a line) while keeping
        // the cap meaningful against distinct rows rather than repeated text.
        Set<UUID> distinct = new LinkedHashSet<>(ids);
        List<UUID> capped =
                distinct.stream().limit(MAX_BATCH_IDS).toList();

        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        if (sellerIds.isEmpty() || capped.isEmpty()) {
            return singlePage(List.of());
        }

        List<Product> found =
                productRepository.findAll(MarketplaceProductSpecifications.publicCatalogByIds(sellerIds, capped));
        return singlePage(toResponses(found));
    }

    /**
     * {@code idOrSlug} accepts either, because the storefront links by slug for SEO and
     * shareability while the cart and order history hold ids. Parse-then-branch rather
     * than try-both: a value that parses as a UUID is an id, full stop, so a product can
     * never be shadowed by another product whose slug happens to look like a UUID.
     */
    @Transactional(readOnly = true)
    public MarketplaceProductResponse get(String idOrSlug) {
        // Through the batch lookup even for one product, so the detail page can never
        // disagree with the grid tile the buyer clicked to reach it.
        return toResponses(List.of(findListedOrThrow(idOrSlug))).getFirst();
    }

    /**
     * Related products, heuristic and deliberately simple: same category, excluding the
     * product itself, listed and active, in-stock ones first (CatalogSort.RELEVANCE),
     * then alphabetical. An uncategorised product falls back to the rest of the catalog
     * on the same ordering rather than returning nothing, so the detail page never
     * renders an empty rail.
     *
     * No co-purchase or similarity signal: the order history that would power one is
     * being built in parallel, and a wrong "customers also bought" is worse than an
     * honest "more in Cooking Oils". Revisit once there is real order volume.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceProductResponse> related(String idOrSlug, Integer limit) {
        Product product = findListedOrThrow(idOrSlug);
        ProductCategory category = product.getCategory();
        int capped = Math.clamp(limit == null ? DEFAULT_RELATED : limit, 1, MAX_RELATED);

        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        return toResponses(productRepository
                .findAll(
                        MarketplaceProductSpecifications.related(
                                sellerIds,
                                category == null ? null : category.getId(),
                                product.getId(),
                                // Related products are RELEVANCE-ordered, so they need the
                                // same sellable-first signal the grid uses - otherwise the
                                // rail leads with something badged sold out.
                                catalogStockService.fullyCommittedProductIds(sellerIds)),
                        PageRequest.of(0, capped, Sort.unsorted()))
                .getContent());
    }

    /**
     * The storefront's category menu: active categories only, in the merchandising order
     * ProcurePal set, each with the number of products a buyer would actually find inside
     * it. Categories with nothing listed in them are dropped - clicking through to an
     * empty grid reads as a broken site, and this is cheaper than making every consumer
     * remember to filter.
     */
    @Transactional(readOnly = true)
    public List<MarketplaceCategoryResponse> categories() {
        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        if (sellerIds.isEmpty()) {
            return List.of();
        }

        // One COUNT per category rather than a single GROUP BY. Deliberate: a grouped
        // query would need a new method on ProductRepository (owned by M1 and edited by
        // several modules in parallel), and the category list is single-digit and
        // storefront-cacheable. If the category tree ever grows past a screenful, replace
        // this with one grouped query - the shape of the response does not change.
        List<MarketplaceCategoryResponse> visible = new ArrayList<>();
        for (ProductCategory category : productCategoryRepository.findAllByActiveTrueOrderBySortOrderAscNameAsc()) {
            long count = productRepository.count(MarketplaceProductSpecifications.publicCatalog(
                    sellerIds, null, null, category.getId(), null, null, false, Set.of(), CatalogSort.NAME_ASC));
            if (count > 0) {
                visible.add(MarketplaceCategoryResponse.from(category, count));
            }
        }
        return visible;
    }

    /**
     * Public settings. Unlike the catalog, an absent settings row is NOT degraded to a
     * default - see MarketplaceSettingsMissingException for why guessing a delivery fee
     * is the wrong failure.
     */
    @Transactional(readOnly = true)
    public PublicMarketplaceSettingsResponse settings() {
        return PublicMarketplaceSettingsResponse.from(
                marketplaceSettingsRepository.findBySingletonTrue().orElseThrow(MarketplaceSettingsMissingException::new));
    }

    /**
     * One listed product by id or slug, across every active seller.
     *
     * <p>Resolved through the same Specification the grid uses rather than through a
     * derived finder. That is a deliberate downgrade from the previous
     * {@code findByIdAndClientIdAndMarketplaceListedTrueAndActiveTrue}: with several
     * sellers the predicate is an IN over a set plus the approval gate, and expressing
     * it as a fifth hand-written finder would leave two definitions of "public" to keep
     * in step. There is one, and the detail page cannot disagree with the tile that led
     * to it.
     *
     * <p>Slugs are unique per tenant, not globally (products has a per-client unique
     * index on slug), so two sellers CAN legitimately hold the same slug. The id form
     * is unambiguous; the slug form takes the first match in the query's order. Flagged
     * rather than hidden: the storefront links by id-or-slug and this is the one place
     * the ambiguity can surface. Making slugs globally unique is a schema change and a
     * migration for existing rows, and it belongs to whoever adds vendor-authored slugs.
     */
    private Product findListedOrThrow(String idOrSlug) {
        Set<UUID> sellerIds = sellerDirectory.activeSellerIds();
        if (sellerIds.isEmpty()) {
            throw new CatalogProductNotFoundException();
        }
        Optional<UUID> id = asUuid(idOrSlug);

        Specification<Product> specification = id.isPresent()
                ? MarketplaceProductSpecifications.publicCatalogByIds(sellerIds, List.of(id.get()))
                : MarketplaceProductSpecifications.publicCatalogBySlug(sellerIds, idOrSlug);

        return productRepository.findAll(specification, PageRequest.of(0, 1, Sort.unsorted())).stream()
                .findFirst()
                .orElseThrow(CatalogProductNotFoundException::new);
    }

    private Optional<UUID> asUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Sort.unsorted() is load-bearing: MarketplaceProductSpecifications sets the ORDER BY
     * itself (RELEVANCE needs a CASE expression), and a sorted Pageable would make Spring
     * Data overwrite it.
     */
    private Pageable catalogPageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), Sort.unsorted());
    }

    /**
     * The one place raw products become public DTOs, so every surface - grid, ?ids=,
     * detail, related - projects the SAME notion of stock. A product that reads sold out
     * on the grid and in stock on its detail page is worse than either answer being
     * wrong, and the only reliable way to prevent that is to leave exactly one conversion
     * path.
     *
     * One batch query per response regardless of page size. A product missing from the
     * map has no row in products (it cannot: it was just read from there) or, for the
     * empty-input case, was never asked about - zero is the safe reading either way.
     */
    private List<MarketplaceProductResponse> toResponses(List<Product> products) {
        Map<UUID, Integer> available =
                catalogStockService.availableToSell(products.stream().map(Product::getId).toList());
        // One seller lookup per response, not per row: the whole seller list is a
        // handful of rows and every product on the page resolves against the same map.
        Map<UUID, Client> sellers = sellerDirectory.activeSellersById();
        return products.stream()
                .map(product -> MarketplaceProductResponse.from(
                        product,
                        available.getOrDefault(product.getId(), 0),
                        MarketplaceSellerResponse.of(sellers.get(product.getClientId()))))
                .toList();
    }

    /** Keeps the Page's paging metadata while replacing its content with projected DTOs. */
    private Page<MarketplaceProductResponse> withSellableStock(Page<Product> results) {
        return new PageImpl<>(toResponses(results.getContent()), results.getPageable(), results.getTotalElements());
    }

    private Page<MarketplaceProductResponse> singlePage(List<MarketplaceProductResponse> content) {
        return new PageImpl<>(content, PageRequest.of(0, Math.max(content.size(), 1)), content.size());
    }
}
