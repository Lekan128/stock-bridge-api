package com.procurepal_services.stock_bridge_api.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.MarketplaceCatalogAdminService;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceDetailsRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockAdjustmentRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.vendor.catalogue.dto.UpdateVendorMarketplaceDetailsRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * One test per write path that can reach a product row, proving each either re-triggers
 * moderation or is deliberately exempt.
 *
 * <h2>Why a test per path rather than a test per rule</h2>
 * {@code ProductModerationRules.invalidatesApproval} was already correct and already
 * tested by its callers. The M6 bug was not a wrong rule, it was a path that never asked:
 * {@code MarketplaceCatalogAdminService.updateMarketplaceDetails} wrote {@code brand} and
 * {@code unitOfMeasure} - two fields the rule names - and never called
 * {@code onListingContentChanged}. A test suite organised around the rule would have
 * passed throughout. So this one is organised around the PATHS, matching the audit table
 * in {@code ProductModerationRules}, and a new write path with no row here is a gap this
 * file is supposed to make visible.
 *
 * <h2>The M6 hole was latent; M8 made it live, and these tests moved with it</h2>
 * When M6 fixed {@code updateMarketplaceDetails}, the method was mounted only under
 * {@code /api/marketplace/admin/**}, behind {@code requirePlatformOwner()}, with its rows
 * pinned to the caller by {@code ownedBy} - so the only account that could reach it over
 * HTTP was ProcurePal, editing ProcurePal's products, which moderation deliberately
 * ignores. The fix was therefore tested against the SEAM rather than a route: two tests
 * called the service directly with a vendor's id, which is what a vendor controller would
 * one day do. Testing only what was routable would have let the fix rot until the route
 * appeared.
 *
 * <p>M8 added that route -
 * {@code PUT /api/vendor/catalogue/products/&#123;id&#125;/marketplace-details}, behind
 * {@code requireSeller()} - so the seam is now a real request a real vendor makes, and
 * those two tests go over HTTP as a vendor instead. That is also what replaced this file's
 * tripwire: it used to assert the vendor route did NOT exist, precisely so that adding it
 * would fail here and send the author to the audit table in {@code ProductModerationRules}.
 * It did its job. Deleting it and asserting nothing would have thrown away the coverage the
 * tripwire was standing in for, so what stands in its place is the positive version of the
 * same claim - the route exists, and a vendor's brand edit through it costs them their
 * approval. {@link #procurePalsOwnListingIsNeverSentForReviewOverHttp} still covers the
 * operator's own route, which M8 left alone.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration test here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductModerationWritePathIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    /** Names every row this class creates, so @AfterEach removes exactly those. */
    private static final String FIXTURE_PREFIX = "PPMODPATH-";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MarketplaceCatalogAdminService marketplaceCatalogAdminService;

    private UUID vendorClientId;
    private HttpHeaders vendorHeaders;
    /** A vendor listing that has already been APPROVED - the state approve-then-swap attacks. */
    private UUID approvedProductId;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        TenantLoginResponse vendor = createVendor();
        vendorClientId = clientIdOf(vendor);
        vendorHeaders = authHeaders(vendor.tokens().accessToken());
        approvedProductId = createApprovedVendorProduct();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ---------------------------------------------------------------------------------
    // Path 1: PUT /api/products/{id} - the seller's ordinary edit form.
    // ---------------------------------------------------------------------------------

    @Test
    void aNameEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                "Something Else Entirely", null, null, null, null, null, null, null, null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
        assertThat(reviewedAtIsCleared(approvedProductId)).isTrue();
    }

    @Test
    void anSkuEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                null, FIXTURE_PREFIX + "SWAPPED", null, null, null, null, null, null, null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
    }

    @Test
    void aDescriptionEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                null, null, "Now claims to be something a reviewer never saw.",
                null, null, null, null, null, null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
    }

    /**
     * The photo swap. This is the sharpest version of approve-then-swap - get a plain
     * product cleared, then put a photo of something else on it - so it is checked through
     * the removal flag, which is the one image change a test can make without S3.
     */
    @Test
    void anImageRemovalSendsAnApprovedListingBackForReview() {
        jdbc.update("UPDATE products SET image_url = ? WHERE id = ?", "https://example.test/a.png", approvedProductId);

        updateProduct(new UpdateProductRequest(
                null, null, null, null, null, null, true, null, null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
    }

    /**
     * unitOfMeasure moved onto this same endpoint from the marketplace-details route (see
     * Path 2 below for what remains there), and re-triggering approval moved with it - this
     * is the M6/M8 rule applying to a field that did not even live here before. Uses a real
     * catalog code because this path validates against {@code UnitOfMeasure.fromCode} now,
     * unlike the old marketplace-details route which accepted any string.
     */
    @Test
    void aUnitOfMeasureEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                null, null, null, null, null, null, null, "KG", null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
        assertThat(reviewedAtIsCleared(approvedProductId)).isTrue();
    }

    /**
     * packagingSize (renamed from unitCount by V18) is new: a 50kg bag quietly becoming a
     * 25kg one at the same unit label and packaging is exactly the swap
     * {@link com.procurepal_services.stock_bridge_api.marketplace.moderation.ProductModerationRules}
     * exists to catch. unitOfMeasure and packagingUnit are left null here (not resent)
     * precisely to isolate packagingSize as the only thing changing - the fixture already has
     * a paired unitOfMeasure/packagingUnit, so this patch alone does not trip either pairing
     * rule.
     */
    @Test
    void aPackagingSizeEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                null, null, null, null, null, null, null, null, null, new BigDecimal("25.00")));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
    }

    /**
     * V18's third identity field: packagingUnit joins unitOfMeasure/packagingSize as
     * something that re-triggers moderation on its own - "Bag" quietly becoming "Carton" at
     * the same unitOfMeasure and the same packagingSize is a different product to a buyer
     * ("a 50kg bag" vs "a 50kg carton"), exactly the swap this rule exists to catch.
     * unitOfMeasure/packagingSize are left null here (not resent) to isolate packagingUnit as
     * the only thing changing - the fixture already pairs it with a packagingSize, so this
     * patch alone does not trip the pairing rule.
     */
    @Test
    void aPackagingUnitEditSendsAnApprovedListingBackForReview() {
        updateProduct(new UpdateProductRequest(
                null, null, null, null, null, null, null, null, "CARTON", null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
        assertThat(reviewedAtIsCleared(approvedProductId)).isTrue();
    }

    /**
     * The BigDecimal-equality decision made explicit: 50 and 50.00 are the same packaging
     * size by VALUE, and a client round-tripping a decimal through JSON tends to change its
     * scale without changing what it means - so resending it, even at a different scale than
     * what is stored, must not cost a seller their approval. See
     * {@code ProductModerationRules}'s private {@code changed(BigDecimal, BigDecimal)} for why
     * this uses {@code compareTo} rather than {@code equals}.
     */
    @Test
    void resendingTheSamePackagingSizeAtADifferentScaleIsNotAnEdit() {
        updateProduct(new UpdateProductRequest(
                null, null, null, null, null, null, null, null, null, new BigDecimal("50.0")));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    /**
     * The exemption the rule exists to protect. Section A calls price/stock quick edit a
     * MUST and Section C item 10 records stale stock as the biggest cause of cancellations
     * - a seller who has to wait for review before correcting a quantity stops correcting
     * quantities, which is worse for buyers than the risk being managed.
     */
    @Test
    void priceCostThresholdAndActiveEditsDoNotCostASellerItsApproval() {
        updateProduct(new UpdateProductRequest(
                null,
                null,
                null,
                new BigDecimal("12345.00"),
                7,
                true,
                null,
                null, null,
                null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    /** Sending a field back unchanged is not a change, so it must not re-moderate either. */
    @Test
    void resendingTheSameIdentityValuesIsNotAnEdit() {
        String name = jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, approvedProductId);

        updateProduct(new UpdateProductRequest(
                name, null, null, null, null, null, null, null, null, null));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    // ---------------------------------------------------------------------------------
    // Path 2: marketplace details - brand only now. THE M6 FIX; unitOfMeasure moved to Path 1.
    // ---------------------------------------------------------------------------------

    /**
     * The bug, now over the route a vendor actually uses. "Dangote" becoming "Generic" at
     * the same price and the same name is a different product to a buyer, and before M6
     * this write never asked moderation anything.
     *
     * <p>Asserted end to end rather than through the service, because M8's route is the
     * reason the M6 fix matters: this is a MODERATED seller, editing their OWN approved
     * listing, through a request they can make today. The controller contains no moderation
     * logic - it delegates - so what this really proves is that the delegation is intact.
     */
    @Test
    void aVendorsBrandEditSendsAnApprovedListingBackForReview() {
        assertThat(updateMarketplaceDetailsAsVendor(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, null, "A Different Brand Entirely")))
                .isEqualTo(HttpStatus.OK);

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
        assertThat(reviewedAtIsCleared(approvedProductId)).isTrue();
    }

    /**
     * unitOfMeasure used to be set on this same route, and the M6 fix originally re-triggered
     * moderation for it here too. It has since moved onto {@code /api/products} - see
     * {@code aUnitOfMeasureEditSendsAnApprovedListingBackForReview} in Path 1 above - and a
     * client still sending it here (a stale build, say) reaches nothing: the field is not on
     * {@code UpdateVendorMarketplaceDetailsRequest} anymore, so Jackson silently drops it
     * (Boot disables FAIL_ON_UNKNOWN_PROPERTIES) before the request even reaches the service.
     * Asserted with a raw map, the same technique {@code aVendorCannotAuthorASlugThroughTheirOwnRoute}
     * uses, because the record itself cannot express sending a field it no longer has.
     */
    @Test
    void unitOfMeasureIsNoLongerWritableThroughTheVendorMarketplaceDetailsRoute() {
        String before = jdbc.queryForObject(
                "SELECT unit_of_measure FROM products WHERE id = ?", String.class, approvedProductId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + approvedProductId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("brand", "Still Fine", "unitOfMeasure", "KG"), vendorHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                        "SELECT unit_of_measure FROM products WHERE id = ?", String.class, approvedProductId))
                .isEqualTo(before);
    }

    /** The admin route's copy of the same claim - see the vendor version above for the reasoning. */
    @Test
    void unitOfMeasureIsNoLongerWritableThroughTheAdminMarketplaceDetailsRoute() {
        HttpHeaders operator = authHeaders(loginAsPlatformOwner().tokens().accessToken());
        UUID productId = jdbc.queryForObject(
                "SELECT p.id FROM products p JOIN clients c ON c.id = p.client_id AND c.is_platform_owner "
                        + "WHERE p.is_marketplace_listed ORDER BY p.name LIMIT 1",
                UUID.class);
        String before =
                jdbc.queryForObject("SELECT unit_of_measure FROM products WHERE id = ?", String.class, productId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/marketplace/admin/products/" + productId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("unitOfMeasure", "KG"), operator),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT unit_of_measure FROM products WHERE id = ?", String.class, productId))
                .isEqualTo(before);
    }

    /**
     * The change really lands, and is not merely reported. Worth its own assertion because
     * every other test on this path reads {@code approval_status} - a route that re-triggered
     * review and then wrote nothing would pass all of them and ship a form that appears to
     * work.
     */
    @Test
    void aVendorsBrandActuallyReachesTheRow() {
        assertThat(updateMarketplaceDetailsAsVendor(new UpdateVendorMarketplaceDetailsRequest(null, null, 4, "Ada Mills")))
                .isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, approvedProductId))
                .isEqualTo("Ada Mills");
        assertThat(jdbc.queryForObject(
                        "SELECT min_order_quantity FROM products WHERE id = ?", Integer.class, approvedProductId))
                .isEqualTo(4);
    }

    /**
     * The documented exemptions on the same endpoint. Category is the operator's taxonomy,
     * minimum order quantity is a commercial term on the same footing as price, and slug is
     * a URL that follows the name - which re-moderates on its own path.
     */
    @Test
    void categoryMinimumOrderQuantityAndSlugEditsAreExemptOnTheSameEndpoint() {
        UUID categoryId = jdbc.queryForObject("SELECT id FROM product_categories LIMIT 1", UUID.class);

        marketplaceCatalogAdminService.updateMarketplaceDetails(
                vendorClientId,
                approvedProductId,
                new UpdateMarketplaceDetailsRequest(
                        categoryId, null, 12, null, FIXTURE_PREFIX.toLowerCase() + "renamed-link"));

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    /**
     * Sending brand back unchanged is not a change - same rule as the product form.
     *
     * <p>This is the one that would bite in practice rather than in theory. The vendor form
     * posts the WHOLE group on every save, so a vendor correcting a minimum order quantity
     * resends their existing brand alongside it. If "resent" counted as "changed", every save
     * from that screen would take the listing off the storefront, and the field grouping the
     * form is built around would be a lie.
     */
    @Test
    void resendingTheSameBrandIsNotAnEdit() {
        assertThat(updateMarketplaceDetailsAsVendor(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, null, "Fixture Brand")))
                .isEqualTo(HttpStatus.OK);

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    /**
     * The exemptions again, over the vendor's own route: filing a product and changing how
     * few of it a buyer may order are not claims about what it is, so neither costs a live
     * listing its place on the storefront.
     */
    @Test
    void categoryAndMinimumOrderQuantityAreExemptOverTheVendorRouteToo() {
        UUID categoryId = jdbc.queryForObject("SELECT id FROM product_categories LIMIT 1", UUID.class);

        assertThat(updateMarketplaceDetailsAsVendor(new UpdateVendorMarketplaceDetailsRequest(categoryId, null, 24, null)))
                .isEqualTo(HttpStatus.OK);

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
        assertThat(jdbc.queryForObject(
                        "SELECT min_order_quantity FROM products WHERE id = ?", Integer.class, approvedProductId))
                .isEqualTo(24);
    }

    /**
     * A vendor cannot author a slug, and the proof is that the field is not on their record
     * at all - so a body carrying one is ignored by Jackson (Boot disables
     * FAIL_ON_UNKNOWN_PROPERTIES) and the stored slug is untouched.
     *
     * <p>{@code products.slug} is unique per TENANT while the storefront resolves
     * {@code /product/:idOrSlug} across every seller, so a vendor-authored slug is a way to
     * aim at somebody else's URL. See {@code UpdateVendorMarketplaceDetailsRequest}. This is
     * asserted with a raw map rather than the record precisely because the record cannot
     * express the attack.
     */
    @Test
    void aVendorCannotAuthorASlugThroughTheirOwnRoute() {
        jdbc.update("UPDATE products SET slug = ? WHERE id = ?", FIXTURE_PREFIX.toLowerCase() + "own", approvedProductId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + approvedProductId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of("brand", "Still Fine", "slug", "dangote-cement"), vendorHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT slug FROM products WHERE id = ?", String.class, approvedProductId))
                .isEqualTo(FIXTURE_PREFIX.toLowerCase() + "own");
    }

    /**
     * The route as it actually exists today: ProcurePal, over HTTP, editing its own
     * listing's brand. Exempt because the platform owner is not moderated - a queue the
     * operator must clear before its own catalogue renders is a way to take the storefront
     * down by going on holiday.
     */
    @Test
    void procurePalsOwnListingIsNeverSentForReviewOverHttp() {
        HttpHeaders operator = authHeaders(loginAsPlatformOwner().tokens().accessToken());
        UUID productId = jdbc.queryForObject(
                "SELECT p.id FROM products p JOIN clients c ON c.id = p.client_id AND c.is_platform_owner "
                        + "WHERE p.is_marketplace_listed ORDER BY p.name LIMIT 1",
                UUID.class);
        String originalBrand =
                jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, productId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/marketplace/admin/products/" + productId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateMarketplaceDetailsRequest(null, null, null, FIXTURE_PREFIX + "Brand", null),
                        operator),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approvalStatusOf(productId)).isEqualTo(ProductApprovalStatus.APPROVED);

        jdbc.update("UPDATE products SET brand = ? WHERE id = ?", originalBrand, productId);
    }

    // ---------------------------------------------------------------------------------
    // Path 3: the vendor catalogue's listing toggles.
    // ---------------------------------------------------------------------------------

    /**
     * Exempt. Toggling "sell this" is not a claim about what it is, and the public
     * catalogue independently requires APPROVED - so nothing reaches a buyer early.
     * Re-moderating here would make the switch a punishment for using it.
     */
    @Test
    void listingAndUnlistingOverTheVendorCatalogueDoesNotCostApproval() {
        setListing(false);
        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);

        setListing(true);
        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    @Test
    void bulkListingOverTheVendorCatalogueDoesNotCostApproval() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/bulk-listing",
                HttpMethod.POST,
                new HttpEntity<>(new BulkListingRequest(List.of(approvedProductId), false), vendorHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    /**
     * What used to be the tripwire, turned the right way up.
     *
     * <p>Until M8 this asserted that {@code /api/vendor/catalogue/**} had no
     * marketplace-details route at all, so that adding one would fail here and send the
     * author to the audit table in {@code ProductModerationRules} rather than let a
     * moderated seller quietly gain a silent identity write. The route now exists, so the
     * assertion is the positive form of the same claim: it is mounted, a vendor may call it
     * on their own product, and the approval they had is gone when they do. The audit table
     * gained its row; this is the coverage the tripwire was holding the place for.
     *
     * <p>The listing flag is checked too, because "goes back for review" must not be
     * implemented by quietly unlisting: the seller's own {@code listed} choice is theirs and
     * the public catalogue predicate independently requires APPROVED, so nothing needs to
     * touch it. A vendor whose flag was flipped would have to remember to flip it back after
     * every approval.
     */
    @Test
    void theVendorCatalogueMarketplaceDetailsRouteReTriggersModeration() {
        assertThat(jdbc.queryForObject(
                        "SELECT is_marketplace_listed FROM products WHERE id = ?", Boolean.class, approvedProductId))
                .isTrue();

        assertThat(updateMarketplaceDetailsAsVendor(new UpdateVendorMarketplaceDetailsRequest(null, null, null, "Sneaky")))
                .isEqualTo(HttpStatus.OK);

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.PENDING);
        assertThat(reviewedAtIsCleared(approvedProductId)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT is_marketplace_listed FROM products WHERE id = ?", Boolean.class, approvedProductId))
                .as("re-review must not silently unlist a seller's product")
                .isTrue();
    }

    /**
     * The route is scoped to the caller, not merely to sellers. A second vendor's approved
     * listing is a 404 through it - {@code ownedBy} pins the row - and, the assertion that
     * matters, their approval is still intact afterwards. A cross-seller write on this route
     * would not look like an error: it would look like somebody else's listing quietly
     * dropping off the storefront.
     */
    @Test
    void aVendorCannotTouchAnotherSellersProductThroughTheRoute() {
        UUID otherVendorId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                otherVendorId,
                FIXTURE_PREFIX + "Other " + suffix,
                (FIXTURE_PREFIX + "other-" + suffix).toLowerCase(),
                "other-" + suffix + "@example.com");
        UUID theirProductId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, brand, "
                        + "unit_of_measure, is_active, is_marketplace_listed, approval_status, reviewed_at, "
                        + "min_order_quantity) "
                        + "VALUES (?, ?, ?, ?, 500.00, 10, 'Their Brand', '25kg bag', TRUE, TRUE, 'APPROVED', "
                        + "now(), 1)",
                theirProductId,
                otherVendorId,
                FIXTURE_PREFIX + "Their Rice",
                FIXTURE_PREFIX + suffix);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + theirProductId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, null, "Hijacked"),
                        vendorHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(approvalStatusOf(theirProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
        assertThat(jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, theirProductId))
                .isEqualTo("Their Brand");
    }

    /**
     * And a company that does not sell is refused outright, before ownership is even
     * consulted - 403 from {@code VendorGuard.requireSeller()}, not 404 from {@code ownedBy}.
     * The distinction is worth pinning: a buying company holds MANAGE_MARKETPLACE (every
     * tenant's OWNER does), so the {@code @PreAuthorize} alone lets them through the door.
     */
    @Test
    void aBuyingCompanyIsRefusedOnTheMarketplaceDetailsRoute() {
        TenantLoginResponse buyer = signup("No Details Here Ltd");
        assertThat(buyer).isNotNull();
        assertThat(buyer.user().permissions()).contains("MANAGE_MARKETPLACE");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + approvedProductId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, null, "Nope"),
                        authHeaders(buyer.tokens().accessToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    // ---------------------------------------------------------------------------------
    // Path 4: stock, and withdrawal.
    // ---------------------------------------------------------------------------------

    @Test
    void stockMovementsAndAdjustmentsDoNotCostApproval() {
        restTemplate.exchange(
                "/api/products/" + approvedProductId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(50, new BigDecimal("100.00"), null), vendorHeaders),
                String.class);
        restTemplate.exchange(
                "/api/products/" + approvedProductId + "/stock/adjustment",
                HttpMethod.POST,
                new HttpEntity<>(new StockAdjustmentRequest(12, "stocktake"), vendorHeaders),
                String.class);

        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    @Test
    void deactivatingAProductDoesNotCostApproval() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + approvedProductId, HttpMethod.DELETE, new HttpEntity<>(vendorHeaders), String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.NO_CONTENT);
        assertThat(approvalStatusOf(approvedProductId)).isEqualTo(ProductApprovalStatus.APPROVED);
    }

    // ---------------------------------------------------------------------------------
    // Path 5: creation, where there is no approval to invalidate.
    // ---------------------------------------------------------------------------------

    @Test
    void aVendorsNewProductStartsPendingAndABuyingCompanysIsNeverModerated() {
        ProductResponse vendorProduct = createProduct(vendorHeaders, FIXTURE_PREFIX + "NEW-VEND");
        assertThat(approvalStatusOf(vendorProduct.id())).isEqualTo(ProductApprovalStatus.PENDING);

        // A buying company's private stock list. approval_status is PENDING for it too -
        // the column defaults that way and moderation fails closed - but nothing ever reads
        // it, and editing it must never drag the company into a review queue. The assertion
        // that matters is that the edit succeeds and the row does not enter the queue.
        TenantLoginResponse buyer = signup("Moderation Path Buyer");
        HttpHeaders buyerHeaders = authHeaders(buyer.tokens().accessToken());
        ProductResponse buyerProduct = createProduct(buyerHeaders, FIXTURE_PREFIX + "NEW-BUYR");

        ResponseEntity<ProductResponse> renamed = restTemplate.exchange(
                "/api/products/" + buyerProduct.id(),
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                "Renamed Napkins", null, null, null, null, null, null, null, null, null),
                        buyerHeaders),
                ProductResponse.class);

        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(moderationQueueContains(buyerProduct.id())).isFalse();
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private TenantLoginResponse createVendor() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = (FIXTURE_PREFIX + suffix).toLowerCase();
        UUID clientId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                clientId,
                FIXTURE_PREFIX + suffix,
                slug,
                "vendor-" + suffix + "@example.com");

        String username = "vendor-" + suffix;
        jdbc.update(
                "INSERT INTO users (id, client_id, username, password_hash, role_id, is_active, is_root) "
                        + "VALUES (?, ?, ?, ?, (SELECT id FROM roles WHERE name = 'VENDOR'), TRUE, TRUE)",
                UUID.randomUUID(),
                clientId,
                username,
                passwordEncoder.encode(PASSWORD));

        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login).as("vendor fixture must be able to log in").isNotNull();
        return login;
    }

    /**
     * A vendor listing already cleared by a reviewer, with a brand, a unit of measure, a
     * packaging unit and a packaging size all set - the exact state approve-then-swap
     * targets, and the one the pre-M6 code let a seller change silently. unit_of_measure is
     * deliberately the raw free-text value products carried before the fixed catalog existed
     * ('50kg bag' rather than a code like 'KG') - this is a direct INSERT bypassing
     * ProductManagementService's validation entirely, same as every other fixture in this
     * class, and it stays untouched by tests that never write to it. packaging_unit/
     * packaging_size are BOTH set (rather than left null) so that a patch touching only ONE
     * field elsewhere on the row - name, brand, image, or just one of the pair itself (see
     * the unitOfMeasure/packagingUnit/packagingSize tests on Path 1) - never trips
     * PackagingUnitAndSizeRequiredTogetherException on the RESULTING state merely because the
     * fixture itself started out with one of the pair unset.
     */
    private UUID createApprovedVendorProduct() {
        UUID productId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, brand, "
                        + "unit_of_measure, packaging_unit, packaging_size, is_active, is_marketplace_listed, "
                        + "approval_status, reviewed_at, min_order_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'Fixture Brand', '50kg bag', 'BAG', 50.00, TRUE, TRUE, "
                        + "'APPROVED', now(), 1)",
                productId,
                vendorClientId,
                FIXTURE_PREFIX + "Approved Rice",
                FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8),
                new BigDecimal("10000.00"),
                100);
        return productId;
    }

    /**
     * Users before clients (FK), products before their owner (FK). Buying-company fixtures
     * are left behind deliberately - signup creates a whole tenant and the other suites do
     * the same - but their PRODUCTS carry the prefix and go.
     */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM stock_movements WHERE product_id IN (SELECT id FROM products WHERE sku LIKE ?)",
                FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update(
                "DELETE FROM users WHERE client_id IN (SELECT id FROM clients WHERE name LIKE ?)",
                FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM clients WHERE name LIKE ?", FIXTURE_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------
    // Callers and assertions
    // ---------------------------------------------------------------------------------

    private void updateProduct(UpdateProductRequest request) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products/" + approvedProductId,
                HttpMethod.PUT,
                multipart(request, vendorHeaders),
                ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** The vendor's own marketplace-details route, as a vendor. Returns the status so a caller can assert it. */
    private HttpStatus updateMarketplaceDetailsAsVendor(UpdateVendorMarketplaceDetailsRequest request) {
        return (HttpStatus) restTemplate
                .exchange(
                        "/api/vendor/catalogue/products/" + approvedProductId + "/marketplace-details",
                        HttpMethod.PUT,
                        new HttpEntity<>(request, vendorHeaders),
                        String.class)
                .getStatusCode();
    }

    private void setListing(boolean listed) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + approvedProductId + "/listing",
                HttpMethod.POST,
                new HttpEntity<>(new UpdateListingRequest(listed), vendorHeaders),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ProductResponse createProduct(HttpHeaders headers, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest(
                                "Product " + sku, sku, null, new BigDecimal("500.00"), null, null, null, null, null),
                        partHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, requestHeaders), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    /** PUT /api/products/{id} is multipart because it can carry an image alongside the JSON. */
    private HttpEntity<MultiValueMap<String, Object>> multipart(UpdateProductRequest request, HttpHeaders headers) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, partHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.putAll(headers);
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, requestHeaders);
    }

    private ProductApprovalStatus approvalStatusOf(UUID productId) {
        return ProductApprovalStatus.valueOf(jdbc.queryForObject(
                "SELECT approval_status FROM products WHERE id = ?", String.class, productId));
    }

    /**
     * The reviewer's decision is cleared alongside the status, because it no longer refers
     * to this product - somebody approved something with a different brand. The rejection
     * REASON survives, which is a separate column and deliberately not asserted here.
     */
    private boolean reviewedAtIsCleared(UUID productId) {
        Integer nulls = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE id = ? AND reviewed_at IS NULL AND reviewed_by IS NULL",
                Integer.class,
                productId);
        return nulls != null && nulls == 1;
    }

    /**
     * Whether the row is one a super admin would be asked to review: the queue is pinned to
     * ACTIVE VENDOR sellers, so a buying company's private stock can never appear in it
     * however its approval_status column happens to read.
     */
    private boolean moderationQueueContains(UUID productId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products p JOIN clients c ON c.id = p.client_id "
                        + "WHERE p.id = ? AND c.client_type = 'VENDOR' AND c.is_active AND NOT c.is_platform_owner",
                Integer.class,
                productId);
        return count != null && count > 0;
    }

    private UUID clientIdOf(TenantLoginResponse login) {
        return jdbc.queryForObject(
                "SELECT id FROM clients WHERE slug = ?", UUID.class, login.user().clientIdentifier());
    }

    private TenantLoginResponse loginAsPlatformOwner() {
        TenantLoginResponse response = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(response)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        return response;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
