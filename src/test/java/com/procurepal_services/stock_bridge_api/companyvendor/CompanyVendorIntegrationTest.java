package com.procurepal_services.stock_bridge_api.companyvendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.cart.dto.AddCartItemRequest;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorDetailResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.VendorProductPriceResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.CompanyVendorKind;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentTerms;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.order.dto.OrderResponse;
import com.procurepal_services.stock_bridge_api.order.dto.ReceiveOrderRequest;
import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseHistoryEntry;
import com.procurepal_services.stock_bridge_api.purchase.dto.PurchaseSource;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * The buyer-side vendor directory, end to end: the two kinds of entry and what
 * separates them, the auto-create on purchase, and the two derived figures
 * (last purchase price, purchase history) that are the point of the whole module.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration
 * test here - see AuthIntegrationTest for why local Postgres over Testcontainers -
 * and against the seeded ProcurePal catalog. Requires `docker compose up -d`.
 *
 * <p>Every buying company is a freshly signed-up tenant rather than the shared
 * `demo` one, so tests cannot see each other's directories or orders and can run
 * in any order. Catalog products are planted per test rather than taken from the
 * seed, for the reason MarketplaceOrderIntegrationTest gives: COD orders left at
 * PLACED count as committed stock forever against a database that is never reset.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CompanyVendorIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RoleRepository roleRepository;

    private final List<UUID> plantedCatalogProductIds = new ArrayList<>();

    /** Withdrawn rather than deleted - order_items references them ON DELETE RESTRICT. */
    @AfterEach
    void withdrawPlantedCatalogProducts() {
        for (UUID productId : plantedCatalogProductIds) {
            productRepository.findById(productId).ifPresent(product -> {
                product.setMarketplaceListed(false);
                product.setActive(false);
                productRepository.saveAndFlush(product);
            });
        }
        plantedCatalogProductIds.clear();
    }

    // ------------------------------------------------------------------------
    // (a) EXTERNAL vendors: the company's own suppliers
    // ------------------------------------------------------------------------

    @Test
    void aCompanyAddsEditsAndRemovesItsOwnSupplier() {
        Buyer buyer = signupBuyer("External Vendor Lifecycle Co");

        CompanyVendorResponse created = createVendor(
                buyer, vendorRequest("Ada Millers", "+234 801 111 2222"));
        assertThat(created.kind()).isEqualTo(CompanyVendorKind.EXTERNAL);
        // The guarantee the DTO's shape exists to give: an external entry can never
        // carry a platform client id, because nothing in the request can express one.
        assertThat(created.platformClientId()).isNull();
        assertThat(created.editable()).isTrue();
        assertThat(created.active()).isTrue();
        assertThat(created.contactPhone()).isEqualTo("+234 801 111 2222");

        CompanyVendorResponse updated = updateVendor(
                buyer,
                created.id(),
                new CompanyVendorRequest(
                        "Ada Millers Limited",
                        "0803 000 0000",
                        "sales@adamillers.example",
                        "14 Mill Road",
                        null,
                        "Ikeja",
                        "Lagos",
                        "Delivers Tuesdays.",
                        "First Bank",
                        "0123456789",
                        "Ada Millers Limited",
                        "RC 123456"));
        assertThat(updated.name()).isEqualTo("Ada Millers Limited");
        assertThat(updated.email()).isEqualTo("sales@adamillers.example");
        assertThat(updated.state()).isEqualTo("Lagos");
        assertThat(updated.notes()).isEqualTo("Delivers Tuesdays.");
        // V28: optional on both kinds, and the buyer's OWN record of how they pay this supplier -
        // never sourced from the seller's clients row. See CompanyVendor's javadoc.
        assertThat(updated.bankName()).isEqualTo("First Bank");
        assertThat(updated.bankAccountNumber()).isEqualTo("0123456789");
        assertThat(updated.bankAccountName()).isEqualTo("Ada Millers Limited");
        assertThat(updated.cacNumber()).isEqualTo("RC 123456");
        // Still external after an edit - an update can no more change the kind than a
        // create can choose it.
        assertThat(updated.kind()).isEqualTo(CompanyVendorKind.EXTERNAL);
        assertThat(updated.platformClientId()).isNull();

        assertThat(listVendors(buyer)).extracting(CompanyVendorResponse::name).contains("Ada Millers Limited");

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/company-vendors/" + created.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(buyer.headers()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Deactivation, not deletion - but indistinguishable from one for every caller,
        // which is the whole claim. Gone from the list, and a 404 by id.
        assertThat(listVendors(buyer)).extracting(CompanyVendorResponse::name).doesNotContain("Ada Millers Limited");
        assertThat(getVendorRaw(buyer, created.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The failure this must NOT be is a 500. contact_phone is required by
     * chk_company_vendors_external_shape at the database level, and a hand-typed
     * supplier with no number reaching that constraint would produce a Postgres
     * error in the log and nothing useful on the form.
     */
    @Test
    void anExternalVendorWithNoContactNumberIsAValidationErrorRatherThanAServerError() {
        Buyer buyer = signupBuyer("External Vendor No Phone Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(vendorRequest("Phoneless Supplies", "   "), buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("contactPhone");
        // And nothing was written on the way to being rejected.
        assertThat(listVendors(buyer)).isEmpty();
    }

    @Test
    void aVendorWithNoNameIsRejected() {
        Buyer buyer = signupBuyer("External Vendor No Name Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(vendorRequest("  ", "0801 234 5678"), buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("name");
    }

    /** Server-side, because a select on the frontend is a convenience and not a constraint. */
    @Test
    void aStateOutsideNigeriaIsRejected() {
        Buyer buyer = signupBuyer("External Vendor Bad State Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CompanyVendorRequest(
                                "Faraway Traders", "0801 234 5678", null, null, null, "Accra", "Greater Accra", null,
                                null, null, null, null),
                        buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("Greater Accra");
    }

    /**
     * Deliberately no uniqueness on (client_id, name): a company may genuinely deal
     * with two suppliers of the same name, and rejecting the second - after they
     * typed it - would be the schema overruling a fact about their business.
     */
    @Test
    void twoSuppliersMayShareAName() {
        Buyer buyer = signupBuyer("Duplicate Supplier Name Co");

        createVendor(buyer, vendorRequest("Mainland Diesel", "0801 111 1111"));
        createVendor(buyer, vendorRequest("Mainland Diesel", "0802 222 2222"));

        assertThat(listVendors(buyer))
                .filteredOn(vendor -> vendor.name().equals("Mainland Diesel"))
                .hasSize(2);
    }

    // ------------------------------------------------------------------------
    // (b) tenant isolation
    // ------------------------------------------------------------------------

    /**
     * The property this module is judged on. A directory row carries what one
     * company privately noted about a supplier and what it privately paid them, so
     * the failure mode of a missing predicate is commercial intelligence, not an
     * anonymous list of names.
     */
    @Test
    void oneCompanyNeverSeesAnotherCompanysVendors() {
        Buyer first = signupBuyer("Vendor Isolation One Co");
        Buyer second = signupBuyer("Vendor Isolation Two Co");

        CompanyVendorResponse firstsVendor =
                createVendor(first, vendorRequest("First Co Secret Supplier", "0801 111 1111"));
        createVendor(second, vendorRequest("Second Co Supplier", "0802 222 2222"));

        assertThat(listVendors(second))
                .extracting(CompanyVendorResponse::name)
                .containsExactly("Second Co Supplier");

        // Not merely absent from the list - unreachable by its id, which is the case a
        // list-only assertion would miss.
        assertThat(getVendorRaw(second, firstsVendor.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // And the same for the two derived screens, which read orders rather than the
        // directory and could have got their predicate backwards independently.
        assertThat(restTemplate
                        .exchange(
                                "/api/purchases?companyVendorId=" + firstsVendor.id(),
                                HttpMethod.GET,
                                new HttpEntity<>(second.headers()),
                                ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Neither may write it.
        assertThat(restTemplate
                        .exchange(
                                "/api/company-vendors/" + firstsVendor.id(),
                                HttpMethod.PUT,
                                new HttpEntity<>(vendorRequest("Hijacked", "0800 000 0000"), second.headers()),
                                ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate
                        .exchange(
                                "/api/company-vendors/" + firstsVendor.id(),
                                HttpMethod.DELETE,
                                new HttpEntity<>(second.headers()),
                                ApiError.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // ...and the first company's row is untouched by all of that.
        assertThat(getVendor(first, firstsVendor.id()).vendor().name()).isEqualTo("First Co Secret Supplier");
    }

    // ------------------------------------------------------------------------
    // (c) VERIFIED vendors: auto-created on purchase, and not editable
    // ------------------------------------------------------------------------

    /**
     * The auto-create, its idempotency, and the reactivation rule, in one flow -
     * because they are one behaviour and a test per clause would have to rebuild the
     * same three orders three times.
     */
    @Test
    void buyingFromASellerAddsThemToTheDirectoryOnceHoweverManyOrdersFollow() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Verified Vendor Autocreate Co");
        Product catalogProduct = plantCatalogProduct(200, 1);
        UUID addressId = createAddress(buyer).id();

        assertThat(listVendors(buyer)).isEmpty();

        placeCodOrder(buyer, catalogProduct, 5, addressId);

        List<CompanyVendorResponse> afterFirst = listVendors(buyer);
        assertThat(afterFirst).hasSize(1);
        CompanyVendorResponse vendor = afterFirst.getFirst();
        assertThat(vendor.kind()).isEqualTo(CompanyVendorKind.VERIFIED);
        assertThat(vendor.platformClientId()).isEqualTo(platformOwner().getId());
        assertThat(vendor.name()).isEqualTo(platformOwner().getName());
        // The row asserts a fact about the platform, so the company may not rewrite it.
        assertThat(vendor.editable()).isFalse();

        // A second order from the same seller must not create a second row, and must
        // not fail on the partial unique index.
        placeCodOrder(buyer, catalogProduct, 3, addressId);

        List<CompanyVendorResponse> afterSecond = listVendors(buyer);
        assertThat(afterSecond).hasSize(1);
        assertThat(afterSecond.getFirst().id()).isEqualTo(vendor.id());

        // Removed, then bought from again: the ORIGINAL row comes back rather than a
        // duplicate - which is why findByClientIdAndPlatformClientId ignores `active`.
        deleteVendor(buyer, vendor.id());
        assertThat(listVendors(buyer)).isEmpty();

        placeCodOrder(buyer, catalogProduct, 2, addressId);

        List<CompanyVendorResponse> afterReactivation = listVendors(buyer);
        assertThat(afterReactivation).hasSize(1);
        assertThat(afterReactivation.getFirst().id()).isEqualTo(vendor.id());
    }

    /**
     * The other guarantee this module is judged on. 409 and not 403: the caller DOES
     * hold MANAGE_VENDORS and the same request would succeed against an external
     * row, so this is a conflict with the state of the resource rather than a
     * permission failure.
     */
    @Test
    void aVerifiedVendorCannotBeEditedByTheOwningCompany() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Verified Vendor Not Editable Co");
        Product catalogProduct = plantCatalogProduct(50, 1);
        placeCodOrder(buyer, catalogProduct, 2, createAddress(buyer).id());

        CompanyVendorResponse verified = listVendors(buyer).getFirst();
        assertThat(verified.kind()).isEqualTo(CompanyVendorKind.VERIFIED);

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/company-vendors/" + verified.id(),
                HttpMethod.PUT,
                new HttpEntity<>(vendorRequest("Renamed By The Buyer", "0800 000 0000"), buyer.headers()),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("cannot be edited");

        // And nothing moved: the name is still the seller's.
        assertThat(getVendor(buyer, verified.id()).vendor().name()).isEqualTo(platformOwner().getName());
    }

    /**
     * Deactivating a VERIFIED row IS allowed, unlike editing it. They are different
     * claims: "stop showing me this supplier" is the company's opinion about its own
     * directory; "this supplier is called something else" is a fact about an account
     * it does not own.
     */
    @Test
    void aVerifiedVendorMayStillBeRemovedFromTheDirectory() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Verified Vendor Removable Co");
        Product catalogProduct = plantCatalogProduct(50, 1);
        placeCodOrder(buyer, catalogProduct, 2, createAddress(buyer).id());

        CompanyVendorResponse verified = listVendors(buyer).getFirst();
        deleteVendor(buyer, verified.id());

        assertThat(listVendors(buyer)).isEmpty();
    }

    /**
     * A raw body carrying the two fields the request record cannot express. The DTO
     * physically has no vendorKind or platformClientId, so building the body through
     * it would prove nothing about what happens when a caller sends those keys
     * anyway - the same reasoning CompanyIntegrationTest gives for its immutability
     * cases.
     */
    @Test
    void anExternalVendorCannotBeSmuggledIntoNamingAPlatformSeller() {
        Buyer buyer = signupBuyer("External Vendor Smuggle Co");
        UUID platformOwnerId = platformOwner().getId();

        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                jsonBody(
                        """
                        {
                          "name": "Pretend Verified Supplier",
                          "contactPhone": "0801 234 5678",
                          "kind": "VERIFIED",
                          "vendorKind": "VERIFIED",
                          "platformClientId": "%s",
                          "editable": false,
                          "active": true
                        }
                        """
                                .formatted(platformOwnerId),
                        buyer.headers()),
                CompanyVendorResponse.class);

        // The request was genuinely processed - this is not passing because the whole
        // body was rejected.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().name()).isEqualTo("Pretend Verified Supplier");
        // ...and every smuggled field is at its real value.
        assertThat(response.getBody().kind()).isEqualTo(CompanyVendorKind.EXTERNAL);
        assertThat(response.getBody().platformClientId()).isNull();
        assertThat(response.getBody().editable()).isTrue();

        CompanyVendorResponse reloaded = getVendor(buyer, response.getBody().id()).vendor();
        assertThat(reloaded.kind()).isEqualTo(CompanyVendorKind.EXTERNAL);
        assertThat(reloaded.platformClientId()).isNull();
    }

    // ------------------------------------------------------------------------
    // (d) last purchase price and purchase history
    // ------------------------------------------------------------------------

    /**
     * The single highest-value buyer field in VENDOR_RESEARCH.md Section B. Three
     * purchases of the same product at three different prices; the answer must be
     * the LAST one, not the first, the cheapest or an average.
     */
    @Test
    void lastPurchasePriceIsTheMostRecentOfSeveralPurchasesOfTheSameProduct() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Last Purchase Price Co");
        Product catalogProduct = plantCatalogProduct(300, 1);
        UUID addressId = createAddress(buyer).id();

        repriceCatalogProduct(catalogProduct.getId(), new BigDecimal("10000.00"));
        UUID firstOrderId = placeCodOrder(buyer, catalogProduct, 4, addressId).id();
        deliverOrder(buyer, firstOrderId);
        receive(buyer, firstOrderId, null);

        repriceCatalogProduct(catalogProduct.getId(), new BigDecimal("11500.00"));
        UUID secondOrderId = placeCodOrder(buyer, catalogProduct, 6, addressId).id();
        deliverOrder(buyer, secondOrderId);
        receive(buyer, secondOrderId, null);

        // The last one, and deliberately the CHEAPEST, so a test that happened to pick
        // the highest price would fail here.
        repriceCatalogProduct(catalogProduct.getId(), new BigDecimal("9250.00"));
        UUID thirdOrderId = placeCodOrder(buyer, catalogProduct, 7, addressId).id();
        deliverOrder(buyer, thirdOrderId);
        receive(buyer, thirdOrderId, null);

        CompanyVendorResponse vendor = listVendors(buyer).getFirst();
        CompanyVendorDetailResponse detail = getVendor(buyer, vendor.id());

        // Since V19, the product_vendors link (what makes a product show up as
        // "supplied by" this vendor) is created by the RECEIVING step, not by placing
        // the order - see IncomingStockService.receive and the design doc's §7.2. An
        // order that is only placed, never received, is not yet a standing "we buy
        // this from them" arrangement; goods actually arriving is. Each order above is
        // received immediately so the link exists by the time the assertions run.
        assertThat(detail.products()).hasSize(1);
        VendorProductPriceResponse supplied = detail.products().getFirst();
        assertThat(supplied.sku()).isEqualTo(catalogProduct.getSku());
        assertThat(supplied.lastPurchaseUnitPrice()).isEqualByComparingTo(new BigDecimal("9250.00"));
        assertThat(supplied.lastPurchaseQuantity()).isEqualTo(7);
        assertThat(supplied.lastPurchasedAt()).isNotNull();
        assertThat(supplied.lastPurchaseOrderNumber()).isNotBlank();

        // Spend covers all three orders, not just the last.
        assertThat(detail.spend().orderCount()).isEqualTo(3);
        assertThat(detail.spend().totalSpend()).isGreaterThan(BigDecimal.ZERO);
        assertThat(detail.spend().lastPurchasedAt()).isNotNull();

        // The live seller is resolved on the detail read - this is what makes a stale
        // name snapshot self-correct the moment somebody opens the screen.
        assertThat(detail.platformVendor()).isNotNull();
        assertThat(detail.platformVendor().name()).isEqualTo(platformOwner().getName());
    }

    @Test
    void purchaseHistoryListsEveryOrderPlacedWithThatSeller() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Purchase History Co");
        Product catalogProduct = plantCatalogProduct(100, 1);
        UUID addressId = createAddress(buyer).id();

        OrderResponse first = placeCodOrder(buyer, catalogProduct, 2, addressId);
        OrderResponse second = placeCodOrder(buyer, catalogProduct, 3, addressId);

        CompanyVendorResponse vendor = listVendors(buyer).getFirst();
        List<PurchaseHistoryEntry> purchases = purchases(buyer, vendor.id());

        assertThat(purchases).hasSize(2);
        // Newest first - the order the screen renders.
        assertThat(purchases.getFirst().orderNumber()).isEqualTo(second.orderNumber());
        assertThat(purchases.get(1).orderNumber()).isEqualTo(first.orderNumber());

        PurchaseHistoryEntry newest = purchases.getFirst();
        assertThat(newest.source()).isEqualTo(PurchaseSource.MARKETPLACE_ORDER);
        assertThat(newest.occurredAt()).isNotNull();
        assertThat(newest.lines()).hasSize(1);
        assertThat(newest.lines().getFirst().quantity()).isEqualTo(3);
        assertThat(newest.lines().getFirst().productSku()).isEqualTo(catalogProduct.getSku());
        assertThat(newest.total()).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * An external supplier has no platform orders and never will, and while it has no
     * stock recorded against it either, its history and spend are both the finished,
     * correct empty/zero answer rather than a gap waiting for a feature. See
     * {@link #aManualStockInAgainstAnExternalVendorAppearsInPurchaseHistoryButNotSpend()}
     * for the moment a real delivery is recorded and the two screens diverge.
     */
    @Test
    void anExternalVendorHasNoPurchaseHistoryAndNoSpend() {
        Buyer buyer = signupBuyer("External Vendor Empty History Co");
        CompanyVendorResponse vendor = createVendor(buyer, vendorRequest("Corner Shop Diesel", "0801 234 5678"));

        CompanyVendorDetailResponse detail = getVendor(buyer, vendor.id());
        assertThat(detail.platformVendor()).isNull();
        assertThat(detail.products()).isEmpty();
        assertThat(detail.spend().orderCount()).isZero();
        // Zero, never null: a null would render as an empty box where a real 0 belongs.
        assertThat(detail.spend().totalSpend()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(detail.spend().lastPurchasedAt()).isNull();

        assertThat(purchases(buyer, vendor.id())).isEmpty();
    }

    /**
     * The bug PurchaseHistoryService was built to fix: purchase history used to read only
     * {@code orders}, so a supplier bought from exclusively through manual stock-in - which is
     * every EXTERNAL vendor, by construction, since it has no platform account to place an
     * order against - never showed anything there no matter how much stock was actually
     * recorded against it. A manual stock-in freezes the vendor, quantity and price onto the
     * {@code StockMovement} row itself (see its own class javadoc, V19), so there was always a
     * real record to show; nothing was reading it.
     *
     * <p>Spend and last-purchase-price stay order-only by design - see
     * {@code VendorPurchaseService}'s class javadoc for why a manual entry is not run through
     * the same checkout/payment path an order is, and so is not folded into "spend".
     */
    @Test
    void aManualStockInAgainstAnExternalVendorAppearsInPurchaseHistoryButNotSpend() {
        Buyer buyer = signupBuyer("Manual Stock-in History Co");
        CompanyVendorResponse vendor = createVendor(buyer, vendorRequest("Corner Shop Diesel", "0801 234 5678"));
        Product product = plantBuyerProduct(buyer, "MANUAL-STOCKIN-1", "BAG");

        stockIn(buyer, product.getId(), vendor.id(), 10, new BigDecimal("4500.00"), "First delivery");

        List<PurchaseHistoryEntry> purchases = purchases(buyer, vendor.id());
        assertThat(purchases).hasSize(1);
        PurchaseHistoryEntry entry = purchases.getFirst();
        assertThat(entry.source()).isEqualTo(PurchaseSource.MANUAL_STOCK_IN);
        assertThat(entry.orderNumber()).isNull();
        assertThat(entry.note()).isEqualTo("First delivery");
        assertThat(entry.occurredAt()).isNotNull();
        assertThat(entry.lines()).hasSize(1);
        assertThat(entry.lines().getFirst().quantity()).isEqualTo(10);
        assertThat(entry.lines().getFirst().unitPrice()).isEqualByComparingTo(new BigDecimal("4500.00"));
        assertThat(entry.total()).isEqualByComparingTo(new BigDecimal("45000.00"));

        // Spend stays order-only by design (see class javadoc above) - a manual entry never
        // touches it, even though it now appears in the history list right beside it.
        CompanyVendorDetailResponse detail = getVendor(buyer, vendor.id());
        assertThat(detail.spend().totalSpend()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * The company-wide feed above the per-vendor screen: {@code /api/purchases} with no
     * {@code companyVendorId} is every supplier's history, merged into one date-ordered list -
     * which is the whole reason the merge happens in a single database query rather than in the
     * frontend (see {@code PurchaseHistoryRepository}'s class javadoc). {@code source} narrows
     * it to one ledger, for the screen's own filter.
     */
    @Test
    void theCompanyWideFeedMergesBothLedgersAcrossVendorsAndCanBeFilteredBySource() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Company Wide Purchases Co");
        Product catalogProduct = plantCatalogProduct(50, 1);
        placeCodOrder(buyer, catalogProduct, 2, createAddress(buyer).id());

        CompanyVendorResponse externalVendor = createVendor(buyer, vendorRequest("Corner Shop Diesel", "0801 234 5678"));
        Product buyerProduct = plantBuyerProduct(buyer, "MANUAL-GLOBAL-1", "BAG");
        stockIn(buyer, buyerProduct.getId(), externalVendor.id(), 5, new BigDecimal("2000.00"), null);

        assertThat(purchasesGlobal(buyer, null))
                .extracting(PurchaseHistoryEntry::source)
                .containsExactlyInAnyOrder(PurchaseSource.MARKETPLACE_ORDER, PurchaseSource.MANUAL_STOCK_IN);

        assertThat(purchasesGlobal(buyer, PurchaseSource.MARKETPLACE_ORDER))
                .extracting(PurchaseHistoryEntry::source)
                .containsExactly(PurchaseSource.MARKETPLACE_ORDER);
        assertThat(purchasesGlobal(buyer, PurchaseSource.MANUAL_STOCK_IN))
                .extracting(PurchaseHistoryEntry::source)
                .containsExactly(PurchaseSource.MANUAL_STOCK_IN);
    }

    // ------------------------------------------------------------------------
    // (e) filtering and search
    // ------------------------------------------------------------------------

    @Test
    void theDirectoryHoldsBothKindsTogetherAndFiltersAndSearchesAcrossThem() {
        Buyer buyer = signupBuyerAllowedPayOnDelivery("Vendor Filter Co");
        Product catalogProduct = plantCatalogProduct(50, 1);
        placeCodOrder(buyer, catalogProduct, 2, createAddress(buyer).id());
        createVendor(buyer, vendorRequest("Zenith Packaging", "0801 111 1111"));

        // Both kinds in one list by default - the point of one table with a kind.
        assertThat(listVendors(buyer)).hasSize(2);
        assertThat(listVendors(buyer)).extracting(CompanyVendorResponse::kind)
                .containsExactlyInAnyOrder(CompanyVendorKind.VERIFIED, CompanyVendorKind.EXTERNAL);

        assertThat(listVendors(buyer, "?kind=EXTERNAL"))
                .extracting(CompanyVendorResponse::name)
                .containsExactly("Zenith Packaging");
        assertThat(listVendors(buyer, "?kind=VERIFIED"))
                .extracting(CompanyVendorResponse::kind)
                .containsExactly(CompanyVendorKind.VERIFIED);

        // Search is case-insensitive and matches a fragment of the name.
        assertThat(listVendors(buyer, "?search=zenith"))
                .extracting(CompanyVendorResponse::name)
                .containsExactly("Zenith Packaging");
        assertThat(listVendors(buyer, "?search=nothing-matches-this")).isEmpty();
    }

    // ------------------------------------------------------------------------
    // (f) permissions
    // ------------------------------------------------------------------------

    /**
     * STOREKEEPER holds neither code, following V6's own logic that prices and spend
     * are not their business. PROCUREMENT_MANAGER holds both.
     */
    @Test
    void aStorekeeperCannotReachTheDirectoryAtAllAndAProcurementManagerCanManageIt() {
        Buyer owner = signupBuyer("Vendor Permissions Co");
        CompanyVendorResponse existing = createVendor(owner, vendorRequest("Baseline Supplier", "0801 111 1111"));

        createUser(owner, "floor-hand", "STOREKEEPER");
        HttpHeaders storekeeper = authHeaders(login(owner.clientIdentifier(), "floor-hand", PASSWORD));

        // Denied on every endpoint, read and write alike.
        assertForbidden(HttpMethod.GET, "/api/company-vendors", null, storekeeper);
        assertForbidden(HttpMethod.GET, "/api/company-vendors/" + existing.id(), null, storekeeper);
        assertForbidden(HttpMethod.GET, "/api/purchases?companyVendorId=" + existing.id(), null, storekeeper);
        assertForbidden(
                HttpMethod.POST, "/api/company-vendors", vendorRequest("Sneaky Ltd", "0800 000 0000"), storekeeper);
        assertForbidden(
                HttpMethod.PUT,
                "/api/company-vendors/" + existing.id(),
                vendorRequest("Renamed Ltd", "0800 000 0000"),
                storekeeper);
        assertForbidden(HttpMethod.DELETE, "/api/company-vendors/" + existing.id(), null, storekeeper);

        createUser(owner, "buyer-manager", "PROCUREMENT_MANAGER");
        Buyer manager = new Buyer(login(owner.clientIdentifier(), "buyer-manager", PASSWORD), owner.clientId());

        assertThat(listVendors(manager)).extracting(CompanyVendorResponse::name).contains("Baseline Supplier");
        CompanyVendorResponse createdByManager =
                createVendor(manager, vendorRequest("Manager Added Supplier", "0802 222 2222"));
        assertThat(createdByManager.id()).isNotNull();
        deleteVendor(manager, createdByManager.id());
    }

    /**
     * A finance officer reconciles against what was paid, so they read the directory -
     * but they do not maintain it. This is the split that would silently collapse if
     * both endpoints were ever gated on the same code.
     */
    @Test
    void aFinanceOfficerReadsTheDirectoryButCannotChangeIt() {
        Buyer owner = signupBuyer("Vendor Finance Read Co");
        CompanyVendorResponse existing = createVendor(owner, vendorRequest("Audited Supplier", "0801 111 1111"));

        createUser(owner, "book-keeper", "FINANCE_OFFICER");
        HttpHeaders finance = authHeaders(login(owner.clientIdentifier(), "book-keeper", PASSWORD));

        ResponseEntity<TestPage<CompanyVendorResponse>> read = restTemplate.exchange(
                "/api/company-vendors?size=100",
                HttpMethod.GET,
                new HttpEntity<>(finance),
                new ParameterizedTypeReference<TestPage<CompanyVendorResponse>>() {});
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().content()).extracting(CompanyVendorResponse::name).contains("Audited Supplier");

        assertForbidden(
                HttpMethod.POST, "/api/company-vendors", vendorRequest("New Ltd", "0800 000 0000"), finance);
        assertForbidden(HttpMethod.DELETE, "/api/company-vendors/" + existing.id(), null, finance);
    }

    @Test
    void theDirectoryIsNotReachableWithoutAToken() {
        assertThat(restTemplate.getForEntity("/api/company-vendors", String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------

    private record Buyer(TenantLoginResponse login, UUID clientId) {

        HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(login.tokens().accessToken());
            return headers;
        }

        String clientIdentifier() {
            return login.user().clientIdentifier();
        }
    }

    /** Spring's Page JSON has no public constructor Jackson can use; only content is asserted on. */
    private record TestPage<T>(List<T> content) {
    }

    private static CompanyVendorRequest vendorRequest(String name, String phone) {
        return new CompanyVendorRequest(name, phone, null, null, null, null, null, null, null, null, null, null);
    }

    private CompanyVendorResponse createVendor(Buyer buyer, CompanyVendorRequest request) {
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors",
                HttpMethod.POST,
                new HttpEntity<>(request, buyer.headers()),
                CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).as("create vendor").isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private CompanyVendorResponse updateVendor(Buyer buyer, UUID id, CompanyVendorRequest request) {
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors/" + id,
                HttpMethod.PUT,
                new HttpEntity<>(request, buyer.headers()),
                CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).as("update vendor").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void deleteVendor(Buyer buyer, UUID id) {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/company-vendors/" + id, HttpMethod.DELETE, new HttpEntity<>(buyer.headers()), Void.class);
        assertThat(response.getStatusCode()).as("delete vendor").isEqualTo(HttpStatus.NO_CONTENT);
    }

    private List<CompanyVendorResponse> listVendors(Buyer buyer) {
        return listVendors(buyer, "");
    }

    private List<CompanyVendorResponse> listVendors(Buyer buyer, String query) {
        String separator = query.isEmpty() ? "?" : "&";
        return restTemplate
                .exchange(
                        "/api/company-vendors" + query + separator + "size=100",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<CompanyVendorResponse>>() {})
                .getBody()
                .content();
    }

    private CompanyVendorDetailResponse getVendor(Buyer buyer, UUID id) {
        ResponseEntity<CompanyVendorDetailResponse> response = restTemplate.exchange(
                "/api/company-vendors/" + id,
                HttpMethod.GET,
                new HttpEntity<>(buyer.headers()),
                CompanyVendorDetailResponse.class);
        assertThat(response.getStatusCode()).as("get vendor").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<ApiError> getVendorRaw(Buyer buyer, UUID id) {
        return restTemplate.exchange(
                "/api/company-vendors/" + id, HttpMethod.GET, new HttpEntity<>(buyer.headers()), ApiError.class);
    }

    private List<PurchaseHistoryEntry> purchases(Buyer buyer, UUID vendorId) {
        return restTemplate
                .exchange(
                        "/api/purchases?companyVendorId=" + vendorId + "&size=100",
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<PurchaseHistoryEntry>>() {})
                .getBody()
                .content();
    }

    /** The company-wide feed - no {@code companyVendorId}, optionally narrowed to one source. */
    private List<PurchaseHistoryEntry> purchasesGlobal(Buyer buyer, PurchaseSource source) {
        String query = source == null ? "" : "&source=" + source;
        return restTemplate
                .exchange(
                        "/api/purchases?size=100" + query,
                        HttpMethod.GET,
                        new HttpEntity<>(buyer.headers()),
                        new ParameterizedTypeReference<TestPage<PurchaseHistoryEntry>>() {})
                .getBody()
                .content();
    }

    /**
     * A product in the BUYER's own inventory, planted directly rather than through the
     * multipart {@code POST /api/products} endpoint - this suite has no other reason to touch
     * file upload, and a direct save is what {@link #plantCatalogProduct} already does for the
     * seller side of the same problem.
     */
    private Product plantBuyerProduct(Buyer buyer, String sku, String unitOfMeasure) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name(sku + " product")
                .sku(sku + "-" + unique)
                .quantityOnHand(0)
                .active(true)
                .marketplaceListed(false)
                .unitOfMeasure(unitOfMeasure)
                .build();
        TenantContext.set(buyer.clientId());
        try {
            return productRepository.saveAndFlush(product);
        } finally {
            TenantContext.clear();
        }
    }

    /** Records a manual delivery against a company vendor - {@code note} may be null. */
    private void stockIn(Buyer buyer, UUID productId, UUID companyVendorId, int quantity, BigDecimal unitPrice, String note) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(quantity, unitPrice, note, null, companyVendorId, null, null),
                        buyer.headers()),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).as("stock-in").isEqualTo(HttpStatus.OK);
    }

    private void assertForbidden(HttpMethod method, String path, Object body, HttpHeaders caller) {
        ResponseEntity<ApiError> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, caller), ApiError.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private HttpEntity<String> jsonBody(String json, HttpHeaders auth) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(auth);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    /** COD, so the order reaches PLACED immediately - which is the moment the directory entry appears. */
    private OrderResponse placeCodOrder(Buyer buyer, Product catalogProduct, int quantity, UUID addressId) {
        ResponseEntity<String> added = restTemplate.exchange(
                "/api/cart/items",
                HttpMethod.POST,
                new HttpEntity<>(new AddCartItemRequest(catalogProduct.getId(), quantity), buyer.headers()),
                String.class);
        assertThat(added.getStatusCode()).as("add to cart: %s", added.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<OrderResponse> placed = restTemplate.exchange(
                "/api/orders",
                HttpMethod.POST,
                new HttpEntity<>(
                        new PlaceOrderRequestBody(PaymentMethod.PAY_ON_DELIVERY, addressId), buyer.headers()),
                OrderResponse.class);
        assertThat(placed.getStatusCode()).as("place order").isEqualTo(HttpStatus.OK);
        return placed.getBody();
    }

    /**
     * Fast-forwards a COD order straight to DELIVERED, bypassing the operator-driven
     * CONFIRMED/PROCESSING/OUT_FOR_DELIVERY steps that {@code OrderLifecycleService}
     * would otherwise require - this suite has no platform-owner-operator login
     * fixture, and the state machine's own transition audit trail is not what these
     * tests are checking. Direct persistence-layer manipulation under the buyer's own
     * tenant, same pattern as {@link #plantCatalogProduct} / {@link #repriceCatalogProduct}
     * already use for the platform owner's side.
     */
    private void deliverOrder(Buyer buyer, UUID orderId) {
        TenantContext.set(buyer.clientId());
        try {
            Order order = orderRepository.findById(orderId).orElseThrow();
            order.setStatus(OrderStatus.DELIVERED);
            order.setDeliveredAt(java.time.OffsetDateTime.now());
            orderRepository.saveAndFlush(order);
        } finally {
            TenantContext.clear();
        }
    }

    /** Full receipt of everything outstanding on the order (request body {@code null}). */
    private OrderResponse receive(Buyer buyer, UUID orderId, ReceiveOrderRequest request) {
        ResponseEntity<OrderResponse> response = restTemplate.exchange(
                "/api/orders/" + orderId + "/receive",
                HttpMethod.POST,
                new HttpEntity<>(request, buyer.headers()),
                OrderResponse.class);
        assertThat(response.getStatusCode()).as("receive").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /**
     * Hand-rolled rather than the real PlaceOrderRequest so this suite does not have
     * to be edited every time the checkout module adds an optional field - it only
     * ever needs the two that matter here.
     */
    private record PlaceOrderRequestBody(PaymentMethod paymentMethod, UUID deliveryAddressId) {
    }

    private DeliveryAddressResponse createAddress(Buyer buyer) {
        DeliveryAddressResponse address = restTemplate
                .exchange(
                        "/api/delivery-addresses",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new DeliveryAddressRequest(
                                        "Main Kitchen",
                                        "Ada Okafor",
                                        "+234 801 234 5678",
                                        "14 Adeola Odeku Street",
                                        null,
                                        "Victoria Island",
                                        "Lagos",
                                        null,
                                        null,
                                        null,
                                        null),
                                buyer.headers()),
                        DeliveryAddressResponse.class)
                .getBody();
        assertThat(address).isNotNull();
        return address;
    }

    private Buyer signupBuyer(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        TenantLoginResponse login =
                restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
        assertThat(login).isNotNull();
        UUID clientId = clientRepository.findBySlug(login.user().clientIdentifier()).orElseThrow().getId();
        return new Buyer(login, clientId);
    }

    /** A new signup is PREPAID; pay on delivery is ProcurePal ops' decision, with no self-service endpoint. */
    private Buyer signupBuyerAllowedPayOnDelivery(String name) {
        Buyer buyer = signupBuyer(name);
        Client client = clientRepository.findById(buyer.clientId()).orElseThrow();
        client.setPaymentTerms(PaymentTerms.PAY_ON_DELIVERY_ALLOWED);
        clientRepository.saveAndFlush(client);
        return buyer;
    }

    private void createUser(Buyer asOwner, String username, String role) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                username,
                                PASSWORD,
                                roleRepository.findByName(role).orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        asOwner.headers()),
                UserSummaryResponse.class);
    }

    private TenantLoginResponse login(String clientIdentifier, String username, String password) {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(clientIdentifier, username, password), TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }

    private Client platformOwner() {
        return clientRepository.findByPlatformOwnerTrue().orElseThrow();
    }

    /**
     * A listed ProcurePal product created just for one test - not the seeded catalog,
     * because availability is on-hand MINUS everything sold and not yet dispatched,
     * and every COD order these tests leave at PLACED counts against it forever on a
     * local Postgres that is never reset.
     */
    private Product plantCatalogProduct(int quantityOnHand, int minOrderQuantity) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Product product = Product.builder()
                .name("Vendor Test Item " + unique)
                .sku("CV-TEST-" + unique)
                .slug("cv-test-" + unique)
                .description("Planted by CompanyVendorIntegrationTest.")
                .unitPrice(new BigDecimal("12000.00"))
                .quantityOnHand(quantityOnHand)
                .active(true)
                .marketplaceListed(true)
                .unitOfMeasure("bag (25kg)")
                .minOrderQuantity(minOrderQuantity)
                // Explicit, because Product defaults to PENDING - moderation fails closed so an
                // unreviewed vendor listing can never become a real company's purchase order. A
                // fixture that skipped this would simply be unbuyable, and the failure ("not
                // available on the marketplace") points at the cart rather than at the default.
                .approvalStatus(ProductApprovalStatus.APPROVED)
                .build();
        TenantContext.set(platformOwner().getId());
        try {
            Product saved = productRepository.saveAndFlush(product);
            plantedCatalogProductIds.add(saved.getId());
            return saved;
        } finally {
            TenantContext.clear();
        }
    }

    /** Moves the catalog price so successive orders record genuinely different unit prices. */
    private void repriceCatalogProduct(UUID catalogProductId, BigDecimal unitPrice) {
        TenantContext.set(platformOwner().getId());
        try {
            Product product = productRepository.findById(catalogProductId).orElseThrow();
            product.setUnitPrice(unitPrice);
            productRepository.saveAndFlush(product);
        } finally {
            TenantContext.clear();
        }
    }
}
