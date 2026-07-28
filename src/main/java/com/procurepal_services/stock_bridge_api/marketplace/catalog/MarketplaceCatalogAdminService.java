package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import com.procurepal_services.stock_bridge_api.marketplace.PlatformOwnerGuard;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.AdminCatalogProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.AdminMarketplaceSettingsResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.CreateCategoryRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceCategoryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateCategoryRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceDetailsRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceSettingsRequest;
import com.procurepal_services.stock_bridge_api.order.CatalogStockService;
import com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductCategoryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProcurePal's catalog administration: what is for sale, how it is filed, and the
 * commercial rules around it.
 *
 * <h2>The invariant this class owns</h2>
 * {@code is_marketplace_listed = TRUE} may only ever appear on the platform owner's own
 * products. That cannot be a CHECK constraint - expressing it needs a join to
 * clients.is_platform_owner - so it is enforced here twice over: the caller must be the
 * platform owner (PlatformOwnerGuard, applied in the controller), AND every product this
 * class touches is resolved through {@link MarketplaceProductSpecifications#ownedBy},
 * which pins client_id to that same platform owner. Neither check alone is enough: the
 * first would still let ProcurePal list a product id it does not own, and the second
 * would still let any tenant list their own products if the guard were removed.
 *
 * <h2>Categories and settings are global, not tenant-scoped</h2>
 * There is one marketplace, so there is one category tree and one settings row. Both are
 * therefore protected only by the platform-owner guard - there is no tenant filter behind
 * these writes to catch a mistake.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceCatalogAdminService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final MarketplaceSettingsRepository marketplaceSettingsRepository;
    private final PlatformOwnerGuard platformOwnerGuard;
    private final CatalogStockService catalogStockService;

    // ------------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<AdminCatalogProductResponse> listProducts(
            UUID operatorId, String query, UUID categoryId, Boolean listed, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE),
                // Alphabetical, not newest-first: this is a working list an operator
                // scans and re-scans for a known product name, not a feed.
                Sort.by(Sort.Order.asc("name").ignoreCase()));

        Page<Product> results = productRepository.findAll(
                MarketplaceProductSpecifications.adminCatalog(operatorId, query, categoryId, listed), pageable);
        // One commitment query for the whole page - see AdminCatalogProductResponse for
        // why this screen shows physical, committed and sellable stock rather than one
        // number.
        Map<UUID, Integer> committed = catalogStockService.committedQuantities(
                results.getContent().stream().map(Product::getId).toList());
        return new PageImpl<>(
                results.getContent().stream()
                        .map(product -> AdminCatalogProductResponse.from(
                                product, committed.getOrDefault(product.getId(), 0)))
                        .toList(),
                results.getPageable(),
                results.getTotalElements());
    }

    /**
     * List or unlist a single product.
     *
     * Listing auto-fills a slug when the product has none, because the storefront routes
     * by slug ({@code /product/:idOrSlug}) and a listed product without one is a catalog
     * entry that cannot be linked to. Unlisting leaves the slug alone so the same URL
     * comes back if it is re-listed.
     */
    @Transactional
    public AdminCatalogProductResponse setListing(UUID operatorId, UUID productId, UpdateListingRequest request) {
        Product product = findOwnedOrThrow(operatorId, productId);

        if (Boolean.TRUE.equals(request.listed())) {
            if (product.getSlug() == null || product.getSlug().isBlank()) {
                product.setSlug(deriveSlug(operatorId, product.getName(), productId));
            }
            product.setMarketplaceListed(true);
        } else {
            product.setMarketplaceListed(false);
        }
        return toResponse(product);
    }

    /**
     * Select-all listing from the admin table. Partial success by design - see
     * BulkListingResponse.
     */
    @Transactional
    public BulkListingResponse bulkSetListing(UUID operatorId, BulkListingRequest request) {
        boolean listed = Boolean.TRUE.equals(request.listed());
        int updated = 0;
        int alreadyInState = 0;
        List<UUID> skipped = new ArrayList<>();

        for (UUID productId : new LinkedHashSet<>(request.productIds())) {
            Product product = productRepository
                    .findOne(MarketplaceProductSpecifications.ownedBy(operatorId, productId))
                    .orElse(null);
            if (product == null) {
                // Not ProcurePal's, or gone. Reported, never silently treated as done.
                skipped.add(productId);
                continue;
            }
            if (product.isMarketplaceListed() == listed) {
                alreadyInState++;
                continue;
            }
            if (listed && (product.getSlug() == null || product.getSlug().isBlank())) {
                product.setSlug(deriveSlug(operatorId, product.getName(), productId));
            }
            product.setMarketplaceListed(listed);
            updated++;
        }
        return new BulkListingResponse(updated, alreadyInState, skipped);
    }

    /**
     * The marketplace-only facets of a product. Name, price, image and SKU are not
     * editable here on purpose - they belong to /api/products, and giving the same row two
     * write paths is how fields start disagreeing.
     */
    @Transactional
    public AdminCatalogProductResponse updateMarketplaceDetails(
            UUID operatorId, UUID productId, UpdateMarketplaceDetailsRequest request) {
        Product product = findOwnedOrThrow(operatorId, productId);

        if (Boolean.TRUE.equals(request.clearCategory())) {
            product.setCategory(null);
        } else if (request.categoryId() != null) {
            product.setCategory(productCategoryRepository
                    .findById(request.categoryId())
                    .orElseThrow(CategoryNotFoundException::new));
        }
        if (request.unitOfMeasure() != null) {
            product.setUnitOfMeasure(blankToNull(request.unitOfMeasure()));
        }
        if (request.minOrderQuantity() != null) {
            product.setMinOrderQuantity(request.minOrderQuantity());
        }
        if (request.brand() != null) {
            product.setBrand(blankToNull(request.brand()));
        }
        if (request.slug() != null) {
            applySlug(operatorId, product, request.slug());
        }
        return toResponse(product);
    }

    // ------------------------------------------------------------------------
    // Categories
    // ------------------------------------------------------------------------

    /** Admin view: inactive categories are still manageable, and empty ones are still shown. */
    @Transactional(readOnly = true)
    public List<MarketplaceCategoryResponse> listCategories() {
        return productCategoryRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(category -> MarketplaceCategoryResponse.from(category, countProductsInCategory(category.getId())))
                .toList();
    }

    @Transactional
    public MarketplaceCategoryResponse createCategory(CreateCategoryRequest request) {
        ProductCategory category = ProductCategory.builder()
                .name(request.name().trim())
                .slug(resolveCategorySlug(request.slug(), request.name(), null))
                .parent(resolveParent(request.parentId(), null))
                .sortOrder(request.sortOrder() == null ? 0 : request.sortOrder())
                .active(request.active() == null || request.active())
                .build();

        return MarketplaceCategoryResponse.from(productCategoryRepository.save(category), 0);
    }

    @Transactional
    public MarketplaceCategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        ProductCategory category =
                productCategoryRepository.findById(id).orElseThrow(CategoryNotFoundException::new);

        if (request.name() != null && !request.name().isBlank()) {
            category.setName(request.name().trim());
        }
        if (request.slug() != null) {
            category.setSlug(resolveCategorySlug(request.slug(), category.getName(), id));
        }
        if (Boolean.TRUE.equals(request.clearParent())) {
            category.setParent(null);
        } else if (request.parentId() != null) {
            category.setParent(resolveParent(request.parentId(), id));
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.active() != null) {
            // Deactivating hides the category from the storefront MENU only; the products
            // filed under it stay listed and findable by search. Delisting a dozen
            // products as a side effect of tidying the menu would be a merchandising
            // accident nobody asked for - unlisting is a per-product decision.
            category.setActive(request.active());
        }
        return MarketplaceCategoryResponse.from(category, countProductsInCategory(id));
    }

    /**
     * Blocked while anything still references the category, in either direction: products
     * filed under it, or child categories parented to it.
     *
     * Both FKs are ON DELETE SET NULL, so the database would accept the delete and quietly
     * detach everything - see CategoryInUseException for why that is the wrong default.
     * The message names the count so the operator knows what they are being asked to do
     * first.
     */
    @Transactional
    public void deleteCategory(UUID id) {
        ProductCategory category =
                productCategoryRepository.findById(id).orElseThrow(CategoryNotFoundException::new);

        long children = productCategoryRepository.countByParentId(id);
        if (children > 0) {
            throw new CategoryInUseException("'" + category.getName() + "' still has " + children
                    + " sub-categor" + (children == 1 ? "y" : "ies") + ". Move or delete them first.");
        }

        long products = countProductsInCategory(id);
        if (products > 0) {
            throw new CategoryInUseException("'" + category.getName() + "' still has " + products + " product"
                    + (products == 1 ? "" : "s") + " in it. Re-file them, or deactivate the category instead"
                    + " to take it off the storefront menu.");
        }

        productCategoryRepository.delete(category);
    }

    // ------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminMarketplaceSettingsResponse getSettings() {
        return AdminMarketplaceSettingsResponse.from(requireSettings());
    }

    @Transactional
    public AdminMarketplaceSettingsResponse updateSettings(UpdateMarketplaceSettingsRequest request) {
        validateSettings(request);

        MarketplaceSettings settings = requireSettings();
        settings.setDeliveryFee(request.deliveryFee());
        settings.setFreeDeliveryThreshold(request.freeDeliveryThreshold());
        settings.setMinimumOrderValue(request.minimumOrderValue());
        settings.setPayOnDeliveryEnabled(Boolean.TRUE.equals(request.payOnDeliveryEnabled()));
        settings.setPayOnDeliveryMaxOrderValue(request.payOnDeliveryMaxOrderValue());
        settings.setSupportPhone(blankToNull(request.supportPhone()));
        settings.setSupportEmail(blankToNull(request.supportEmail()));
        return AdminMarketplaceSettingsResponse.from(settings);
    }

    /**
     * The checks bean validation cannot make, because each one reads two fields at once.
     * All of them describe a configuration that is internally contradictory rather than
     * merely unusual - a free-delivery threshold below the minimum order value, say, is
     * odd but is a legitimate "delivery is always free" setting, so it is allowed.
     */
    private void validateSettings(UpdateMarketplaceSettingsRequest request) {
        if (Boolean.TRUE.equals(request.payOnDeliveryEnabled())) {
            if (request.payOnDeliveryMaxOrderValue().signum() <= 0) {
                throw new InvalidMarketplaceSettingsException(
                        "Pay on delivery is enabled, so its maximum order value must be greater than zero.");
            }
            if (request.payOnDeliveryMaxOrderValue().compareTo(request.minimumOrderValue()) < 0) {
                throw new InvalidMarketplaceSettingsException(
                        "The pay-on-delivery limit is below the minimum order value, so no order could ever"
                                + " qualify for it.");
            }
        }
        if (request.deliveryFee().signum() == 0 && request.freeDeliveryThreshold().signum() > 0) {
            throw new InvalidMarketplaceSettingsException(
                    "Delivery is already free for every order, so a free-delivery threshold would never apply."
                            + " Set the threshold to zero.");
        }
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /** Single-product form of the page projection above; still one query, just for one id. */
    private AdminCatalogProductResponse toResponse(Product product) {
        return AdminCatalogProductResponse.from(
                product,
                catalogStockService
                        .committedQuantities(List.of(product.getId()))
                        .getOrDefault(product.getId(), 0));
    }

    private Product findOwnedOrThrow(UUID operatorId, UUID productId) {
        return productRepository
                .findOne(MarketplaceProductSpecifications.ownedBy(operatorId, productId))
                .orElseThrow(CatalogProductNotFoundException::new);
    }

    /**
     * Categories are global, so "is anything still in this one" has to be answered across
     * every tenant - a buyer's own inventory row copied from the catalog carries the
     * category too. Under ProcurePal's tenant filter that count would come back as only
     * ProcurePal's own products, and the delete would look safe while orphaning a
     * customer's rows. readAcrossTenants is the guarded, filter-lifting way to ask; see
     * PlatformOwnerGuard for why nothing else may disable the filter by hand.
     */
    private long countProductsInCategory(UUID categoryId) {
        return platformOwnerGuard.readAcrossTenants(
                () -> productRepository.count(MarketplaceProductSpecifications.inCategory(categoryId)));
    }

    private MarketplaceSettings requireSettings() {
        return marketplaceSettingsRepository.findBySingletonTrue().orElseThrow(MarketplaceSettingsMissingException::new);
    }

    /**
     * An explicitly supplied product slug collides loudly; an omitted one is derived from
     * the name and disambiguated silently. Sending {@code ""} means "regenerate from the
     * name", which is what a cleared field in the admin form should do.
     */
    private void applySlug(UUID operatorId, Product product, String requested) {
        String normalized = Slugs.normalize(requested);
        if (normalized == null) {
            product.setSlug(deriveSlug(operatorId, product.getName(), product.getId()));
            return;
        }
        if (productSlugTaken(operatorId, product.getId(), normalized)) {
            throw new ProductSlugTakenException(normalized);
        }
        product.setSlug(normalized);
    }

    /**
     * Falls back to the SKU when the name has nothing URL-safe in it (a name written
     * entirely in a non-Latin script), and to the product id as a last resort, so a listed
     * product always has a working storefront URL.
     */
    private String deriveSlug(UUID operatorId, String name, UUID productId) {
        String base = Slugs.normalize(name);
        if (base == null) {
            base = "product-" + productId.toString().substring(0, 8);
        }
        return Slugs.uniquify(base, slug -> productSlugTaken(operatorId, productId, slug));
    }

    private boolean productSlugTaken(UUID operatorId, UUID excludingProductId, String slug) {
        return productRepository
                .findByClientIdAndSlug(operatorId, slug)
                .filter(existing -> !existing.getId().equals(excludingProductId))
                .isPresent();
    }

    /** Category slugs are globally unique (one shared catalog), so no client scoping here. */
    private String resolveCategorySlug(String requested, String name, UUID excludingCategoryId) {
        String normalized = Slugs.normalize(requested);
        if (normalized != null) {
            if (categorySlugTaken(normalized, excludingCategoryId)) {
                throw new CategorySlugTakenException(normalized);
            }
            return normalized;
        }

        String base = Slugs.normalize(name);
        if (base == null) {
            base = "category";
        }
        return Slugs.uniquify(base, slug -> categorySlugTaken(slug, excludingCategoryId));
    }

    private boolean categorySlugTaken(String slug, UUID excludingCategoryId) {
        return productCategoryRepository
                .findBySlug(slug)
                .filter(existing -> !existing.getId().equals(excludingCategoryId))
                .isPresent();
    }

    /**
     * A category cannot be its own parent. Deeper cycles are not reachable: the contract
     * fixes the tree at one level of nesting, and nothing here creates a grandchild.
     */
    private ProductCategory resolveParent(UUID parentId, UUID selfId) {
        if (parentId == null) {
            return null;
        }
        if (parentId.equals(selfId)) {
            throw new InvalidCategoryException("A category cannot be its own parent.");
        }
        return productCategoryRepository.findById(parentId).orElseThrow(CategoryNotFoundException::new);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
