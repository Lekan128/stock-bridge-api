package com.procurepal_services.stock_bridge_api.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.address.DeliveryAddressService;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressRequest;
import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.MarketplaceAnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.UserManagementService;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorOrderStatusCount;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorRevenuePoint;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorSalesPeriodMetrics;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorSalesSummaryResponse;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorStockOutEntry;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorTopProductEntry;
import com.procurepal_services.stock_bridge_api.vendor.catalogue.dto.UpdateVendorMarketplaceDetailsRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The vendor's own workspace, end to end over real HTTP: own-sales analytics,
 * pickup addresses, the single-account invariant, and the seller-scoped order
 * queue seen from a vendor rather than from ProcurePal.
 *
 * <h2>What this class is really testing</h2>
 * One property, from five directions: <b>a vendor sees its own data and nothing
 * else, and ProcurePal's behaviour is unchanged</b>. VENDOR_RESEARCH.md Section C
 * item 7 calls the first cross-vendor leak a commercial incident, and the shape of
 * that leak is not an exception - it is a 200 with somebody else's numbers in it.
 * So almost every test below plants a SECOND seller's data in the same window and
 * asserts it is absent, rather than asserting that the caller's own data is
 * present. A test that only checked the latter would pass just as happily against
 * an unscoped query.
 *
 * <h2>Why the fixtures are raw SQL</h2>
 * Same reason {@code MarketplaceAnalyticsIntegrationTest} gives, and the helpers
 * are deliberately shaped like its: every figure is a function of WHEN an order
 * happened, and {@code orders.created_at} is a {@code @CreationTimestamp} column
 * JPA will not let a caller set. Planting with JdbcTemplate is the only way to
 * write a history with known dates, and it sidesteps needing a TenantContext to
 * write a buyer's row. Every planted order carries {@link #FIXTURE_PREFIX} so
 * {@code @AfterEach} removes exactly what this class created - the local Postgres
 * is shared and never reset, and a PLACED order commits catalog stock
 * indefinitely.
 *
 * <h2>Why the window is in the past</h2>
 * Anchored to a fixed historical month nothing else in the suite writes to, so the
 * other order tests' fixtures cannot drift into these assertions or vice versa.
 * A different month from the marketplace analytics test's, for the same reason.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration
 * test here - see AuthIntegrationTest for why local Postgres over Testcontainers.
 * Requires `docker compose up -d` at the project root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class VendorWorkspaceIntegrationTest {

    private static final String ANALYTICS = "/api/vendor/analytics";
    private static final String PICKUP = "/api/vendor/pickup-addresses";
    private static final String FIXTURE_PREFIX = "PP-VWRK-";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** May 2018: safely before anything else the suite writes, and a clean 31-day month. */
    private static final String RANGE = "?from=2018-05-01T00:00:00Z&to=2018-06-01T00:00:00Z";

    private static final OffsetDateTime DAY_FOUR = OffsetDateTime.parse("2018-05-04T09:00:00Z");
    private static final OffsetDateTime DAY_SIX = OffsetDateTime.parse("2018-05-06T09:00:00Z");
    private static final OffsetDateTime DAY_EIGHT = OffsetDateTime.parse("2018-05-08T09:00:00Z");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private DeliveryAddressService deliveryAddressService;

    private VendorFixture vendorA;
    private VendorFixture vendorB;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        vendorA = createVendor("Workspace Vendor A");
        vendorB = createVendor("Workspace Vendor B");
        buyer = signupClientId("Workspace Buyer");
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------
    // (a) Own-sales analytics: the commercial-leak surface.
    // ---------------------------------------------------------------------------------

    /**
     * The headline test. Vendor B's orders are three times the size of vendor A's
     * and sit in the same window, so an unscoped query would not merely be wrong -
     * it would be obviously, quotably wrong, which is what an assertion should
     * catch.
     */
    @Test
    void aVendorsSummaryCountsItsOwnSalesAndNoOtherSellers() {
        plantOrder(vendorA, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "100000.00", "5000.00", 10);
        plantOrder(vendorB, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "300000.00", "9000.00", 30);
        plantOrder(vendorB, buyer, OrderStatus.RECEIVED, "PAID", "MONNIFY", DAY_SIX, "400000.00", "0.00", 40);

        VendorSalesPeriodMetrics mine = summaryAs(vendorA).current();

        assertThat(mine.orderCount()).isEqualTo(1);
        assertThat(mine.grossRevenue()).isEqualByComparingTo("105000.00");
        assertThat(mine.merchandiseRevenue()).isEqualByComparingTo("100000.00");
        assertThat(mine.deliveryFeeRevenue()).isEqualByComparingTo("5000.00");
        assertThat(mine.unitsSold()).isEqualTo(10);
        assertThat(mine.averageOrderValue()).isEqualByComparingTo("105000.00");

        // And the other direction, so a query that happened to pin the WRONG seller
        // is caught too: B sees B's, not A's.
        VendorSalesPeriodMetrics theirs = summaryAs(vendorB).current();
        assertThat(theirs.orderCount()).isEqualTo(2);
        assertThat(theirs.grossRevenue()).isEqualByComparingTo("709000.00");
    }

    /** Same predicate, the series form. Zero-filled buckets must be zero-filled with the caller's own zeros. */
    @Test
    void aVendorsRevenueSeriesExcludesAnotherSellersOrders() {
        plantOrder(vendorA, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "50000.00", "0.00", 5);
        plantOrder(vendorB, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_SIX, "999999.00", "0.00", 99);

        List<VendorRevenuePoint> points = list(
                ANALYTICS + "/revenue-over-time" + RANGE + "&granularity=DAY",
                vendorA.login(),
                new ParameterizedTypeReference<>() {});

        // Deliberately not asserting a bucket COUNT. date_trunc resolves day
        // boundaries in the database session's time zone (pgjdbc takes it from the
        // JVM default), so a UTC-midnight window yields 31 or 32 buckets depending
        // on where the machine running this thinks it is - which is the behaviour a
        // local-day chart wants and not something to pin. What matters is the
        // buckets' CONTENT.
        assertThat(points).hasSizeGreaterThanOrEqualTo(31);
        assertThat(pointOn(points, "2018-05-04").revenue()).isEqualByComparingTo("50000.00");
        // The day vendor B sold on is a zero for vendor A, not a hidden row and not
        // a missing bucket.
        assertThat(pointOn(points, "2018-05-06").revenue()).isEqualByComparingTo("0.00");
        assertThat(pointOn(points, "2018-05-06").orderCount()).isZero();
        assertThat(points.stream().map(VendorRevenuePoint::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50000.00");
    }

    /**
     * Top products joins {@code products}, so this is the one query where another
     * seller's CATALOGUE could leak rather than just their money - a vendor learning
     * the SKUs and names of a competitor's best sellers.
     */
    @Test
    void aVendorsTopProductsNeverNameAnotherSellersCatalogue() {
        plantOrder(vendorA, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "60000.00", "0.00", 6);
        plantOrder(vendorB, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_SIX, "800000.00", "0.00", 80);

        List<VendorTopProductEntry> mine = list(
                ANALYTICS + "/top-products" + RANGE + "&metric=REVENUE",
                vendorA.login(),
                new ParameterizedTypeReference<>() {});

        assertThat(mine).singleElement().satisfies(entry -> {
            assertThat(entry.productId()).isEqualTo(vendorA.productId());
            assertThat(entry.revenue()).isEqualByComparingTo("60000.00");
            assertThat(entry.quantitySold()).isEqualTo(6);
        });
        assertThat(mine).extracting(VendorTopProductEntry::productId).doesNotContain(vendorB.productId());
        assertThat(mine).extracting(VendorTopProductEntry::sku).doesNotContain(vendorB.sku());
    }

    /**
     * The status breakdown is the one endpoint that counts CANCELLED and
     * PENDING_PAYMENT, so it is also the one where a leak would show up in statuses
     * the other endpoints deliberately drop.
     */
    @Test
    void aVendorsOrderStatusBreakdownIsScopedAndZeroFilled() {
        plantOrder(vendorA, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_FOUR, "20000.00", "0.00", 2);
        plantOrder(vendorA, buyer, OrderStatus.CANCELLED, "FAILED", "MONNIFY", DAY_SIX, "30000.00", "0.00", 3);
        plantOrder(vendorB, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_EIGHT, "777777.00", "0.00", 77);

        List<VendorOrderStatusCount> breakdown =
                list(
                ANALYTICS + "/order-status-breakdown" + RANGE, vendorA.login(), new ParameterizedTypeReference<>() {});

        assertThat(breakdown)
                .as("every OrderStatus is present, so a bar never vanishes")
                .hasSize(OrderStatus.values().length);
        assertThat(statusCount(breakdown, OrderStatus.PLACED)).isEqualTo(1);
        assertThat(statusCount(breakdown, OrderStatus.CANCELLED)).isEqualTo(1);
        assertThat(statusValue(breakdown, OrderStatus.PLACED)).isEqualByComparingTo("20000.00");
        assertThat(statusCount(breakdown, OrderStatus.DELIVERED)).isZero();
    }

    /**
     * Stock-outs read {@code products}, which is tenant-scoped rather than
     * seller-scoped, so the leak this guards against is a different one - but it is
     * still a leak, and a vendor must not be told what a competitor has run out of.
     */
    @Test
    void stockOutsReportOnlyTheCallersOwnCatalogue() {
        jdbc.update("UPDATE products SET quantity_on_hand = 0 WHERE id IN (?, ?)", vendorA.productId(), vendorB.productId());

        List<VendorStockOutEntry> mine =
                list(ANALYTICS + "/stock-outs?limit=50", vendorA.login(), new ParameterizedTypeReference<>() {});

        assertThat(mine).extracting(VendorStockOutEntry::productId).contains(vendorA.productId());
        assertThat(mine).extracting(VendorStockOutEntry::productId).doesNotContain(vendorB.productId());
        assertThat(mine).allSatisfy(entry -> assertThat(entry.availableToSell()).isZero());
    }

    /**
     * The permission half. A buying company's OWNER holds VIEW_MARKETPLACE_ANALYTICS -
     * every OWNER does - and this surface accepts that code, on purpose, so that
     * ProcurePal's staff can use it. This is the test that proves accepting it is not
     * a way in: the refusal comes from the guard, on every route, not from
     * {@code @PreAuthorize}.
     */
    @Test
    void aBuyingCompanyIsRefusedOnEveryVendorAnalyticsRoute() {
        TenantLoginResponse owner = signup("Definitely Not A Seller");
        assertThat(owner.user().permissions()).contains("VIEW_MARKETPLACE_ANALYTICS");
        assertThat(owner.user().permissions()).doesNotContain("VIEW_OWN_SALES_ANALYTICS");

        for (String path : analyticsRoutes()) {
            ResponseEntity<ApiError> response = restTemplate.exchange(
                    ANALYTICS + path, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ApiError.class);

            assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("sell on the marketplace");
        }
    }

    /**
     * The mirror image, and the reason the permission expression is
     * {@code hasAnyAuthority}: a vendor holds VIEW_OWN_SALES_ANALYTICS and NOT
     * VIEW_MARKETPLACE_ANALYTICS, so requiring the latter would have left the new
     * code serving nothing, which is the state this module was written to fix.
     */
    @Test
    void aVendorReachesEveryRouteOnItsOwnSalesCodeAlone() {
        assertThat(vendorA.login().user().permissions())
                .contains("VIEW_OWN_SALES_ANALYTICS")
                .doesNotContain("VIEW_MARKETPLACE_ANALYTICS");

        for (String path : analyticsRoutes()) {
            ResponseEntity<String> response = restTemplate.exchange(
                    ANALYTICS + path, HttpMethod.GET, new HttpEntity<>(authHeaders(vendorA.login())), String.class);
            assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * ProcurePal is a seller too, and locking it out of a selling surface is the
     * mistake VendorGuard's Javadoc names as the most likely in this feature. It
     * holds VIEW_MARKETPLACE_ANALYTICS and not the vendor code, which is the exact
     * case {@code hasAnyAuthority} exists for.
     */
    @Test
    void thePlatformOwnerReachesItsOwnSalesFiguresToo() {
        TenantLoginResponse operator = loginAsPlatformOwner();
        plantOrder(procurePal(), buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "70000.00", "0.00", 7);
        plantOrder(vendorA, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_SIX, "11000.00", "0.00", 1);

        VendorSalesPeriodMetrics own = body(get(ANALYTICS + "/summary" + RANGE, operator, VendorSalesSummaryResponse.class))
                .current();

        assertThat(own.grossRevenue()).isEqualByComparingTo("70000.00");
        assertThat(own.orderCount()).isEqualTo(1);
    }

    /**
     * REVERSED IN M6, deliberately, and kept rather than deleted because the reversal is
     * the point.
     *
     * <p>M5 shipped this test asserting the opposite - that
     * {@code /api/marketplace/admin/analytics} still spanned every seller - and gave a good
     * reason: while building own-sales analytics the tempting mistake was to add a seller
     * predicate to the marketplace queries too, turning the operator's marketplace-wide view
     * into a second copy of its own-sales one. What that reasoning missed is that a
     * marketplace-wide view on a TENANT surface stopped being the right thing the moment a
     * second seller existed: ProcurePal was reading a revenue figure that included money it
     * does not receive.
     *
     * <p>So the predicate WAS added, on purpose, and this test now pins the new rule. The
     * two screens are still not copies of each other - the marketplace one carries named
     * customers, new/repeat rates, category mix and funnel timings that a vendor
     * deliberately never sees - they differ in DEPTH rather than in scope. The cross-seller
     * total moved to the super admin, where {@code SuperAdminPlatformRevenueIntegrationTest}
     * asserts every seller IS counted and no tenant token can reach it.
     */
    @Test
    void theOperatorsOwnAnalyticsScreenCountsProcurePalsSalesOnly() {
        TenantLoginResponse operator = loginAsPlatformOwner();
        plantOrder(procurePal(), buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_FOUR, "70000.00", "0.00", 7);
        plantOrder(vendorA, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_SIX, "11000.00", "0.00", 1);
        plantOrder(vendorB, buyer, OrderStatus.DELIVERED, "PAID", "MONNIFY", DAY_EIGHT, "19000.00", "0.00", 2);

        MarketplaceAnalyticsSummaryResponse marketplace = body(get(
                "/api/marketplace/admin/analytics/summary" + RANGE, operator, MarketplaceAnalyticsSummaryResponse.class));

        assertThat(marketplace.current().orderCount()).isEqualTo(1);
        assertThat(marketplace.current().grossRevenue()).isEqualByComparingTo("70000.00");

        // And it agrees, to the naira, with what the same operator sees on the own-sales
        // route. The two screens differ in what ELSE they report, never in whose money it is.
        VendorSalesPeriodMetrics ownSales =
                body(get(ANALYTICS + "/summary" + RANGE, operator, VendorSalesSummaryResponse.class)).current();
        assertThat(marketplace.current().grossRevenue()).isEqualByComparingTo(ownSales.grossRevenue());
        assertThat(marketplace.current().orderCount()).isEqualTo(ownSales.orderCount());
    }

    // ---------------------------------------------------------------------------------
    // (b) The single-account invariant. Every route, not just the obvious one.
    // ---------------------------------------------------------------------------------

    /**
     * The permission layer, which is what existed before this module: the VENDOR
     * role has no MANAGE_USERS, so the tenant user surface is closed to a vendor
     * outright. Asserted so a future role edit that granted it would fail here
     * first, next to the invariant test that would then become the only defence.
     */
    @Test
    void aVendorCannotEvenReachTheTenantUserSurface() {
        assertThat(vendorA.login().user().permissions()).doesNotContain("MANAGE_USERS");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(newUserRequest(), authHeaders(vendorA.login())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The invariant itself, reached past the permission. Called at the service layer
     * because that is the only way to get there - which is the point: if the
     * permission were ever granted, or a new controller called this service, THIS is
     * what refuses, and it refuses with a message a human can act on rather than a
     * constraint violation.
     */
    @Test
    void theTenantUserManagementPathRefusesASecondVendorAccount() {
        TenantContext.set(vendorA.clientId());
        try {
            assertThatThrownBy(() -> userManagementService.create(newUserRequest()))
                    .isInstanceOf(VendorSingleAccountException.class)
                    .hasMessageContaining("exactly one user");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The same call against a buying company must still work, or the rule has been
     * implemented as "nobody may add users" rather than "vendors may not".
     */
    @Test
    void anOrdinaryCompanyCanStillAddUsers() {
        TenantContext.set(buyer);
        try {
            assertThat(userManagementService.create(newUserRequest()).id()).isNotNull();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The backstop, for the route nobody thought of. Written straight through the
     * repository, below every service and every guard - which is exactly the shape
     * of a data fix, a migration, or a future admin surface that takes a client id
     * as a parameter.
     */
    @Test
    void theDatabaseRefusesASecondVendorAccountWhateverTheRoute() {
        Role vendorRole = roleRepository.findByName("VENDOR").orElseThrow();
        TenantContext.set(vendorA.clientId());
        try {
            User second = User.builder()
                    .username("smuggled-" + UUID.randomUUID())
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(vendorRole)
                    .active(true)
                    .root(false)
                    .build();

            // Asserted on the trigger's own message rather than on the constraint
            // name: RAISE ... USING CONSTRAINT sets a field pgjdbc does not surface
            // through Hibernate's translated exception (it arrives as
            // "constraint [null]"), so matching the name would pass for the wrong
            // reason the day the trigger stopped firing and some other integrity
            // rule failed instead.
            assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("vendors have exactly one");
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The super-admin surface. There is deliberately no endpoint that writes an
     * arbitrary tenant's users - SuperAdminTenantUserController is read-only by
     * design - so the assertion is that the route does not exist rather than that it
     * refuses. Both answers keep the invariant; only one of them stays true if
     * somebody adds the endpoint later, which is why the service-level rule above
     * exists as well.
     */
    @Test
    void thereIsNoSuperAdminRouteThatCreatesATenantsUsers() {
        HttpHeaders headers = authHeaders(superAdminToken());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/clients/" + vendorA.clientId() + "/users",
                HttpMethod.POST,
                new HttpEntity<>(newUserRequest(), headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("read-only sub-collection: no POST handler")
                .isIn(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);

        // ...and the read half still works, so the assertion above is about the verb
        // rather than about the path being wrong.
        assertThat(restTemplate
                        .exchange(
                                "/api/superadmin/clients/" + vendorA.clientId() + "/users",
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A vendor sells; it does not buy. PLACE_ORDERS is not on the role, and checkout is closed. */
    @Test
    void aVendorCannotPlaceAnOrder() {
        assertThat(vendorA.login().user().permissions())
                .doesNotContain("PLACE_ORDERS")
                .doesNotContain("BROWSE_MARKETPLACE")
                .doesNotContain("VIEW_ORDERS");

        // A WELL-FORMED body on purpose. An empty one fails @Valid binding, which
        // Spring MVC resolves before the security interceptor runs, so the 400 it
        // produces would prove nothing about authorization.
        assertThat(restTemplate
                        .exchange(
                                "/api/orders",
                                HttpMethod.POST,
                                new HttpEntity<>(
                                        Map.of("paymentMethod", "PAY_ON_DELIVERY"), authHeaders(vendorA.login())),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Reading somebody's purchases is closed too - a vendor has none to read.
        assertThat(restTemplate
                        .exchange(
                                "/api/orders", HttpMethod.GET, new HttpEntity<>(authHeaders(vendorA.login())), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // The cart is the step before, and is closed too - otherwise a vendor could
        // fill a basket it can never check out, which is a worse experience than a
        // refusal.
        assertThat(restTemplate
                        .exchange("/api/cart", HttpMethod.GET, new HttpEntity<>(authHeaders(vendorA.login())), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------
    // (c) The seller-scoped order queue, seen from a vendor.
    // ---------------------------------------------------------------------------------

    /**
     * The queue was re-gated to any seller before this module; what was never tested
     * from a vendor's side is that "any seller" did not become "every order".
     */
    @Test
    void aVendorSeesOnlyItsOwnOrdersInTheFulfilmentQueue() {
        UUID mine = plantOrder(vendorA, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_FOUR, "10000.00", "0.00", 1);
        UUID theirs = plantOrder(vendorB, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_SIX, "20000.00", "0.00", 2);

        String queue = body(get("/api/marketplace/admin/orders" + RANGE + "&size=100", vendorA.login(), String.class));

        assertThat(queue).contains(mine.toString());
        assertThat(queue).doesNotContain(theirs.toString());
    }

    /** And the write side: another seller's order id is not found, not merely un-advanceable. */
    @Test
    void aVendorCannotOpenOrAdvanceAnotherSellersOrder() {
        UUID theirs = plantOrder(vendorB, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_SIX, "20000.00", "0.00", 2);

        assertThat(restTemplate
                        .exchange(
                                "/api/marketplace/admin/orders/" + theirs,
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(vendorA.login())),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(restTemplate
                        .exchange(
                                "/api/marketplace/admin/orders/" + theirs + "/status",
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("status", "CONFIRMED"), authHeaders(vendorA.login())),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** ProcurePal's own queue is untouched by any of this - the acceptance bar again. */
    @Test
    void thePlatformOwnerStillSeesItsOwnQueueAndNotAVendors() {
        UUID operatorOrder =
                plantOrder(procurePal(), buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_FOUR, "15000.00", "0.00", 1);
        UUID vendorOrder =
                plantOrder(vendorA, buyer, OrderStatus.PLACED, "PAID", "MONNIFY", DAY_SIX, "25000.00", "0.00", 2);

        String queue = body(get(
                "/api/marketplace/admin/orders" + RANGE + "&size=100", loginAsPlatformOwner(), String.class));

        assertThat(queue).contains(operatorOrder.toString());
        assertThat(queue)
                .as("being the platform owner does not widen the queue - see MarketplaceOrderAdminService")
                .doesNotContain(vendorOrder.toString());
    }

    // ---------------------------------------------------------------------------------
    // (d) Pickup addresses, and the two directions they must not leak in.
    // ---------------------------------------------------------------------------------

    /**
     * The leak that matters most, and the one the discriminator exists for: a
     * pickup point must never be offered as somewhere to deliver TO. Tested on
     * ProcurePal rather than on a vendor because ProcurePal is the tenant that has
     * both kinds - a vendor's rows are all pickup points, so a vendor could not
     * detect the bug.
     */
    @Test
    void aSellersPickupAddressesNeverAppearInItsDeliveryAddressBook() {
        TenantLoginResponse operator = loginAsPlatformOwner();

        DeliveryAddressResponse depot = body(post(PICKUP, operator, addressRequest("Fixture Collection Depot"),
                DeliveryAddressResponse.class));
        DeliveryAddressResponse shipTo = body(post("/api/delivery-addresses", operator,
                addressRequest("Fixture Ship-To"), DeliveryAddressResponse.class));

        try {
            List<DeliveryAddressResponse> deliveryBook = list(
                    "/api/delivery-addresses", operator, new ParameterizedTypeReference<>() {});
            assertThat(deliveryBook).extracting(DeliveryAddressResponse::id).contains(shipTo.id()).doesNotContain(depot.id());

            List<DeliveryAddressResponse> pickupBook = list(PICKUP, operator, new ParameterizedTypeReference<>() {});
            assertThat(pickupBook).extracting(DeliveryAddressResponse::id).contains(depot.id()).doesNotContain(shipTo.id());

            // Reading one by id across the boundary is a 404, not a cross-serve. The
            // list assertions above would pass against a service that only filtered
            // the list.
            assertThat(restTemplate
                            .exchange(
                                    "/api/delivery-addresses/" + depot.id(),
                                    HttpMethod.GET,
                                    new HttpEntity<>(authHeaders(operator)),
                                    String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(restTemplate
                            .exchange(
                                    PICKUP + "/" + shipTo.id(),
                                    HttpMethod.GET,
                                    new HttpEntity<>(authHeaders(operator)),
                                    String.class)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            // Each purpose keeps its own default: V6's index made the two meanings
            // fight over one flag, and V13 split it precisely so this holds.
            assertThat(depot.isDefault() || pickupBook.stream().anyMatch(DeliveryAddressResponse::isDefault)).isTrue();
            assertThat(deliveryBook.stream().filter(DeliveryAddressResponse::isDefault).count()).isLessThanOrEqualTo(1);
        } finally {
            deleteQuietly("/api/delivery-addresses/" + shipTo.id(), operator);
            deleteQuietly(PICKUP + "/" + depot.id(), operator);
        }
    }

    /**
     * The sharpest form of the same rule, one layer below the controller: checkout's own
     * address resolution.
     *
     * <p>The list assertions above would pass against a service that merely filtered its
     * list endpoint. This one asserts the predicate that actually decides where goods are
     * SENT - {@code resolveForCheckout} is what turns a delivery-address id on a checkout
     * request into an address, and if it were not pinned to DELIVERY a seller could pass
     * the id of a depot they collect from and have an order routed there.
     */
    @Test
    void checkoutRefusesToResolveAPickupAddressAsADeliveryDestination() {
        TenantLoginResponse operator = loginAsPlatformOwner();
        DeliveryAddressResponse depot = body(post(PICKUP, operator, addressRequest("Checkout Probe Depot"),
                DeliveryAddressResponse.class));

        TenantContext.set(platformOwnerId());
        try {
            assertThat(deliveryAddressService.resolveForCheckout(depot.id()))
                    .as("a pickup point is not a delivery destination")
                    .isEmpty();
        } finally {
            TenantContext.clear();
            deleteQuietly(PICKUP + "/" + depot.id(), operator);
        }
    }

    /** A vendor's own pickup surface works, and its rows are its own. */
    @Test
    void aVendorManagesItsOwnPickupAddressesAndNobodyElsesAreVisible() {
        DeliveryAddressResponse mine =
                body(post(PICKUP, vendorA.login(), addressRequest("Vendor A Depot"), DeliveryAddressResponse.class));
        DeliveryAddressResponse theirs =
                body(post(PICKUP, vendorB.login(), addressRequest("Vendor B Depot"), DeliveryAddressResponse.class));

        List<DeliveryAddressResponse> visible = list(PICKUP, vendorA.login(), new ParameterizedTypeReference<>() {});

        assertThat(visible).extracting(DeliveryAddressResponse::id).containsExactly(mine.id());
        assertThat(visible).extracting(DeliveryAddressResponse::id).doesNotContain(theirs.id());
        assertThat(mine.isDefault()).as("the first pickup point a seller saves is its default").isTrue();
    }

    /** A buying company sells nothing, so it has no pickup points - guard, not permission. */
    @Test
    void aBuyingCompanyIsRefusedOnThePickupAddressSurface() {
        TenantLoginResponse owner = signup("Buys Only Ltd");
        assertThat(owner.user().permissions()).contains("MANAGE_DELIVERY_ADDRESSES");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                PICKUP, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("sell on the marketplace");
    }

    // ---------------------------------------------------------------------------------
    // (e) Lockout recovery - VENDOR_RESEARCH.md Section C item 9.
    // ---------------------------------------------------------------------------------

    /**
     * The gap this module found: a vendor has one account, no colleagues and no
     * self-service reset, and until now no super-admin path reached a non-ProcurePal
     * user either. The proof is a login that fails and then works.
     */
    @Test
    void aSuperAdminCanRestoreALockedOutVendorsOnlyLogin() {
        String replacement = "brand-new-" + UUID.randomUUID();

        ResponseEntity<Void> reset = restTemplate.exchange(
                "/api/superadmin/vendors/" + vendorA.clientId() + "/account/password",
                HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest(replacement, replacement), authHeaders(superAdminToken())),
                Void.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(loginStatus(vendorA.slug(), vendorA.username(), PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(loginStatus(vendorA.slug(), vendorA.username(), replacement)).isEqualTo(HttpStatus.OK);
    }

    /**
     * And it reaches VENDORS only. A buying company's id on this path is a 404, so
     * the endpoint cannot become the broad "reset any customer's login" capability
     * SuperAdminUserService argues at length against building.
     */
    @Test
    void theVendorPasswordResetDoesNotReachAnOrdinaryCompany() {
        String attempt = "should-not-apply-" + UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/vendors/" + buyer + "/account/password",
                HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest(attempt, attempt), authHeaders(superAdminToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Mismatched confirmation is a clean 400, matching every other password path. */
    @Test
    void aMismatchedConfirmationIsRejectedBeforeAnythingIsWritten() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/vendors/" + vendorA.clientId() + "/account/password",
                HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest("aaaaaaaa1", "bbbbbbbb2"), authHeaders(superAdminToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(loginStatus(vendorA.slug(), vendorA.username(), PASSWORD)).isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------------------
    // (f) The vendor catalogue.
    // ---------------------------------------------------------------------------------

    /**
     * A vendor's catalogue is its own, and every row carries the moderation state
     * that explains why a listed product may still be invisible.
     */
    @Test
    void aVendorsCatalogueShowsItsOwnProductsWithTheirApprovalState() {
        jdbc.update(
                "UPDATE products SET approval_status = 'REJECTED', rejection_reason = ? WHERE id = ?",
                "Image is too small.",
                vendorA.productId());

        String catalogue = body(get("/api/vendor/catalogue/products?size=100", vendorA.login(), String.class));

        assertThat(catalogue).contains(vendorA.sku());
        assertThat(catalogue).doesNotContain(vendorB.sku());
        assertThat(catalogue).contains("REJECTED").contains("Image is too small.");
    }

    /** And the catalogue surface refuses a company that does not sell, like every other seller surface. */
    @Test
    void aBuyingCompanyIsRefusedOnTheVendorCatalogue() {
        TenantLoginResponse owner = signup("No Catalogue Here Ltd");
        assertThat(owner.user().permissions()).contains("MANAGE_MARKETPLACE");

        assertThat(restTemplate
                        .exchange(
                                "/api/vendor/catalogue/products",
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(owner)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The marketplace-details route: a vendor sets the two facets that make a listing
     * legible to a procurement buyer, on their own product and only their own.
     *
     * <p>The unit is the commercially load-bearing half. A B2B price is meaningless without
     * it - N10,000 per 50kg bag and N10,000 per 1kg sachet are not the same offer - and
     * until this route existed no vendor-facing surface could write it, so vendors listed
     * without one and buyers had to guess or ask.
     *
     * <p>The second half of the test is the one that would be a commercial incident: the
     * SAME request against vendorB's product is a 404, because the row is resolved through
     * {@code ownedBy} against the caller's own client id and never against anything from
     * the request. A cross-seller write here would not surface as an error - it would
     * surface as a competitor's approved listing quietly dropping off the storefront with a
     * changed brand on it.
     */
    @Test
    void aVendorSetsBrandAndUnitOnItsOwnProductAndIsRefusedOnAnotherSellers() {
        ResponseEntity<String> mine = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + vendorA.productId() + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, "50kg bag", 10, "Ada Mills"),
                        authHeaders(vendorA.login())),
                String.class);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                        "SELECT unit_of_measure FROM products WHERE id = ?", String.class, vendorA.productId()))
                .isEqualTo("50kg bag");
        assertThat(jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, vendorA.productId()))
                .isEqualTo("Ada Mills");

        ResponseEntity<String> theirs = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + vendorB.productId() + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, "1kg sachet", null, "Hijacked"),
                        authHeaders(vendorA.login())),
                String.class);

        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, vendorB.productId()))
                .isNull();
    }

    /**
     * The documented trap, asserted rather than commented: the route is behind
     * {@code requireSeller()}, so ProcurePal - a COMPANY that happens to own the platform,
     * and therefore not a {@code ClientType.VENDOR} - can use it. {@code requireVendor()}
     * would 403 the operator on its own marketplace, and VendorGuard calls that "the single
     * most likely mistake in this feature".
     *
     * <p>ProcurePal does not need this route in practice; it keeps its own under
     * /api/marketplace/admin/**, which M8 left untouched. The assertion exists because the
     * next handler on this controller will be copied from the last one, and a seller surface
     * that silently excludes one seller is a trap that only fires in production.
     */
    @Test
    void theMarketplaceDetailsRouteDoesNotLockThePlatformOwnerOut() {
        TenantLoginResponse operator = loginAsPlatformOwner();
        UUID productId = jdbc.queryForObject(
                "SELECT id FROM products WHERE client_id = ? ORDER BY name LIMIT 1", UUID.class, platformOwnerId());
        String originalBrand = jdbc.queryForObject("SELECT brand FROM products WHERE id = ?", String.class, productId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + productId + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, null, null, "PP-VWRK-Brand"),
                        authHeaders(operator)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        jdbc.update("UPDATE products SET brand = ? WHERE id = ?", originalBrand, productId);
    }

    /** And the details route refuses a company that does not sell, like every other seller surface. */
    @Test
    void aBuyingCompanyIsRefusedOnTheMarketplaceDetailsRoute() {
        TenantLoginResponse owner = signup("No Details Here Ltd");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/vendor/catalogue/products/" + vendorA.productId() + "/marketplace-details",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateVendorMarketplaceDetailsRequest(null, null, "carton", null, "Nope"),
                        authHeaders(owner)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    /** A vendor account plus the one catalogue row its sales are attributed to. */
    private record VendorFixture(
            UUID clientId, String slug, String username, UUID productId, String sku, TenantLoginResponse login) {
    }

    /**
     * A vendor straight through the repositories rather than through
     * SuperAdminVendorService, matching VendorFoundationIntegrationTest: this class
     * is testing the workspace, and going through the approval flow would couple
     * every test here to the onboarding module's fixtures.
     */
    private VendorFixture createVendor(String name) {
        String slug = name.toLowerCase().replace(" ", "-") + "-" + UUID.randomUUID();
        Client client = clientRepository.saveAndFlush(Client.builder()
                .name(name)
                .slug(slug)
                .adminContactEmail(null)
                .clientType(ClientType.VENDOR)
                .active(true)
                .build());

        Role vendorRole = roleRepository.findByName("VENDOR").orElseThrow();
        String username = "vendor-" + UUID.randomUUID();
        TenantContext.set(client.getId());
        try {
            userRepository.saveAndFlush(User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(vendorRole)
                    .active(true)
                    .root(true)
                    .build());
        } finally {
            TenantContext.clear();
        }

        // APPROVED explicitly: the column defaults to PENDING and BuyerCatalogLookup
        // enforces it, so a fixture that left the default would be silently
        // unbuyable - and the top-products query joins this row.
        UUID productId = UUID.randomUUID();
        String sku = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, "
                        + "is_active, is_marketplace_listed, approval_status, min_order_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, TRUE, 'APPROVED', 1)",
                productId,
                client.getId(),
                name + " Signature Product",
                sku,
                new BigDecimal("10000.00"),
                500);

        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login).isNotNull();

        return new VendorFixture(client.getId(), slug, username, productId, sku, login);
    }

    /** The platform owner as a seller fixture - it has no fixture product, and none of its tests need one. */
    private VendorFixture procurePal() {
        return new VendorFixture(platformOwnerId(), "procurepal", "admin", null, null, null);
    }

    /** Read straight from the table: these fixtures are planted with raw SQL, below the entity layer. */
    private UUID platformOwnerId() {
        return jdbc.queryForObject("SELECT id FROM clients WHERE is_platform_owner LIMIT 1", UUID.class);
    }

    /**
     * One order, one line, dated exactly. See the class doc for why this is SQL:
     * created_at is a {@code @CreationTimestamp} column JPA will not let a caller
     * set, and it is what dates a PENDING_PAYMENT row.
     */
    private UUID plantOrder(
            VendorFixture seller,
            UUID buyerId,
            OrderStatus status,
            String paymentStatus,
            String paymentMethod,
            OffsetDateTime at,
            String subtotal,
            String deliveryFee,
            int quantity) {
        UUID id = UUID.randomUUID();
        BigDecimal goods = new BigDecimal(subtotal);
        BigDecimal total = goods.add(new BigDecimal(deliveryFee));
        boolean placed = status != OrderStatus.PENDING_PAYMENT;

        jdbc.update(
                "INSERT INTO orders (id, order_number, client_id, seller_client_id, checkout_group_id, "
                        + "status, payment_status, payment_method, currency, "
                        + "subtotal, delivery_fee, total, placed_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'NGN', ?, ?, ?, ?, ?, ?)",
                id,
                FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 12),
                buyerId,
                seller.clientId(),
                // Each fixture order is its own checkout - a group of one, which is
                // what a single-seller basket still produces. Set to the order's own
                // id for the same reason V12's backfill does.
                id,
                status.name(),
                paymentStatus,
                paymentMethod,
                goods,
                new BigDecimal(deliveryFee),
                total,
                placed ? java.sql.Timestamp.from(at.toInstant()) : null,
                java.sql.Timestamp.from(at.toInstant()),
                java.sql.Timestamp.from(at.toInstant()));

        if (seller.productId() != null && quantity > 0) {
            jdbc.update(
                    "INSERT INTO order_items (id, order_id, product_id, product_name, product_sku, "
                            + "unit_price, quantity, line_total) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    id,
                    seller.productId(),
                    "Fixture line",
                    seller.sku(),
                    goods.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP),
                    quantity,
                    goods);
        }
        return id;
    }

    /**
     * Orders first (ON DELETE CASCADE takes their items), then the fixture products,
     * then the vendor clients this class created - which cascades their users. The
     * local Postgres is shared and never reset, so leaving a live vendor login and a
     * PLACED order behind would leak into every later run.
     */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM delivery_addresses WHERE label LIKE ?", "Fixture %");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update(
                "DELETE FROM clients WHERE client_type = 'VENDOR' AND slug LIKE ?", "workspace-vendor-%");
    }

    // ---------------------------------------------------------------------------------
    // Callers
    // ---------------------------------------------------------------------------------

    private VendorSalesSummaryResponse summaryAs(VendorFixture seller) {
        return body(get(ANALYTICS + "/summary" + RANGE, seller.login(), VendorSalesSummaryResponse.class));
    }

    private static List<String> analyticsRoutes() {
        return List.of("/summary", "/revenue-over-time", "/top-products", "/order-status-breakdown", "/stock-outs");
    }

    private DeliveryAddressRequest addressRequest(String label) {
        return new DeliveryAddressRequest(
                "Fixture " + label,
                "Fixture Contact",
                "+2348030000009",
                "1 Fixture Road",
                null,
                "Ikeja",
                "Lagos",
                null,
                null,
                null,
                null);
    }

    private <T> ResponseEntity<T> get(String path, TenantLoginResponse as, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(as)), type);
    }

    private <T> ResponseEntity<T> post(String path, TenantLoginResponse as, Object payload, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(payload, authHeaders(as)), type);
    }

    private void deleteQuietly(String path, TenantLoginResponse as) {
        restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<>(authHeaders(as)), Void.class);
    }

    private <T> List<T> list(String path, TenantLoginResponse as, ParameterizedTypeReference<List<T>> type) {
        ResponseEntity<List<T>> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(as)), type);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static <T> T body(ResponseEntity<T> response) {
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static VendorRevenuePoint pointOn(List<VendorRevenuePoint> points, String period) {
        return points.stream()
                .filter(point -> point.period().equals(period))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bucket for " + period));
    }

    private static long statusCount(List<VendorOrderStatusCount> breakdown, OrderStatus status) {
        return entryFor(breakdown, status).orderCount();
    }

    private static BigDecimal statusValue(List<VendorOrderStatusCount> breakdown, OrderStatus status) {
        return entryFor(breakdown, status).orderValue();
    }

    private static VendorOrderStatusCount entryFor(List<VendorOrderStatusCount> breakdown, OrderStatus status) {
        return breakdown.stream()
                .filter(entry -> entry.status() == status)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + status));
    }

    // ---------------------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------------------

    private CreateUserRequest newUserRequest() {
        return new CreateUserRequest(
                "staff-" + UUID.randomUUID().toString().substring(0, 8),
                PASSWORD,
                "STOREKEEPER",
                null,
                null,
                null,
                null,
                null);
    }

    private TenantLoginResponse loginAsPlatformOwner() {
        TenantLoginResponse response = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(response)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        return response;
    }

    private HttpStatus loginStatus(String clientIdentifier, String username, String password) {
        return (HttpStatus) restTemplate
                .postForEntity("/api/auth/login", new LoginRequest(clientIdentifier, username, password), String.class)
                .getStatusCode();
    }

    private String superAdminToken() {
        String uniqueUsername = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private UUID signupClientId(String name) {
        TenantLoginResponse response = signup(name);
        return jdbc.queryForObject(
                "SELECT id FROM clients WHERE slug = ?", UUID.class, response.user().clientIdentifier());
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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
