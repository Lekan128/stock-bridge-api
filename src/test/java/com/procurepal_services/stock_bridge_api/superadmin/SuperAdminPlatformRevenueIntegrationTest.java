package com.procurepal_services.stock_bridge_api.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenuePoint;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenueSummaryResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SellerRevenueBreakdownResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SellerRevenueEntry;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The super admin's cross-seller revenue surface, end to end over real HTTP.
 *
 * <h2>What this class is really testing</h2>
 * Two properties that pull in opposite directions, which is why they are tested together.
 * <b>This surface must see EVERY seller</b> - it is the only place total marketplace
 * revenue exists now that M6 narrowed ProcurePal's own analytics to its own sales - and
 * <b>nobody but the super admin principal may reach it</b>. A test that only proved the
 * first would pass against an endpoint mounted on a tenant route, which would be the
 * cross-vendor leak VENDOR_RESEARCH.md Section C item 7 calls a commercial incident; a test
 * that only proved the second would pass against an endpoint that returns nothing.
 *
 * <p>Section (d) therefore probes the denial in both directions, and includes ProcurePal's
 * OWN owner token explicitly. ProcurePal is the platform owner and holds
 * VIEW_MARKETPLACE_ANALYTICS, so it is the single most plausible account to be let through
 * by accident - and letting it through is precisely the thing M6 was asked to stop.
 *
 * <h2>Why the fixtures are raw SQL, and why the window is in the past</h2>
 * Same reasons {@code MarketplaceAnalyticsIntegrationTest} gives. Every figure is a
 * function of WHEN an order happened and {@code orders.created_at} is a
 * {@code @CreationTimestamp} column JPA will not let a caller set; and anchoring to a
 * historical month nothing else in the suite writes to keeps other tests' fixtures out of
 * these assertions. February 2017 here - a month of its own, distinct from the marketplace
 * (March 2019) and vendor workspace (May 2018) suites.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration test here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SuperAdminPlatformRevenueIntegrationTest {

    private static final String BASE = "/api/superadmin/analytics/revenue";
    private static final String FIXTURE_PREFIX = "PP-SAREV-";
    /** Names the vendor clients and their products, so @AfterEach removes exactly those rows. */
    private static final String VENDOR_PREFIX = "PPSAREVVEND-";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** February 2017: a clean 28-day month, safely before anything else the suite writes. */
    private static final String RANGE = "?from=2017-02-01T00:00:00Z&to=2017-03-01T00:00:00Z";

    private static final OffsetDateTime DAY_FIVE = OffsetDateTime.parse("2017-02-05T09:00:00Z");
    private static final OffsetDateTime DAY_TEN = OffsetDateTime.parse("2017-02-10T09:00:00Z");
    /** In the PRECEDING window - [2017-01-04, 2017-02-01) - which is where growth comes from. */
    private static final OffsetDateTime PREVIOUS_WINDOW_DAY = OffsetDateTime.parse("2017-01-20T09:00:00Z");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String superAdminToken;
    private UUID procurePalId;
    private VendorFixture growingVendor;
    private VendorFixture shrinkingVendor;
    private UUID buyerA;
    private UUID buyerB;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        superAdminToken = superAdminToken();
        procurePalId = platformOwnerId();
        growingVendor = createVendor("Growing");
        shrinkingVendor = createVendor("Shrinking");
        buyerA = signupClientId("Platform Revenue Buyer A");
        buyerB = signupClientId("Platform Revenue Buyer B");
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ---------------------------------------------------------------------------------
    // (a) The total, and its growth.
    // ---------------------------------------------------------------------------------

    /**
     * The number the stakeholder asked for: every seller's revenue in one figure. Each of
     * the three sellers contributes a distinct amount, so a query that dropped one - or
     * counted one twice - produces a total no arithmetic mistake could also produce.
     */
    @Test
    void theTotalSpansEverySellerOnTheMarketplace() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "100000.00", "5000.00", 10);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "200000.00", "0.00", 20);
        plantOrder(shrinkingVendor.clientId(), buyerB, OrderStatus.RECEIVED, "PAID", DAY_TEN, "40000.00", "1000.00", 4);

        PlatformRevenueSummaryResponse response = summary("");

        assertThat(response.current().grossRevenue()).isEqualByComparingTo("346000.00");
        assertThat(response.current().merchandiseRevenue()).isEqualByComparingTo("340000.00");
        assertThat(response.current().deliveryFeeRevenue()).isEqualByComparingTo("6000.00");
        assertThat(response.current().collectedRevenue()).isEqualByComparingTo("346000.00");
        assertThat(response.current().orderCount()).isEqualTo(3);
        assertThat(response.current().unitsSold()).isEqualTo(34);
        assertThat(response.current().sellingSellerCount()).isEqualTo(3);
        // Two distinct buyers across three orders - NOT the sum of the per-seller counts.
        assertThat(response.current().buyingCompanyCount()).isEqualTo(2);
        assertThat(response.current().averageOrderValue())
                .isEqualByComparingTo(new BigDecimal("346000.00").divide(new BigDecimal("3"), 2, java.math.RoundingMode.HALF_UP));
    }

    /**
     * Growth is the preceding window of equal length, ending exactly where this one starts
     * - the same rule every other analytics service in the application uses, so a figure
     * quoted here means the same thing as one quoted from a seller's own screen.
     */
    @Test
    void thePrecedingWindowIsReturnedAlongsideSoGrowthNeedsNoSecondRequest() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", PREVIOUS_WINDOW_DAY, "50000.00", "0.00", 5);
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "150000.00", "0.00", 15);

        PlatformRevenueSummaryResponse response = summary("");

        assertThat(response.current().grossRevenue()).isEqualByComparingTo("150000.00");
        assertThat(response.previous().grossRevenue()).isEqualByComparingTo("50000.00");
        assertThat(response.previousTo()).isEqualTo(response.from());
        assertThat(java.time.Duration.between(response.previousFrom(), response.previousTo()))
                .isEqualTo(java.time.Duration.between(response.from(), response.to()));
    }

    /** Cancelled and never-paid orders are excluded from revenue and reported separately. */
    @Test
    void cancelledOrdersAreExcludedFromRevenueButStillCounted() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "10000.00", "0.00", 1);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.CANCELLED, "FAILED", DAY_FIVE, "900000.00", "0.00", 90);
        plantOrder(growingVendor.clientId(), buyerB, OrderStatus.PENDING_PAYMENT, "PENDING", DAY_TEN, "700000.00", "0.00", 70);

        PlatformRevenueSummaryResponse response = summary("");

        assertThat(response.current().grossRevenue()).isEqualByComparingTo("10000.00");
        assertThat(response.current().orderCount()).isEqualTo(1);
        assertThat(response.current().cancelledOrderCount()).isEqualTo(1);
        assertThat(response.current().cancelledOrderValue()).isEqualByComparingTo("900000.00");
    }

    /** The growth CURVE, zero-filled so a quiet day is a real zero rather than a gap. */
    @Test
    void theSeriesBucketsEverySellersRevenueAndZeroFillsQuietDays() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "10000.00", "0.00", 1);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "20000.00", "0.00", 2);
        plantOrder(shrinkingVendor.clientId(), buyerB, OrderStatus.DELIVERED, "PAID", DAY_TEN, "5000.00", "0.00", 1);

        List<PlatformRevenuePoint> points = list(BASE + "/over-time" + RANGE + "&granularity=DAY");

        assertThat(pointOn(points, "2017-02-05").revenue()).isEqualByComparingTo("30000.00");
        assertThat(pointOn(points, "2017-02-05").sellingSellerCount()).isEqualTo(2);
        assertThat(pointOn(points, "2017-02-10").revenue()).isEqualByComparingTo("5000.00");
        assertThat(pointOn(points, "2017-02-06").revenue()).isEqualByComparingTo("0.00");
        assertThat(points.stream().map(PlatformRevenuePoint::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("35000.00");
    }

    // ---------------------------------------------------------------------------------
    // (b) Per-seller breakdown, which is what "see which vendors are growing" means.
    // ---------------------------------------------------------------------------------

    @Test
    void everySellerGetsARowWithItsOwnRevenueOrdersAovAndUnits() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "100000.00", "0.00", 10);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "30000.00", "0.00", 3);
        plantOrder(growingVendor.clientId(), buyerB, OrderStatus.DELIVERED, "PAID", DAY_TEN, "10000.00", "0.00", 1);

        SellerRevenueBreakdownResponse response = bySeller("");

        assertThat(response.totalRevenue()).isEqualByComparingTo("140000.00");

        SellerRevenueEntry owner = row(response, procurePalId);
        assertThat(owner.platformOwner()).isTrue();
        assertThat(owner.clientType()).isEqualTo(ClientType.COMPANY);
        assertThat(owner.revenue()).isEqualByComparingTo("100000.00");
        assertThat(owner.orderCount()).isEqualTo(1);
        assertThat(owner.averageOrderValue()).isEqualByComparingTo("100000.00");
        assertThat(owner.unitsSold()).isEqualTo(10);
        assertThat(owner.revenueShare()).isEqualByComparingTo("0.7143");

        SellerRevenueEntry vendor = row(response, growingVendor.clientId());
        assertThat(vendor.platformOwner()).isFalse();
        assertThat(vendor.clientType()).isEqualTo(ClientType.VENDOR);
        assertThat(vendor.active()).isTrue();
        assertThat(vendor.revenue()).isEqualByComparingTo("40000.00");
        assertThat(vendor.orderCount()).isEqualTo(2);
        assertThat(vendor.averageOrderValue()).isEqualByComparingTo("20000.00");
        assertThat(vendor.unitsSold()).isEqualTo(4);
        assertThat(vendor.buyingCompanyCount()).isEqualTo(2);

        // Default order is biggest-first, which is what "which vendors are growing" opens on.
        assertThat(response.sellers().getFirst().sellerClientId()).isEqualTo(procurePalId);
    }

    /**
     * The row an operator most needs: a vendor that traded LAST period and not this one.
     * A table keyed on the current window alone would delete it, and the drop to zero -
     * the whole finding - would be invisible.
     */
    @Test
    void aSellerThatStoppedTradingStillHasARowShowingTheDrop() {
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", PREVIOUS_WINDOW_DAY, "20000.00", "0.00", 2);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "60000.00", "0.00", 6);
        plantOrder(shrinkingVendor.clientId(), buyerB, OrderStatus.DELIVERED, "PAID", PREVIOUS_WINDOW_DAY, "80000.00", "0.00", 8);

        SellerRevenueBreakdownResponse response = bySeller("");

        SellerRevenueEntry growing = row(response, growingVendor.clientId());
        assertThat(growing.previousRevenue()).isEqualByComparingTo("20000.00");
        assertThat(growing.revenueGrowth()).isEqualByComparingTo("40000.00");
        assertThat(growing.revenueGrowthRate()).isEqualByComparingTo("2.0000");

        SellerRevenueEntry gone = row(response, shrinkingVendor.clientId());
        assertThat(gone.revenue()).isEqualByComparingTo("0.00");
        assertThat(gone.orderCount()).isZero();
        assertThat(gone.previousRevenue()).isEqualByComparingTo("80000.00");
        assertThat(gone.revenueGrowth()).isEqualByComparingTo("-80000.00");
        assertThat(gone.revenueGrowthRate()).isEqualByComparingTo("-1.0000");
    }

    /**
     * A percentage change from zero is undefined, so it is null rather than 0% or some
     * enormous number - both of which would be lies about a seller's first trading month.
     * The absolute growth is still present, which is what a client renders "new" from.
     */
    @Test
    void growthRateIsNullRatherThanFabricatedForASellersFirstPeriod() {
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "60000.00", "0.00", 6);

        SellerRevenueEntry entry = row(bySeller(""), growingVendor.clientId());

        assertThat(entry.previousRevenue()).isEqualByComparingTo("0.00");
        assertThat(entry.revenueGrowth()).isEqualByComparingTo("60000.00");
        assertThat(entry.revenueGrowthRate()).isNull();
    }

    // ---------------------------------------------------------------------------------
    // (c) Filtering and sorting.
    // ---------------------------------------------------------------------------------

    @Test
    void filteringBySellerNarrowsEveryEndpointToThatSeller() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "100000.00", "0.00", 10);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "30000.00", "0.00", 3);

        String filter = "&sellerId=" + growingVendor.clientId();

        assertThat(summary(filter).current().grossRevenue()).isEqualByComparingTo("30000.00");
        assertThat(summary(filter).current().sellingSellerCount()).isEqualTo(1);

        List<PlatformRevenuePoint> points = list(BASE + "/over-time" + RANGE + "&granularity=DAY" + filter);
        assertThat(pointOn(points, "2017-02-05").revenue()).isEqualByComparingTo("30000.00");

        SellerRevenueBreakdownResponse breakdown = bySeller(filter);
        assertThat(breakdown.sellers()).hasSize(1);
        assertThat(breakdown.sellers().getFirst().sellerClientId()).isEqualTo(growingVendor.clientId());
        assertThat(breakdown.totalRevenue()).isEqualByComparingTo("30000.00");
    }

    @Test
    void filteringByOrderStatusAndPaymentStatusNarrowsThePopulation() {
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "100000.00", "0.00", 10);
        plantOrder(procurePalId, buyerA, OrderStatus.PLACED, "ON_DELIVERY", DAY_TEN, "7000.00", "0.00", 1);
        plantOrder(growingVendor.clientId(), buyerB, OrderStatus.PLACED, "PAID", DAY_TEN, "3000.00", "0.00", 1);

        assertThat(summary("&status=DELIVERED").current().grossRevenue()).isEqualByComparingTo("100000.00");
        assertThat(summary("&status=PLACED").current().grossRevenue()).isEqualByComparingTo("10000.00");
        assertThat(summary("&paymentStatus=ON_DELIVERY").current().grossRevenue()).isEqualByComparingTo("7000.00");
        assertThat(summary("&status=PLACED&paymentStatus=PAID").current().grossRevenue())
                .isEqualByComparingTo("3000.00");

        SellerRevenueBreakdownResponse breakdown = bySeller("&status=PLACED&paymentStatus=PAID");
        assertThat(breakdown.sellers()).hasSize(1);
        assertThat(breakdown.sellers().getFirst().sellerClientId()).isEqualTo(growingVendor.clientId());
    }

    @Test
    void theBreakdownSortsByEveryKeyItAdvertises() {
        // ProcurePal: one big order. growingVendor: three small ones, more units.
        plantOrder(procurePalId, buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "100000.00", "0.00", 1);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_FIVE, "10000.00", "0.00", 10);
        plantOrder(growingVendor.clientId(), buyerA, OrderStatus.DELIVERED, "PAID", DAY_TEN, "10000.00", "0.00", 10);
        plantOrder(growingVendor.clientId(), buyerB, OrderStatus.DELIVERED, "PAID", DAY_TEN, "10000.00", "0.00", 10);

        assertThat(bySeller("&sort=REVENUE").sellers().getFirst().sellerClientId()).isEqualTo(procurePalId);
        assertThat(bySeller("&sort=ORDERS").sellers().getFirst().sellerClientId())
                .isEqualTo(growingVendor.clientId());
        assertThat(bySeller("&sort=UNITS").sellers().getFirst().sellerClientId())
                .isEqualTo(growingVendor.clientId());
        assertThat(bySeller("&sort=AVERAGE_ORDER_VALUE").sellers().getFirst().sellerClientId())
                .isEqualTo(procurePalId);
        assertThat(bySeller("&sort=GROWTH").sellers().getFirst().sellerClientId()).isEqualTo(procurePalId);

        // Money defaults to biggest-first; `ascending` flips it. Name is the one key whose
        // natural direction is forwards, so it needs no flag to read correctly.
        assertThat(bySeller("&sort=REVENUE&ascending=true").sellers().getLast().sellerClientId())
                .isEqualTo(procurePalId);
        assertThat(bySeller("&sort=NAME").sellers())
                .extracting(SellerRevenueEntry::name)
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    // ---------------------------------------------------------------------------------
    // (d) Nobody but the super admin. Both directions.
    // ---------------------------------------------------------------------------------

    /**
     * ProcurePal's own OWNER is the account most likely to be let through by accident: it
     * is the platform owner, it holds VIEW_MARKETPLACE_ANALYTICS, and it used to see these
     * very numbers on its own screen. Letting it through is the thing M6 was asked to stop.
     */
    @Test
    void noTenantTokenReachesCrossSellerRevenue() {
        String procurePalOwner = loginAsPlatformOwner().tokens().accessToken();
        String ordinaryTenant = signup("Cross Seller Probe Co").tokens().accessToken();

        for (String path : List.of("/summary" + RANGE, "/over-time" + RANGE, "/by-seller" + RANGE)) {
            assertThat(statusAs(BASE + path, procurePalOwner))
                    .as("ProcurePal's own owner on %s", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(statusAs(BASE + path, ordinaryTenant))
                    .as("an ordinary tenant owner on %s", path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** A vendor - the party whose competitors' revenue is on the other side of this - is refused too. */
    @Test
    void aVendorCannotReachCrossSellerRevenue() {
        String vendorToken = growingVendor.login().tokens().accessToken();

        for (String path : List.of("/summary" + RANGE, "/over-time" + RANGE, "/by-seller" + RANGE)) {
            assertThat(statusAs(BASE + path, vendorToken)).as("a vendor on %s", path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    /** The other direction: a super admin token still gets nothing on the tenant analytics surface. */
    @Test
    void aSuperAdminTokenIsStillRefusedOnTheTenantAnalyticsSurface() {
        assertThat(statusAs("/api/marketplace/admin/analytics/summary" + RANGE, superAdminToken))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(statusAs("/api/vendor/analytics/summary" + RANGE, superAdminToken))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------------------------
    // (e) Bad input is a 400, not a 500 and not a silently different answer.
    // ---------------------------------------------------------------------------------

    @Test
    void badParametersAreBadRequestsRatherThanServerErrors() {
        ResponseEntity<ApiError> inverted =
                get(BASE + "/summary?from=2017-03-01T00:00:00Z&to=2017-02-01T00:00:00Z", ApiError.class);
        assertThat(inverted.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(inverted.getBody()).isNotNull();
        assertThat(inverted.getBody().message()).contains("must not be after");

        ResponseEntity<ApiError> tooWide =
                get(BASE + "/summary?from=2000-01-01T00:00:00Z&to=2017-02-01T00:00:00Z", ApiError.class);
        assertThat(tooWide.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooWide.getBody()).isNotNull();
        assertThat(tooWide.getBody().message()).contains("too wide");

        assertThat(get(BASE + "/over-time" + RANGE + "&granularity=HOURLY", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE + "/by-seller" + RANGE + "&sort=PROFIT", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE + "/summary" + RANGE + "&status=SHIPPED", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(BASE + "/summary" + RANGE + "&sellerId=everyone", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Omitting both bounds must answer month-to-date, not explode. */
    @Test
    void omittingBothBoundsDefaultsToMonthToDate() {
        ResponseEntity<PlatformRevenueSummaryResponse> response =
                get(BASE + "/summary", PlatformRevenueSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().from().getDayOfMonth()).isEqualTo(1);
        assertThat(response.getBody().previousTo()).isEqualTo(response.getBody().from());
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private record VendorFixture(UUID clientId, TenantLoginResponse login) {
    }

    /**
     * A vendor account with a login, created through the repositories rather than the
     * onboarding flow - this class is testing the revenue surface, and going through
     * approval would couple it to another module's fixtures.
     *
     * <p>The login is needed for exactly one test: proving a VENDOR is refused. That is
     * worth the extra rows, because a vendor is the party whose competitors' revenue sits
     * on the other side of this endpoint.
     */
    private VendorFixture createVendor(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = VENDOR_PREFIX + label + " " + suffix;
        String slug = (VENDOR_PREFIX + label + "-" + suffix).toLowerCase();
        UUID clientId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                clientId,
                name,
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

        return new VendorFixture(clientId, login);
    }

    /**
     * One order, one line, dated exactly, attributed to a named SELLER.
     *
     * <p>Raw SQL for the reason the class doc gives: created_at is a
     * {@code @CreationTimestamp} column JPA will not let a caller set, and it is what dates
     * a PENDING_PAYMENT row that never reached placed_at.
     *
     * <p>Lines reference the seeded ProcurePal catalogue regardless of who sold them. That
     * is fine here and would not be in the per-seller modules: nothing on this surface
     * joins {@code products}, so the line exists only to give {@code order_items.quantity}
     * something to sum.
     */
    private UUID plantOrder(
            UUID sellerClientId,
            UUID buyerId,
            OrderStatus status,
            String paymentStatus,
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
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'MONNIFY', 'NGN', ?, ?, ?, ?, ?, ?)",
                id,
                FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 12),
                buyerId,
                sellerClientId,
                // Each fixture order is its own checkout - a group of one, which is what a
                // single-seller basket still produces. Set to the order's own id for the
                // same reason V12's backfill does.
                id,
                status.name(),
                paymentStatus,
                goods,
                new BigDecimal(deliveryFee),
                total,
                placed ? java.sql.Timestamp.from(at.toInstant()) : null,
                java.sql.Timestamp.from(at.toInstant()),
                java.sql.Timestamp.from(at.toInstant()));

        if (quantity > 0) {
            Map<String, Object> product = catalogueProduct();
            BigDecimal unitPrice = goods.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP);
            jdbc.update(
                    "INSERT INTO order_items (id, order_id, product_id, product_name, product_sku, unit_price, "
                            + "quantity, line_total) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    id,
                    product.get("id"),
                    product.get("name"),
                    product.get("sku"),
                    unitPrice,
                    quantity,
                    goods);
        }
        return id;
    }

    private Map<String, Object> catalogueProduct() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.name, p.sku FROM products p "
                        + "JOIN clients c ON c.id = p.client_id AND c.is_platform_owner "
                        + "WHERE p.is_marketplace_listed ORDER BY p.name LIMIT 1");
        assertThat(rows)
                .as("the procurepal catalog must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotEmpty();
        return rows.getFirst();
    }

    /**
     * ON DELETE CASCADE on order_items takes the lines with the orders.
     *
     * <p>Order matters: {@code orders.seller_client_id} is ON DELETE RESTRICT, so orders go
     * before the clients row that sold them, and the vendor's user row before its client
     * for the same reason.
     */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update(
                "DELETE FROM users WHERE client_id IN (SELECT id FROM clients WHERE name LIKE ?)",
                VENDOR_PREFIX + "%");
        jdbc.update("DELETE FROM clients WHERE name LIKE ?", VENDOR_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------
    // Callers
    // ---------------------------------------------------------------------------------

    private PlatformRevenueSummaryResponse summary(String extra) {
        return body(get(BASE + "/summary" + RANGE + extra, PlatformRevenueSummaryResponse.class));
    }

    private SellerRevenueBreakdownResponse bySeller(String extra) {
        return body(get(BASE + "/by-seller" + RANGE + extra, SellerRevenueBreakdownResponse.class));
    }

    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(superAdminToken)), type);
    }

    private List<PlatformRevenuePoint> list(String path) {
        ResponseEntity<List<PlatformRevenuePoint>> response = restTemplate.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpStatus statusAs(String path, String token) {
        return HttpStatus.valueOf(restTemplate
                .exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class)
                .getStatusCode()
                .value());
    }

    private static <T> T body(ResponseEntity<T> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static SellerRevenueEntry row(SellerRevenueBreakdownResponse response, UUID sellerId) {
        return response.sellers().stream()
                .filter(entry -> entry.sellerClientId().equals(sellerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no breakdown row for seller " + sellerId));
    }

    private static PlatformRevenuePoint pointOn(List<PlatformRevenuePoint> points, String period) {
        return points.stream()
                .filter(point -> point.period().equals(period))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bucket for " + period));
    }

    // ---------------------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------------------

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(username, PASSWORD),
                SuperAdminLoginResponse.class);
        assertThat(response).isNotNull();
        return response.tokens().accessToken();
    }

    /** Read straight from the table: these fixtures are planted with raw SQL, below the entity layer. */
    private UUID platformOwnerId() {
        return jdbc.queryForObject("SELECT id FROM clients WHERE is_platform_owner LIMIT 1", UUID.class);
    }

    private TenantLoginResponse loginAsPlatformOwner() {
        TenantLoginResponse response = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(response)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        return response;
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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
