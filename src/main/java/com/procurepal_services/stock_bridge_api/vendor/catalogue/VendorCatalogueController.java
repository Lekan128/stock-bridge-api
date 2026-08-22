package com.procurepal_services.stock_bridge_api.vendor.catalogue;

import com.procurepal_services.stock_bridge_api.marketplace.catalog.MarketplaceCatalogAdminService;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.AdminCatalogProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateListingRequest;
import com.procurepal_services.stock_bridge_api.vendor.VendorGuard;
import com.procurepal_services.stock_bridge_api.vendor.catalogue.dto.UpdateVendorMarketplaceDetailsRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A SELLER's own catalogue: what they have, whether it is up for sale, and where
 * each listing stands with moderation.
 *
 * <h2>Why this exists next to MarketplaceCatalogAdminController rather than inside it</h2>
 * That controller is ProcurePal's, and three of its concerns genuinely are: the
 * marketplace CATEGORIES are platform-wide taxonomy, and the marketplace SETTINGS
 * (delivery fee, free-delivery threshold, minimum order value, pay-on-delivery
 * rules) are one row governing every seller's checkout. Widening its
 * {@code requirePlatformOwner()} to {@code requireSeller()} would hand those to
 * every vendor - a vendor editing the platform's delivery fee, or deleting a
 * category ProcurePal merchandises by - which is not a scoping bug that shows up
 * in a test, it is the operator losing control of its own marketplace.
 *
 * <p>Only the PRODUCT half is per-seller, and that half is already written to be:
 * {@code MarketplaceCatalogAdminService}'s product methods take an owner id and
 * resolve every row through {@code MarketplaceProductSpecifications.ownedBy}, so
 * an id belonging to anyone else 404s. This controller therefore adds no logic at
 * all - it proves the caller may sell, and passes their own client id where the
 * platform owner's used to go. Duplicating the service would have been the
 * mistake: two places deriving slugs and flipping the same column drift within a
 * release.
 *
 * <h2>Two gates</h2>
 * {@code @PreAuthorize("hasAuthority('MANAGE_MARKETPLACE')")} proves the caller
 * does catalogue work - the VENDOR role holds it, and so does every tenant's
 * OWNER, which is why it proves nothing on its own.
 * {@link VendorGuard#requireSeller()} proves their company sells, and returns the
 * id that scopes every query. It is called in the handler rather than hidden in a
 * service so the check sits on the same screen as the route it protects, exactly
 * as the platform-owner controller does it - and its return value is load-bearing,
 * so deleting the line breaks the build rather than silently opening the endpoint.
 *
 * <h2>What a vendor still cannot do here</h2>
 * Create a product, or edit its name, price, image or SKU. That is
 * {@code /api/products}, which every tenant already has and which is where the
 * moderation stamp is applied - both at creation
 * ({@code ProductModerationRules.initialStatusFor}) and on an identity edit, which
 * is also the RESUBMISSION path for a rejected listing (see
 * {@code ProductModerationService.onListingContentChanged}: editing the listing IS
 * the resubmission, there is no separate button). Giving the same row two write
 * paths is how fields start disagreeing, which is the reason
 * MarketplaceCatalogAdminService gives for keeping name, price, image and SKU off
 * its own surface.
 *
 * <p>Nor may a vendor author a {@code slug}, which is the one field the
 * marketplace-details route below drops on its way to the shared service. The
 * reason is a real collision rather than a tidiness rule - see
 * {@link UpdateVendorMarketplaceDetailsRequest}.
 */
@RestController
@RequestMapping("/api/vendor/catalogue")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('MANAGE_MARKETPLACE')")
public class VendorCatalogueController {

    private final MarketplaceCatalogAdminService marketplaceCatalogAdminService;
    private final VendorGuard vendorGuard;

    /**
     * The seller's whole catalogue, listed and unlisted alike - finding the products
     * that are NOT yet up for sale is most of what this screen is for.
     *
     * <p>Each row carries its {@code approvalStatus} and, if refused, the reason:
     * see {@code AdminCatalogProductResponse}. Those two fields are why a vendor can
     * answer "my product is listed, why can nobody see it" without a support ticket.
     */
    @GetMapping("/products")
    public Page<AdminCatalogProductResponse> products(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean listed,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return marketplaceCatalogAdminService.listProducts(sellerId(), q, categoryId, listed, page, size);
    }

    /**
     * Put one product up for sale, or take it down.
     *
     * <p>This does not make it visible on its own and is not meant to: the public
     * catalogue requires the seller's flag AND an APPROVED moderation status, and
     * only one of those is the seller's to give. Listing a PENDING product is a
     * perfectly sensible thing for a vendor to do - it says "sell this the moment
     * you clear it" - which is why nothing here refuses it.
     */
    @PostMapping("/products/{id}/listing")
    public AdminCatalogProductResponse setListing(
            @PathVariable UUID id, @Valid @RequestBody UpdateListingRequest request) {
        return marketplaceCatalogAdminService.setListing(sellerId(), id, request);
    }

    /** Select-all listing from the catalogue table. Partial success by design - see BulkListingResponse. */
    @PostMapping("/products/bulk-listing")
    public BulkListingResponse bulkListing(@Valid @RequestBody BulkListingRequest request) {
        return marketplaceCatalogAdminService.bulkSetListing(sellerId(), request);
    }

    /**
     * Brand, unit of measure, filing and minimum order quantity, on the seller's own
     * product.
     *
     * <h2>Why a vendor needed this and a listing toggle was not enough</h2>
     * {@code unitOfMeasure} is how a B2B buyer reads a price at all: N32,000 means
     * nothing until it means N32,000 per 50kg bag rather than per carton or per litre.
     * Until this route existed no vendor-facing surface wrote it - {@code /api/products}
     * has never carried either field, and the operator's route pins its rows to
     * ProcurePal's own products with {@code ownedBy}, so it 404s a vendor's product even
     * for the operator. The product form said so and told vendors to contact support,
     * which was true and useless. A marketplace listing without a unit is close to
     * unusable for procurement, so this is the gap being closed.
     *
     * <h2>requireSeller(), not requireVendor()</h2>
     * Same rule as every other handler on this controller, and the reason bears
     * repeating because getting it wrong here is invisible: ProcurePal is a
     * {@link com.procurepal_services.stock_bridge_api.entity.ClientType#COMPANY} that
     * happens to own the platform, so {@code requireVendor()} would refuse the operator
     * on its own marketplace. ProcurePal does not in fact need this route - it keeps its
     * own at {@code /api/marketplace/admin/products/&#123;id&#125;/marketplace-details},
     * unchanged - but a seller surface that silently excludes one seller is a trap for
     * the next handler copied from this one.
     *
     * <h2>This sends the listing back for review, and does not decide that here</h2>
     * {@code brand} and {@code unitOfMeasure} are two of the six identity fields in
     * {@code ProductModerationRules.invalidatesApproval}: "Dangote, 50kg bag" becoming
     * "Generic, 25kg bag" at the same name and the same price is a different product to
     * a buyer, and that is precisely the approve-then-swap the moderation gate exists to
     * catch. The re-trigger is INHERITED, not re-implemented - the shared service calls
     * {@code onListingContentChanged} itself (the M6 fix), so this handler adds no
     * moderation logic and cannot drift from the operator's route. Category and minimum
     * order quantity are exempt on the same call, for the reasons on the service method.
     */
    @PutMapping("/products/{id}/marketplace-details")
    public AdminCatalogProductResponse updateMarketplaceDetails(
            @PathVariable UUID id, @Valid @RequestBody UpdateVendorMarketplaceDetailsRequest request) {
        return marketplaceCatalogAdminService.updateMarketplaceDetails(sellerId(), id, request.toCatalogRequest());
    }

    /**
     * The second gate and the scope in one call. Never a client id from a request:
     * the only thing that reaches the service is the id of the company the token
     * resolved to, re-read from the database on every request.
     */
    private UUID sellerId() {
        return vendorGuard.requireSeller().getId();
    }
}
