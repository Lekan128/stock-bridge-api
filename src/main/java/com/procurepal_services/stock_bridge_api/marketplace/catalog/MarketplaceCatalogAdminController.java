package com.procurepal_services.stock_bridge_api.marketplace.catalog;

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
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ProcurePal's catalog administration.
 *
 * <h2>Two gates on every method, and why one is not enough</h2>
 * {@code @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE')")} proves the caller has the
 * right job. It does NOT prove they work for ProcurePal: MANAGE_MARKETPLACE hangs off the
 * global OWNER role, so every tenant's owner holds it, and a permission check alone would
 * hand the public storefront's catalog to any company that signed up this morning.
 * {@code platformOwnerGuard.requirePlatformOwner()} is the second, independent gate, and
 * it is called first in every handler.
 *
 * The guard is invoked here rather than hidden inside the service so that the check is
 * visible on the same screen as the route it protects - the failure mode being defended
 * against is somebody adding a seventh endpoint and forgetting, and a missing line is much
 * easier to spot in a list of identical ones. Its return value (the operator's client id)
 * is then threaded into the service, which is what scopes every product query - so the
 * guard is load-bearing, not decorative, and deleting it breaks the build rather than
 * silently opening the endpoint.
 */
@RestController
@RequestMapping("/api/marketplace/admin")
@PreAuthorize("hasAuthority('MANAGE_MARKETPLACE')")
@RequiredArgsConstructor
public class MarketplaceCatalogAdminController {

    private final MarketplaceCatalogAdminService marketplaceCatalogAdminService;
    private final PlatformOwnerGuard platformOwnerGuard;

    // ------------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------------

    @GetMapping("/products")
    public Page<AdminCatalogProductResponse> products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean listed,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return marketplaceCatalogAdminService.listProducts(operatorId(), q, categoryId, listed, page, size);
    }

    @PostMapping("/products/{id}/listing")
    public AdminCatalogProductResponse setListing(
            @PathVariable UUID id, @Valid @RequestBody UpdateListingRequest request) {
        return marketplaceCatalogAdminService.setListing(operatorId(), id, request);
    }

    @PostMapping("/products/bulk-listing")
    public BulkListingResponse bulkListing(@Valid @RequestBody BulkListingRequest request) {
        return marketplaceCatalogAdminService.bulkSetListing(operatorId(), request);
    }

    @PutMapping("/products/{id}/marketplace-details")
    public AdminCatalogProductResponse updateMarketplaceDetails(
            @PathVariable UUID id, @Valid @RequestBody UpdateMarketplaceDetailsRequest request) {
        return marketplaceCatalogAdminService.updateMarketplaceDetails(operatorId(), id, request);
    }

    // ------------------------------------------------------------------------
    // Categories - global rows, so the guard is the only thing protecting them.
    // ------------------------------------------------------------------------

    @GetMapping("/categories")
    public List<MarketplaceCategoryResponse> categories() {
        platformOwnerGuard.requirePlatformOwner();
        return marketplaceCatalogAdminService.listCategories();
    }

    @PostMapping("/categories")
    public ResponseEntity<MarketplaceCategoryResponse> createCategory(
            @Valid @RequestBody CreateCategoryRequest request) {
        platformOwnerGuard.requirePlatformOwner();
        return ResponseEntity.status(HttpStatus.CREATED).body(marketplaceCatalogAdminService.createCategory(request));
    }

    @PutMapping("/categories/{id}")
    public MarketplaceCategoryResponse updateCategory(
            @PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        platformOwnerGuard.requirePlatformOwner();
        return marketplaceCatalogAdminService.updateCategory(id, request);
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        platformOwnerGuard.requirePlatformOwner();
        marketplaceCatalogAdminService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------
    // Commercial settings
    // ------------------------------------------------------------------------

    @GetMapping("/settings")
    public AdminMarketplaceSettingsResponse settings() {
        platformOwnerGuard.requirePlatformOwner();
        return marketplaceCatalogAdminService.getSettings();
    }

    @PutMapping("/settings")
    public AdminMarketplaceSettingsResponse updateSettings(
            @Valid @RequestBody UpdateMarketplaceSettingsRequest request) {
        platformOwnerGuard.requirePlatformOwner();
        return marketplaceCatalogAdminService.updateSettings(request);
    }

    /** Throws 403 for anyone who is not ProcurePal, and yields the client id every product query is pinned to. */
    private UUID operatorId() {
        return platformOwnerGuard.requirePlatformOwner().getId();
    }
}
