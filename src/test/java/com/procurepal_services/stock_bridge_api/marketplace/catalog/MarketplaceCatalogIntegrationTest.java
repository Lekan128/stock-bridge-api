package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentMethod;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.AdminCatalogProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.AdminMarketplaceSettingsResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.BulkListingResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.CreateCategoryRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceCategoryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.MarketplaceProductResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.PublicMarketplaceSettingsResponse;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateCategoryRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateListingRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceDetailsRequest;
import com.procurepal_services.stock_bridge_api.marketplace.catalog.dto.UpdateMarketplaceSettingsRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductCategoryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * The public storefront catalog and ProcurePal's catalog admin, over the real HTTP +
 * Spring Security filter chain against the local docker-compose Postgres - see
 * AuthIntegrationTest for why local Postgres over Testcontainers. Requires
 * `docker compose up -d` at the project root.
 *
 * Two properties matter more than the rest and are tested first:
 *
 * 1. The public endpoints run with NO tenant context and therefore with the Hibernate
 *    tenant filter disabled. {@link #publicCatalogCannotSeeAnotherTenantsListedProduct}
 *    plants exactly the row that would leak if the explicit predicates were dropped.
 * 2. Every admin route needs BOTH MANAGE_MARKETPLACE and platform ownership.
 *    {@link #everyAdminRouteRefusesAnOrdinaryTenantsOwner} walks all ten of them with a
 *    freshly signed-up OWNER, who holds the permission and nothing else.
 *
 * Fixtures are created and torn down inside each test rather than left behind: this suite
 * shares one seeded database with every other integration test, and
 * MarketplaceFoundationIntegrationTest asserts globally that nobody but ProcurePal owns a
 * listed product. A leaked fixture would fail a test in another file, which is a
 * miserable thing to debug.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MarketplaceCatalogIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String CATALOG = "/api/marketplace/catalog";
    private static final String ADMIN = "/api/marketplace/admin";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    // ------------------------------------------------------------------------
    // (1) Tenant safety of the public catalog.
    // ------------------------------------------------------------------------

    /**
     * The whole reason MarketplaceProductSpecifications pins client_id by hand.
     *
     * An ordinary tenant's product is flagged is_marketplace_listed = TRUE and active -
     * a state nothing in the API can produce (the admin guard prevents it) but that a bad
     * migration, a support script or a future bug could. The public catalog must still not
     * show it, because the only thing standing between an anonymous GET and every tenant's
     * inventory here is the client_id predicate: there is no tenant context on these
     * requests, so the Hibernate filter is switched off.
     */
    @Test
    void publicCatalogCannotSeeAnotherTenantsListedProduct() {
        TenantLoginResponse intruder = signup("Leaky Tenant Co");
        UUID intruderClientId = clientIdOf(intruder);
        String sku = "LEAK-" + UUID.randomUUID().toString().substring(0, 8);

        Product planted = saveAs(
                intruderClientId,
                Product.builder()
                        .name("Definitely Not For Sale " + sku)
                        .sku(sku)
                        .slug("definitely-not-for-sale-" + sku.toLowerCase())
                        .unitPrice(new BigDecimal("1000.00"))
                        .quantityOnHand(50)
                        .active(true)
                        .marketplaceListed(true)
                        .build());
        try {
            // The grid, with a query that would find it if it were visible at all.
            assertThat(skusIn(getCatalog("?q=" + sku + "&size=100"))).isEmpty();
            assertThat(skusIn(getCatalog("?size=100"))).doesNotContain(sku);

            // Direct addressing, by id and by slug.
            assertThat(getStatus(CATALOG + "/" + planted.getId())).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(getStatus(CATALOG + "/" + planted.getSlug())).isEqualTo(HttpStatus.NOT_FOUND);

            // The batch form, which is the newest door and therefore the easiest to forget.
            assertThat(skusIn(getCatalog("?ids=" + planted.getId()))).isEmpty();
        } finally {
            deleteAs(intruderClientId, planted);
        }
    }

    /**
     * The paged JSON must stay byte-compatible with the frontend's PageResponse<T>
     * (src/features/products/types.ts), which useProducts and the storefront grid both
     * destructure. Spring's Page serialization has changed shape across Boot versions, so
     * this pins the seven fields the UI actually reads.
     */
    @Test
    void pagedCatalogMatchesTheExistingSpringPageJsonShape() {
        JsonNode body = getJson(CATALOG + "?size=5");

        assertThat(body.has("content")).isTrue();
        assertThat(body.get("content").isArray()).isTrue();
        assertThat(body.get("totalElements").isNumber()).isTrue();
        assertThat(body.get("totalPages").isNumber()).isTrue();
        assertThat(body.get("number").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(5);
        assertThat(body.get("first").asBoolean()).isTrue();
        assertThat(body.get("last").isBoolean()).isTrue();
    }

    /** No token, no tenant, no problem - the storefront is the shop window. */
    @Test
    void theSeededCatalogIsBrowsableAnonymously() {
        List<MarketplaceProductResponse> firstPage = getCatalog("?size=50");

        assertThat(firstPage).hasSizeGreaterThanOrEqualTo(25);
        assertThat(firstPage).allSatisfy(product -> {
            assertThat(product.name()).isNotBlank();
            assertThat(product.slug()).isNotBlank();
            assertThat(product.unitPrice()).isPositive();
            assertThat(product.minOrderQuantity()).isGreaterThanOrEqualTo(1);
        });
    }

    // ------------------------------------------------------------------------
    // (2) Filtering, sorting, pagination against the seeded 35-product catalog.
    // ------------------------------------------------------------------------

    @Test
    void searchMatchesNameSkuAndBrand() {
        MarketplaceProductResponse sample = getCatalog("?size=100").getFirst();

        assertThat(skusIn(getCatalog("?size=100&q=" + sample.sku()))).containsExactly(sample.sku());
        assertThat(skusIn(getCatalog("?size=100&q=" + firstWordOf(sample.name())))).contains(sample.sku());
        assertThat(getCatalog("?size=100&q=zzz-no-such-product-zzz")).isEmpty();
    }

    /** A bad enum or a non-UUID id off an edited URL is a 400, never a 500 or a confusing 403. */
    @Test
    void unparseableQueryParametersAreARequestError() {
        assertThat(getStatus(CATALOG + "?sort=CHEAPEST")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getStatus(CATALOG + "?ids=not-a-uuid")).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getStatus(CATALOG + "?minPrice=free")).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void categoryAndPriceFiltersNarrowTheGrid() {
        List<MarketplaceCategoryResponse> categories = categories();
        assertThat(categories).isNotEmpty();
        MarketplaceCategoryResponse category = categories.getFirst();

        List<MarketplaceProductResponse> inCategory = getCatalog("?size=100&categoryId=" + category.id());
        assertThat(inCategory).isNotEmpty();
        assertThat(inCategory).allSatisfy(p -> assertThat(p.categoryId()).isEqualTo(category.id()));
        // The public category list's productCount promises exactly this set.
        assertThat(inCategory).hasSize((int) category.productCount());

        assertThat(getCatalog("?size=100&minPrice=100000"))
                .allSatisfy(p -> assertThat(p.unitPrice()).isGreaterThanOrEqualTo(new BigDecimal("100000")));
        assertThat(getCatalog("?size=100&maxPrice=10000"))
                .allSatisfy(p -> assertThat(p.unitPrice()).isLessThanOrEqualTo(new BigDecimal("10000")));
        assertThat(getCatalog("?size=100&minPrice=1000000000")).isEmpty();
    }

    // ------------------------------------------------------------------------
    // (2b) Sellable stock: the storefront must not advertise goods already sold.
    // ------------------------------------------------------------------------

    /**
     * Stock leaves quantity_on_hand only at OUT_FOR_DELIVERY, so between payment and
     * dispatch the raw column still counts units that belong to somebody else. The public
     * catalog therefore projects SELLABLE stock - on-hand minus committed - and this pins
     * that end to end: another company's PLACED order, and the tile reads down.
     *
     * The committing order belongs to the demo tenant, not to ProcurePal, which is the
     * whole point: the commitment query is native SQL precisely because a JPQL join would
     * be filtered to the caller's own tenant and would miss exactly this row.
     *
     * Every surface is checked, not just the grid. A product that reads sold out on the
     * grid and in stock on its detail page is worse than either answer alone.
     */
    @Test
    void publicCatalogProjectsSellableStockNotRawOnHand() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Committed Stock Widget", 10);
        UUID buyerId = clientRepository.findBySlug("demo").orElseThrow().getId();
        Order committing = null;
        try {
            String slug = listProduct(operator, product).slug();
            assertThat(catalogQuantity(product)).isEqualTo(10);

            // Four bags sold to another company and awaiting dispatch.
            committing = placeCommittingOrder(buyerId, product, 4, OrderStatus.PLACED);

            MarketplaceProductResponse fromGrid = onlyMatch(getCatalog("?size=200&q=" + product.getSku()));
            assertThat(fromGrid.quantityOnHand()).isEqualTo(6);
            assertThat(fromGrid.inStock()).isTrue();

            // ...and every other surface agrees.
            assertThat(getOne(CATALOG + "/" + product.getId()).quantityOnHand()).isEqualTo(6);
            assertThat(getOne(CATALOG + "/" + slug).quantityOnHand()).isEqualTo(6);
            assertThat(onlyMatch(getCatalog("?ids=" + product.getId())).quantityOnHand())
                    .isEqualTo(6);

            // Raw on-hand is untouched: nothing has physically left the warehouse.
            assertThat(reload(product.getClientId(), product.getId()).getQuantityOnHand())
                    .isEqualTo(10);
        } finally {
            deleteOrder(buyerId, committing);
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * The commitment window is PLACED/CONFIRMED/PROCESSING. PENDING_PAYMENT is outside it
     * - an unpaid checkout is not a commitment, or anyone could empty the warehouse by
     * filling a cart - and OUT_FOR_DELIVERY onwards is outside it because those units have
     * already come off quantity_on_hand and counting them again would deduct twice.
     */
    @Test
    void onlyPaidUndispatchedOrdersCountAgainstStorefrontAvailability() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Commitment Window Widget", 10);
        UUID buyerId = clientRepository.findBySlug("demo").orElseThrow().getId();
        Order unpaid = null;
        Order dispatched = null;
        try {
            listProduct(operator, product);

            unpaid = placeCommittingOrder(buyerId, product, 3, OrderStatus.PENDING_PAYMENT);
            dispatched = placeCommittingOrder(buyerId, product, 3, OrderStatus.OUT_FOR_DELIVERY);

            assertThat(catalogQuantity(product)).isEqualTo(10);
        } finally {
            deleteOrder(buyerId, unpaid);
            deleteOrder(buyerId, dispatched);
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * Fully committed is fully unbuyable, and the storefront has to say so in both places
     * it can: the tile reads out of stock, and inStockOnly stops returning it. The filter
     * is a SQL predicate rather than a post-filter on the page, so totalElements has to
     * come down too - otherwise a filtered grid silently loses a row per page.
     */
    @Test
    void aFullyCommittedProductReadsOutOfStockAndDropsOutOfTheInStockFilter() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Fully Committed Widget", 5);
        UUID buyerId = clientRepository.findBySlug("demo").orElseThrow().getId();
        Order committing = null;
        try {
            listProduct(operator, product);
            assertThat(skusIn(getCatalog("?size=200&inStockOnly=true&q=" + product.getSku())))
                    .containsExactly(product.getSku());

            committing = placeCommittingOrder(buyerId, product, 5, OrderStatus.CONFIRMED);

            MarketplaceProductResponse soldOut = onlyMatch(getCatalog("?size=200&q=" + product.getSku()));
            assertThat(soldOut.quantityOnHand()).isZero();
            assertThat(soldOut.inStock()).isFalse();

            JsonNode filtered = getJson(CATALOG + "?size=200&inStockOnly=true&q=" + product.getSku());
            assertThat(filtered.get("content").size()).isZero();
            assertThat(filtered.get("totalElements").asInt()).isZero();
        } finally {
            deleteOrder(buyerId, committing);
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * ProcurePal's own screen shows all three numbers, because their three jobs need
     * different ones: count the shelf, pick the orders, and see what the shop is
     * advertising. See AdminCatalogProductResponse.
     */
    @Test
    void adminSeesPhysicalCommittedAndSellableStockSeparately() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Admin Stock Widget", 10);
        UUID buyerId = clientRepository.findBySlug("demo").orElseThrow().getId();
        Order committing = null;
        try {
            committing = placeCommittingOrder(buyerId, product, 4, OrderStatus.PROCESSING);

            AdminCatalogProductResponse fromList = adminBody(
                            operator,
                            HttpMethod.GET,
                            ADMIN + "/products?size=200&q=" + product.getSku(),
                            null,
                            AdminProductPage.class)
                    .content()
                    .getFirst();

            assertThat(fromList.quantityOnHand()).isEqualTo(10);
            assertThat(fromList.committedQuantity()).isEqualTo(4);
            assertThat(fromList.availableToSell()).isEqualTo(6);

            // The single-product write path projects the same three numbers as the list.
            AdminCatalogProductResponse fromWrite = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/" + product.getId() + "/listing",
                    new UpdateListingRequest(true), AdminCatalogProductResponse.class);
            assertThat(fromWrite.quantityOnHand()).isEqualTo(10);
            assertThat(fromWrite.committedQuantity()).isEqualTo(4);
            assertThat(fromWrite.availableToSell()).isEqualTo(6);
        } finally {
            deleteOrder(buyerId, committing);
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * The seed deliberately contains an out-of-stock product (PP-KE-002), so both halves
     * of this are real: unfiltered browsing shows it (contract §10 - shown but not
     * purchasable) and inStockOnly removes it.
     */
    @Test
    void inStockOnlyRemovesUnbuyableProductsThatBrowsingStillShows() {
        List<MarketplaceProductResponse> everything = getCatalog("?size=100");
        List<MarketplaceProductResponse> buyable = getCatalog("?size=100&inStockOnly=true");

        assertThat(everything).anySatisfy(p -> assertThat(p.inStock()).isFalse());
        assertThat(buyable).isNotEmpty();
        assertThat(buyable).allSatisfy(p -> {
            assertThat(p.inStock()).isTrue();
            assertThat(p.quantityOnHand()).isPositive();
        });
        assertThat(buyable.size()).isLessThan(everything.size());
    }

    @Test
    void allFiveSortsOrderTheCatalogAsAdvertised() {
        assertThat(getCatalog("?size=100&sort=PRICE_ASC"))
                .isSortedAccordingTo(Comparator.comparing(MarketplaceProductResponse::unitPrice));
        assertThat(getCatalog("?size=100&sort=PRICE_DESC"))
                .isSortedAccordingTo(Comparator.comparing(MarketplaceProductResponse::unitPrice).reversed());
        assertThat(getCatalog("?size=100&sort=NAME_ASC"))
                .isSortedAccordingTo(Comparator.comparing(p -> p.name().toLowerCase()));

        // NEWEST has no exposed timestamp to assert on, so this checks the contract it
        // does make: the same set of products, in a different order to NAME_ASC.
        List<String> newest = skusIn(getCatalog("?size=100&sort=NEWEST"));
        List<String> byName = skusIn(getCatalog("?size=100&sort=NAME_ASC"));
        assertThat(newest).containsExactlyInAnyOrderElementsOf(byName);

        // RELEVANCE (the default) puts everything purchasable ahead of everything that
        // is not - see CatalogSort.RELEVANCE for why availability beats text ranking here.
        List<MarketplaceProductResponse> relevance = getCatalog("?size=100");
        int firstOutOfStock = indexOfFirstOutOfStock(relevance);
        assertThat(firstOutOfStock).as("the seed must contain an out-of-stock product").isPositive();
        assertThat(relevance.subList(firstOutOfStock, relevance.size()))
                .allSatisfy(p -> assertThat(p.inStock()).isFalse());
    }

    @Test
    void paginationWalksTheCatalogWithoutRepeatingOrDroppingProducts() {
        JsonNode page0 = getJson(CATALOG + "?size=10&page=0&sort=NAME_ASC");
        JsonNode page1 = getJson(CATALOG + "?size=10&page=1&sort=NAME_ASC");

        assertThat(page0.get("size").asInt()).isEqualTo(10);
        assertThat(page0.get("content").size()).isEqualTo(10);
        assertThat(page0.get("totalElements").asInt()).isGreaterThan(10);
        assertThat(page1.get("number").asInt()).isEqualTo(1);
        assertThat(page1.get("first").asBoolean()).isFalse();

        List<String> combined = skusIn(getCatalog("?size=10&page=0&sort=NAME_ASC"));
        List<String> second = skusIn(getCatalog("?size=10&page=1&sort=NAME_ASC"));
        assertThat(combined).doesNotContainAnyElementsOf(second);
    }

    // ------------------------------------------------------------------------
    // (3) Batch lookup, detail by id-or-slug, related.
    // ------------------------------------------------------------------------

    /** The N+1 the anonymous cart used to make: one GET per line, now one GET per cart. */
    @Test
    void batchIdsLookupReturnsOnlyTheListedProductsAmongThemAndIgnoresTheRest() {
        List<MarketplaceProductResponse> some = getCatalog("?size=3");
        String ids = some.stream().map(p -> p.id().toString()).reduce((a, b) -> a + "," + b).orElseThrow();

        List<MarketplaceProductResponse> found = getCatalog("?ids=" + ids);
        assertThat(skusIn(found)).containsExactlyInAnyOrderElementsOf(skusIn(some));

        // An id that never existed is dropped, not a 400: a localStorage cart legitimately
        // outlives the catalog, and erroring would leave a shopper unable to open it.
        List<MarketplaceProductResponse> withGarbage = getCatalog("?ids=" + ids + "," + UUID.randomUUID());
        assertThat(skusIn(withGarbage)).containsExactlyInAnyOrderElementsOf(skusIn(some));
        assertThat(getCatalog("?ids=" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void productDetailAcceptsEitherAUuidOrASlugAndFourOhFoursOnNeither() {
        MarketplaceProductResponse sample = getCatalog("?size=1").getFirst();

        MarketplaceProductResponse byId = getOne(CATALOG + "/" + sample.id());
        MarketplaceProductResponse bySlug = getOne(CATALOG + "/" + sample.slug());
        assertThat(byId.id()).isEqualTo(sample.id());
        assertThat(bySlug.id()).isEqualTo(sample.id());

        assertThat(getStatus(CATALOG + "/no-such-slug-at-all")).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getStatus(CATALOG + "/" + UUID.randomUUID())).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void relatedProductsShareTheCategoryAndExcludeTheProductItself() {
        // The busiest category, so "same category minus itself" has something to return.
        MarketplaceCategoryResponse busiest = categories().stream()
                .max(Comparator.comparingLong(MarketplaceCategoryResponse::productCount))
                .orElseThrow();
        assertThat(busiest.productCount()).isGreaterThan(1);
        MarketplaceProductResponse sample = getCatalog("?size=100&categoryId=" + busiest.id()).getFirst();

        ResponseEntity<List<MarketplaceProductResponse>> response = restTemplate.exchange(
                CATALOG + "/" + sample.slug() + "/related",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {});

        List<MarketplaceProductResponse> related = response.getBody();
        assertThat(related).isNotNull().isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(related).allSatisfy(p -> {
            assertThat(p.id()).isNotEqualTo(sample.id());
            assertThat(p.categoryId()).isEqualTo(sample.categoryId());
        });
    }

    // ------------------------------------------------------------------------
    // (4) Public categories and settings.
    // ------------------------------------------------------------------------

    @Test
    void publicCategoriesCarryTheCountOfProductsABuyerWouldActuallyFind() {
        List<MarketplaceCategoryResponse> categories = categories();

        assertThat(categories).hasSizeGreaterThanOrEqualTo(4);
        assertThat(categories).allSatisfy(category -> {
            assertThat(category.name()).isNotBlank();
            assertThat(category.slug()).isNotBlank();
            // Empty categories are dropped - clicking through to an empty grid reads as a
            // broken site.
            assertThat(category.productCount()).isPositive();
        });
        assertThat(categories).isSortedAccordingTo(Comparator.comparingInt(MarketplaceCategoryResponse::sortOrder));
    }

    /**
     * The public settings payload is an allowlist. pay_on_delivery_max_order_value is
     * ProcurePal's trust limit and is deliberately not in it - an anonymous visitor who
     * knew it would know exactly how large an unpaid order they could place.
     */
    @Test
    void publicSettingsExposeTheCommercialSubsetAndNothingElse() {
        PublicMarketplaceSettingsResponse settings = restTemplate.getForObject(
                "/api/marketplace/settings", PublicMarketplaceSettingsResponse.class);

        assertThat(settings).isNotNull();
        assertThat(settings.deliveryFee()).isNotNull();
        assertThat(settings.freeDeliveryThreshold()).isNotNull();

        JsonNode raw = getJson("/api/marketplace/settings");
        assertThat(raw.has("payOnDeliveryEnabled")).isTrue();
        assertThat(raw.has("payOnDeliveryMaxOrderValue")).isFalse();
        assertThat(raw.has("id")).isFalse();
    }

    // ------------------------------------------------------------------------
    // (5) Admin: platform ownership is checked independently of the permission.
    // ------------------------------------------------------------------------

    /**
     * The freshly signed-up owner below holds MANAGE_MARKETPLACE - every OWNER does,
     * because permissions hang off global roles - so @PreAuthorize alone lets them
     * straight through and only PlatformOwnerGuard produces the 403. Every route is walked
     * rather than a representative one: the failure mode being defended against is a new
     * endpoint that forgot the guard, which only a per-route assertion catches.
     */
    @Test
    void everyAdminRouteRefusesAnOrdinaryTenantsOwner() {
        TenantLoginResponse outsider = signup("Wants To Sell Co");
        assertThat(outsider.user().permissions()).contains("MANAGE_MARKETPLACE");
        assertThat(outsider.user().platformOwner()).isFalse();
        UUID anyId = UUID.randomUUID();

        assertForbidden(outsider, HttpMethod.GET, ADMIN + "/products", null);
        assertForbidden(outsider, HttpMethod.POST, ADMIN + "/products/" + anyId + "/listing",
                new UpdateListingRequest(true));
        assertForbidden(outsider, HttpMethod.POST, ADMIN + "/products/bulk-listing",
                new BulkListingRequest(List.of(anyId), true));
        assertForbidden(outsider, HttpMethod.PUT, ADMIN + "/products/" + anyId + "/marketplace-details",
                new UpdateMarketplaceDetailsRequest(null, null, "carton", 1, null, null));
        assertForbidden(outsider, HttpMethod.GET, ADMIN + "/categories", null);
        assertForbidden(outsider, HttpMethod.POST, ADMIN + "/categories",
                new CreateCategoryRequest("Sneaky", null, null, null, null));
        assertForbidden(outsider, HttpMethod.PUT, ADMIN + "/categories/" + anyId,
                new UpdateCategoryRequest("Sneaky", null, null, null, null, null));
        assertForbidden(outsider, HttpMethod.DELETE, ADMIN + "/categories/" + anyId, null);
        assertForbidden(outsider, HttpMethod.GET, ADMIN + "/settings", null);
        assertForbidden(outsider, HttpMethod.PUT, ADMIN + "/settings", validSettings());
    }

    /** And the same routes are reachable for ProcurePal, so the test above is proving the guard, not a typo. */
    @Test
    void thePlatformOwnerReachesTheSameRoutes() {
        TenantLoginResponse operator = loginAsOperator();

        assertThat(exchange(operator, HttpMethod.GET, ADMIN + "/products?size=5", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(operator, HttpMethod.GET, ADMIN + "/categories", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(operator, HttpMethod.GET, ADMIN + "/settings", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------------
    // (6) Admin: listing, marketplace details, slugs.
    // ------------------------------------------------------------------------

    @Test
    void listingAProductPutsItOnTheStorefrontAndUnlistingTakesItOff() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Test Listing Widget");
        try {
            // Not listed yet, so invisible to the public catalog.
            assertThat(skusIn(getCatalog("?size=200&q=" + product.getSku()))).isEmpty();

            AdminCatalogProductResponse listed = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/" + product.getId() + "/listing",
                    new UpdateListingRequest(true), AdminCatalogProductResponse.class);
            assertThat(listed.listed()).isTrue();
            // Listing derives a slug, because the storefront routes by one. startsWith
            // rather than isEqualTo: a crashed earlier run could have left the exact slug
            // behind, in which case the -2 suffix is correct behaviour, not a failure.
            assertThat(listed.slug()).startsWith("test-listing-widget");
            assertThat(skusIn(getCatalog("?size=200&q=" + product.getSku()))).containsExactly(product.getSku());

            AdminCatalogProductResponse unlisted = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/" + product.getId() + "/listing",
                    new UpdateListingRequest(false), AdminCatalogProductResponse.class);
            assertThat(unlisted.listed()).isFalse();
            assertThat(skusIn(getCatalog("?size=200&q=" + product.getSku()))).isEmpty();
        } finally {
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * The invariant with no CHECK constraint behind it: is_marketplace_listed is only ever
     * true on the platform owner's own rows. ProcurePal is authenticated and authorized
     * here; the product simply is not theirs, and the ownedBy() predicate turns that into
     * a 404 rather than a listing.
     */
    @Test
    void thePlatformOwnerCannotListAProductBelongingToAnotherTenant() {
        TenantLoginResponse operator = loginAsOperator();
        TenantLoginResponse other = signup("Someone Elses Stock Co");
        UUID otherClientId = clientIdOf(other);
        Product theirs = saveAs(
                otherClientId,
                Product.builder()
                        .name("Their Widget")
                        .sku("THEIRS-" + UUID.randomUUID().toString().substring(0, 8))
                        .unitPrice(new BigDecimal("500.00"))
                        .quantityOnHand(10)
                        .active(true)
                        .build());
        try {
            ResponseEntity<String> response = exchange(
                    operator, HttpMethod.POST, ADMIN + "/products/" + theirs.getId() + "/listing",
                    new UpdateListingRequest(true));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

            assertThat(reload(otherClientId, theirs.getId()).isMarketplaceListed()).isFalse();
        } finally {
            deleteAs(otherClientId, theirs);
        }
    }

    @Test
    void marketplaceDetailsUpdateCategoryUnitOfMeasureMoqBrandAndSlug() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Details Widget");
        UUID categoryId = categories().getFirst().id();
        try {
            AdminCatalogProductResponse updated = adminBody(
                    operator, HttpMethod.PUT, ADMIN + "/products/" + product.getId() + "/marketplace-details",
                    new UpdateMarketplaceDetailsRequest(
                            categoryId, null, "carton (24)", 5, "Test Brand", "details-widget-custom"),
                    AdminCatalogProductResponse.class);

            assertThat(updated.categoryId()).isEqualTo(categoryId);
            assertThat(updated.unitOfMeasure()).isEqualTo("carton (24)");
            assertThat(updated.minOrderQuantity()).isEqualTo(5);
            assertThat(updated.brand()).isEqualTo("Test Brand");
            assertThat(updated.slug()).isEqualTo("details-widget-custom");

            // MOQ below the CHECK constraint's floor is a field-level 400, not a 500.
            ResponseEntity<ApiError> invalid = restTemplate.exchange(
                    ADMIN + "/products/" + product.getId() + "/marketplace-details",
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateMarketplaceDetailsRequest(null, null, null, 0, null, null),
                            authHeaders(operator)),
                    ApiError.class);
            assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            deleteAs(product.getClientId(), product);
        }
    }

    /**
     * A slug someone typed is usually a slug something already links to, so a collision is
     * a 409 rather than a silent rename. An auto-derived one behaves the opposite way -
     * see the -2 suffix below.
     */
    @Test
    void slugsAreUniquePerTenantAndCollisionsAreDeterministic() {
        TenantLoginResponse operator = loginAsOperator();
        // Two products sharing a name, unique per run so nothing a crashed earlier run left
        // behind can shift the suffix and make this assert the wrong thing.
        String sharedName = "Duplicate Widget " + UUID.randomUUID().toString().substring(0, 8);
        Product first = plantOperatorProduct(sharedName);
        Product second = plantOperatorProduct(sharedName);
        try {
            AdminCatalogProductResponse listedFirst = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/" + first.getId() + "/listing",
                    new UpdateListingRequest(true), AdminCatalogProductResponse.class);
            assertThat(listedFirst.slug()).isEqualTo(sharedName.toLowerCase().replace(' ', '-'));

            // Deterministic, not random: the same collision always resolves the same way.
            AdminCatalogProductResponse listedSecond = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/" + second.getId() + "/listing",
                    new UpdateListingRequest(true), AdminCatalogProductResponse.class);
            assertThat(listedSecond.slug()).isEqualTo(listedFirst.slug() + "-2");

            // A slug the operator typed collides loudly instead of being silently renamed.
            ResponseEntity<String> conflict = exchange(
                    operator, HttpMethod.PUT, ADMIN + "/products/" + second.getId() + "/marketplace-details",
                    new UpdateMarketplaceDetailsRequest(null, null, null, null, null, listedFirst.slug()));
            assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            deleteAs(first.getClientId(), first);
            deleteAs(second.getClientId(), second);
        }
    }

    @Test
    void bulkListingReportsWhatItChangedAndWhatItSkipped() {
        TenantLoginResponse operator = loginAsOperator();
        Product a = plantOperatorProduct("Bulk Widget A");
        Product b = plantOperatorProduct("Bulk Widget B");
        UUID unknown = UUID.randomUUID();
        try {
            BulkListingResponse result = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/bulk-listing",
                    new BulkListingRequest(List.of(a.getId(), b.getId(), unknown), true), BulkListingResponse.class);

            assertThat(result.updated()).isEqualTo(2);
            assertThat(result.skipped()).containsExactly(unknown);

            // Re-running is a no-op that says so, rather than reporting phantom work.
            BulkListingResponse again = adminBody(
                    operator, HttpMethod.POST, ADMIN + "/products/bulk-listing",
                    new BulkListingRequest(List.of(a.getId(), b.getId()), true), BulkListingResponse.class);
            assertThat(again.updated()).isZero();
            assertThat(again.alreadyInState()).isEqualTo(2);
        } finally {
            deleteAs(a.getClientId(), a);
            deleteAs(b.getClientId(), b);
        }
    }

    @Test
    void adminProductListShowsUnlistedProductsAndFiltersOnListedState() {
        TenantLoginResponse operator = loginAsOperator();
        Product product = plantOperatorProduct("Unlisted Admin Widget");
        try {
            JsonNode unlisted = adminJson(operator, ADMIN + "/products?size=200&listed=false&q=" + product.getSku());
            assertThat(unlisted.get("totalElements").asInt()).isEqualTo(1);

            JsonNode listedOnly = adminJson(operator, ADMIN + "/products?size=200&listed=true&q=" + product.getSku());
            assertThat(listedOnly.get("totalElements").asInt()).isZero();
        } finally {
            deleteAs(product.getClientId(), product);
        }
    }

    // ------------------------------------------------------------------------
    // (7) Admin: categories and settings.
    // ------------------------------------------------------------------------

    @Test
    void categoriesAreCreatedUpdatedAndDeletedWhileEmpty() {
        TenantLoginResponse operator = loginAsOperator();
        String name = "Test Category " + UUID.randomUUID().toString().substring(0, 8);

        MarketplaceCategoryResponse created = adminBody(
                operator, HttpMethod.POST, ADMIN + "/categories",
                new CreateCategoryRequest(name, null, null, 99, null), MarketplaceCategoryResponse.class);
        try {
            assertThat(created.slug()).isEqualTo(name.toLowerCase().replace(' ', '-'));
            assertThat(created.active()).isTrue();
            assertThat(created.productCount()).isZero();

            MarketplaceCategoryResponse updated = adminBody(
                    operator, HttpMethod.PUT, ADMIN + "/categories/" + created.id(),
                    new UpdateCategoryRequest(name + " Renamed", null, null, null, 5, false),
                    MarketplaceCategoryResponse.class);
            assertThat(updated.name()).endsWith("Renamed");
            assertThat(updated.sortOrder()).isEqualTo(5);
            assertThat(updated.active()).isFalse();

            // An inactive category is off the storefront menu but its id still filters.
            assertThat(categories()).noneSatisfy(c -> assertThat(c.id()).isEqualTo(created.id()));
        } finally {
            exchange(operator, HttpMethod.DELETE, ADMIN + "/categories/" + created.id(), null);
        }
        assertThat(productCategoryRepository.findById(created.id())).isEmpty();
    }

    /**
     * Deleting a category whose products still point at it is blocked, not allowed to
     * silently uncategorise them - the FK is ON DELETE SET NULL, so the database would
     * have accepted it. See CategoryInUseException.
     */
    @Test
    void deletingACategoryThatStillHasProductsIsRefused() {
        TenantLoginResponse operator = loginAsOperator();
        MarketplaceCategoryResponse populated = categories().getFirst();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                ADMIN + "/categories/" + populated.id(),
                HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(operator)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).contains("deactivate the category instead");
        assertThat(productCategoryRepository.findById(populated.id())).isPresent();
    }

    @Test
    void settingsRoundTripAndRejectNonsense() {
        TenantLoginResponse operator = loginAsOperator();
        AdminMarketplaceSettingsResponse original =
                adminBody(operator, HttpMethod.GET, ADMIN + "/settings", null, AdminMarketplaceSettingsResponse.class);
        try {
            AdminMarketplaceSettingsResponse saved = adminBody(
                    operator, HttpMethod.PUT, ADMIN + "/settings",
                    new UpdateMarketplaceSettingsRequest(
                            new BigDecimal("3000.00"),
                            new BigDecimal("200000.00"),
                            new BigDecimal("5000.00"),
                            true,
                            new BigDecimal("400000.00"),
                            "+2348000000000",
                            "help@procurepal.ng"),
                    AdminMarketplaceSettingsResponse.class);
            assertThat(saved.deliveryFee()).isEqualByComparingTo("3000.00");
            assertThat(saved.payOnDeliveryMaxOrderValue()).isEqualByComparingTo("400000.00");

            // And the storefront sees the change, minus the withheld field.
            PublicMarketplaceSettingsResponse publicView = restTemplate.getForObject(
                    "/api/marketplace/settings", PublicMarketplaceSettingsResponse.class);
            assertThat(publicView.deliveryFee()).isEqualByComparingTo("3000.00");

            // Field-level: negative money.
            assertThat(putSettingsStatus(operator, new UpdateMarketplaceSettingsRequest(
                            new BigDecimal("-1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                            false, new BigDecimal("1000.00"), null, null)))
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // Cross-field: COD switched on with a cap nothing could ever qualify for.
            assertThat(putSettingsStatus(operator, new UpdateMarketplaceSettingsRequest(
                            new BigDecimal("2500.00"), BigDecimal.ZERO, new BigDecimal("50000.00"),
                            true, new BigDecimal("1000.00"), null, null)))
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // Cross-field: a free-delivery threshold when delivery is already always free.
            assertThat(putSettingsStatus(operator, new UpdateMarketplaceSettingsRequest(
                            BigDecimal.ZERO, new BigDecimal("100000.00"), BigDecimal.ZERO,
                            false, new BigDecimal("1000.00"), null, null)))
                    .isEqualTo(HttpStatus.BAD_REQUEST);

            // A malformed support email is a field error, not a 500.
            assertThat(putSettingsStatus(operator, new UpdateMarketplaceSettingsRequest(
                            new BigDecimal("2500.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                            false, new BigDecimal("1000.00"), null, "not-an-email")))
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            // Other suites (checkout, payments) read this single row, so it has to go back.
            adminBody(operator, HttpMethod.PUT, ADMIN + "/settings",
                    new UpdateMarketplaceSettingsRequest(
                            original.deliveryFee(),
                            original.freeDeliveryThreshold(),
                            original.minimumOrderValue(),
                            original.payOnDeliveryEnabled(),
                            original.payOnDeliveryMaxOrderValue(),
                            original.supportPhone(),
                            original.supportEmail()),
                    AdminMarketplaceSettingsResponse.class);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private List<MarketplaceProductResponse> getCatalog(String queryString) {
        ResponseEntity<TestPage<MarketplaceProductResponse>> response = restTemplate.exchange(
                CATALOG + queryString, HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).as("GET %s", CATALOG + queryString).isEqualTo(HttpStatus.OK);
        return response.getBody().content();
    }

    private MarketplaceProductResponse getOne(String path) {
        return restTemplate.getForObject(path, MarketplaceProductResponse.class);
    }

    private List<MarketplaceCategoryResponse> categories() {
        ResponseEntity<List<MarketplaceCategoryResponse>> response = restTemplate.exchange(
                "/api/marketplace/categories", HttpMethod.GET, HttpEntity.EMPTY, new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    private List<String> skusIn(List<MarketplaceProductResponse> products) {
        return products.stream().map(MarketplaceProductResponse::sku).toList();
    }

    private int indexOfFirstOutOfStock(List<MarketplaceProductResponse> products) {
        for (int i = 0; i < products.size(); i++) {
            if (!products.get(i).inStock()) {
                return i;
            }
        }
        return -1;
    }

    private HttpStatus getStatus(String path) {
        return (HttpStatus) restTemplate.getForEntity(path, String.class).getStatusCode();
    }

    private JsonNode getJson(String path) {
        return restTemplate.getForObject(path, JsonNode.class);
    }

    private JsonNode adminJson(TenantLoginResponse as, String path) {
        return restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(as)), JsonNode.class)
                .getBody();
    }

    private ResponseEntity<String> exchange(TenantLoginResponse as, HttpMethod method, String path, Object body) {
        HttpHeaders headers = authHeaders(as);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private <T> T adminBody(TenantLoginResponse as, HttpMethod method, String path, Object body, Class<T> type) {
        ResponseEntity<T> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(as)), type);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("%s %s -> %s", method, path, response.getStatusCode())
                .isTrue();
        return response.getBody();
    }

    private void assertForbidden(TenantLoginResponse as, HttpMethod method, String path, Object body) {
        ResponseEntity<ApiError> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(as)), ApiError.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).contains("marketplace operator");
    }

    private HttpStatus putSettingsStatus(TenantLoginResponse as, UpdateMarketplaceSettingsRequest request) {
        return (HttpStatus) exchange(as, HttpMethod.PUT, ADMIN + "/settings", request).getStatusCode();
    }

    private UpdateMarketplaceSettingsRequest validSettings() {
        return new UpdateMarketplaceSettingsRequest(
                new BigDecimal("2500.00"),
                new BigDecimal("150000.00"),
                BigDecimal.ZERO,
                true,
                new BigDecimal("500000.00"),
                null,
                "support@procurepal.ng");
    }

    /**
     * A product owned by ProcurePal but created outside the marketplace API, so listing it
     * is a real state transition rather than a fixture that arrived pre-listed. Always
     * torn down: MarketplaceFoundationIntegrationTest sweeps every product row.
     */
    private Product plantOperatorProduct(String name) {
        return plantOperatorProduct(name, 25);
    }

    private Product plantOperatorProduct(String name, int quantityOnHand) {
        UUID operatorId = clientRepository.findByPlatformOwnerTrue().orElseThrow().getId();
        return saveAs(
                operatorId,
                Product.builder()
                        .name(name)
                        .sku("TST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                        .unitPrice(new BigDecimal("7500.00"))
                        .quantityOnHand(quantityOnHand)
                        .active(true)
                        .build());
    }

    /** Returns the response because listing GENERATES the slug - the caller's Product object is stale without it. */
    private AdminCatalogProductResponse listProduct(TenantLoginResponse operator, Product product) {
        return adminBody(
                operator, HttpMethod.POST, ADMIN + "/products/" + product.getId() + "/listing",
                new UpdateListingRequest(true), AdminCatalogProductResponse.class);
    }

    /** Sellable stock as the public grid reports it, for a fixture product listed by this test. */
    private int catalogQuantity(Product product) {
        return onlyMatch(getCatalog("?size=200&q=" + product.getSku())).quantityOnHand();
    }

    private MarketplaceProductResponse onlyMatch(List<MarketplaceProductResponse> products) {
        assertThat(products).hasSize(1);
        return products.getFirst();
    }

    /**
     * A BUYER's order that commits units of one of ProcurePal's catalog products, written
     * straight through the repositories rather than through the checkout API.
     *
     * Deliberate: this suite is testing the catalog's projection of stock, not M4's
     * checkout, and going through checkout would drag in carts, addresses, payment terms
     * and a Monnify stub to arrange one number. Writing the rows directly also lets a test
     * park an order in PENDING_PAYMENT or OUT_FOR_DELIVERY, which the state machine will
     * not let a test walk to cheaply.
     *
     * Orders are tenant-scoped, so this runs inside the buyer's TenantContext - and
     * order_items.product_id points at the PLATFORM OWNER's product, which is exactly the
     * cross-tenant reference the commitment query has to see.
     */
    private Order placeCommittingOrder(UUID buyerId, Product catalogProduct, int quantity, OrderStatus status) {
        TenantContext.set(buyerId);
        try {
            BigDecimal lineTotal = catalogProduct.getUnitPrice().multiply(BigDecimal.valueOf(quantity));
            Order order = orderRepository.saveAndFlush(Order.builder()
                    .orderNumber("PP-STOCKTEST-" + UUID.randomUUID().toString().substring(0, 8))
                    .status(status)
                    .paymentStatus(
                            status == OrderStatus.PENDING_PAYMENT ? PaymentStatus.PENDING : PaymentStatus.ON_DELIVERY)
                    .paymentMethod(PaymentMethod.PAY_ON_DELIVERY)
                    .currency("NGN")
                    .subtotal(lineTotal)
                    .deliveryFee(BigDecimal.ZERO)
                    .total(lineTotal)
                    .build());
            orderItemRepository.saveAndFlush(OrderItem.builder()
                    .order(order)
                    .productId(catalogProduct.getId())
                    .productName(catalogProduct.getName())
                    .productSku(catalogProduct.getSku())
                    .unitPrice(catalogProduct.getUnitPrice())
                    .quantity(quantity)
                    .lineTotal(lineTotal)
                    .build());
            return order;
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Torn down, not left behind. A PLACED order commits stock for as long as it exists
     * and the local Postgres is never reset, so a leaked fixture would quietly drain the
     * seeded catalog and break an unrelated suite days later.
     */
    private void deleteOrder(UUID buyerId, Order order) {
        if (order == null) {
            return;
        }
        TenantContext.set(buyerId);
        try {
            // order_items cascades from orders in the schema.
            orderRepository.deleteById(order.getId());
            orderRepository.flush();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * TenantAwareEntity's @PrePersist takes client_id from TenantContext and nowhere else,
     * so a fixture for a specific tenant has to be written inside a context - the same
     * technique TenantIsolationIntegrationTest uses.
     */
    private Product saveAs(UUID clientId, Product product) {
        TenantContext.set(clientId);
        try {
            return productRepository.saveAndFlush(product);
        } finally {
            TenantContext.clear();
        }
    }

    private Product reload(UUID clientId, UUID productId) {
        TenantContext.set(clientId);
        try {
            return productRepository.findById(productId).orElseThrow();
        } finally {
            TenantContext.clear();
        }
    }

    private void deleteAs(UUID clientId, Product product) {
        TenantContext.set(clientId);
        try {
            productRepository.deleteById(product.getId());
            productRepository.flush();
        } finally {
            TenantContext.clear();
        }
    }

    private UUID clientIdOf(TenantLoginResponse response) {
        return clientRepository
                .findBySlug(response.user().clientIdentifier())
                .map(Client::getId)
                .orElseThrow();
    }

    private TenantLoginResponse loginAsOperator() {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }

    /** Query strings here are hand-built, so searches use one URL-safe token rather than a whole product name. */
    private String firstWordOf(String name) {
        for (String token : name.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z0-9]", "");
            if (cleaned.length() >= 3) {
                return cleaned;
            }
        }
        throw new IllegalStateException("No searchable token in '" + name + "'");
    }

    /** Only the field the assertions read - see ProductManagementIntegrationTest for the same shim. */
    private record TestPage<T>(List<T> content) {
    }

    /**
     * A concrete page type for the admin list. TestPage&lt;AdminCatalogProductResponse&gt;
     * would need a ParameterizedTypeReference, and adminBody() takes a Class - one named
     * record is cheaper than a second overload.
     */
    private record AdminProductPage(List<AdminCatalogProductResponse> content) {
    }
}
