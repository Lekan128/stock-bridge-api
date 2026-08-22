package com.procurepal_services.stock_bridge_api.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.CategoryMixEntry;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.CategoryMixResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.FulfilmentFunnelResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.FunnelStage;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.FunnelStatusCount;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.FunnelTransition;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.MarketplaceAnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.MarketplacePeriodMetrics;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.RevenuePoint;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.TopCustomerEntry;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.dto.TopSellingProductEntry;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * The marketplace analytics module, end to end over real HTTP.
 *
 * <h2>Why the fixtures are raw SQL</h2>
 * Every figure this module reports is a function of WHEN an order happened, and the two
 * timestamps that decide that - {@code orders.created_at} and
 * {@code order_status_events.created_at} - are {@code @CreationTimestamp} columns JPA will
 * not let a caller set. Planting rows with JdbcTemplate is the only way to write a
 * fulfilment history with known durations, and it also sidesteps needing a TenantContext
 * to write a buyer's row. Every planted order carries the {@link #FIXTURE_PREFIX} order
 * number so {@code @AfterEach} can remove exactly what this class created and nothing else
 * - a PLACED order commits catalog stock indefinitely, and the local Postgres is never
 * reset.
 *
 * <h2>Why the window is in the past</h2>
 * The suite's other order tests all work in "now". Anchoring this class to a fixed
 * historical month means their fixtures cannot drift into these assertions, and these
 * cannot drift into theirs.
 *
 * <h2>The vendor fixture, and why every metric is asserted against it</h2>
 * Since M6 these endpoints report ProcurePal's OWN sales rather than the marketplace's.
 * The failure mode for that is not an exception - it is a 200 with somebody else's money
 * in it - so section (h) plants a third-party VENDOR's orders in the SAME window, on the
 * same days, from the same buyers, and asserts each metric is unmoved. The vendor's
 * figures are deliberately far larger than ProcurePal's, so an unscoped query would not
 * merely be wrong, it would be quotably wrong. A test that only checked ProcurePal's own
 * numbers were present would pass just as happily against the pre-M6 code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class MarketplaceAnalyticsIntegrationTest {

    private static final String BASE = "/api/marketplace/admin/analytics";
    private static final String FIXTURE_PREFIX = "PP-ANLZ-";

    /** Names the vendor client and its product, so @AfterEach can remove exactly those rows. */
    private static final String VENDOR_FIXTURE_PREFIX = "PPANLZVEND-";
    private static final String PASSWORD = "correct-horse-battery-staple";

    /** March 2019: safely before anything else the suite writes, and a clean 31-day month. */
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2019-03-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2019-04-01T00:00:00Z");

    private static final String RANGE = "?from=2019-03-01T00:00:00Z&to=2019-04-01T00:00:00Z";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpHeaders operatorHeaders;
    private UUID buyerA;
    private UUID buyerB;
    private UUID productA;
    private UUID productB;
    private UUID categoryA;
    private UUID categoryB;
    /** name/sku per product id, so order lines can be inserted without a parameterised SELECT. */
    private Map<UUID, Map<String, Object>> productRows;
    /** A third-party seller whose sales must never appear in any of these responses. */
    private UUID vendorSellerId;
    private UUID vendorProductId;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        operatorHeaders = authHeaders(loginAsPlatformOwner());

        buyerA = signupClientId("Analytics Buyer A");
        buyerB = signupClientId("Analytics Buyer B");

        // Two listed products from two different categories, so the category mix has
        // something to split. Taken from the seeded catalog rather than invented: the
        // top-products query joins the live products row, not the order-line snapshot.
        List<Map<String, Object>> products = jdbc.queryForList(
                "SELECT p.id, p.category_id, p.name, p.sku FROM products p "
                        + "JOIN clients c ON c.id = p.client_id AND c.is_platform_owner "
                        + "WHERE p.is_marketplace_listed AND p.category_id IS NOT NULL "
                        + "ORDER BY p.category_id, p.name");
        assertThat(products)
                .as("the procurepal catalog must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotEmpty();

        productA = (UUID) products.getFirst().get("id");
        categoryA = (UUID) products.getFirst().get("category_id");
        Map<String, Object> otherCategory = products.stream()
                .filter(row -> !categoryA.equals(row.get("category_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("seed must have at least two categories with products"));
        productB = (UUID) otherCategory.get("id");
        categoryB = (UUID) otherCategory.get("category_id");
        productRows = new java.util.HashMap<>();
        productRows.put(productA, products.getFirst());
        productRows.put(productB, otherCategory);

        createVendorSeller();
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
    }

    // ---------------------------------------------------------------------------------
    // (a) Both gates. Every route, not just one - a route that forgot the guard is
    //     invisible to a test that only probes /summary.
    // ---------------------------------------------------------------------------------

    @Test
    void anOrdinaryTenantsOwnerIsRefusedOnEveryAnalyticsRoute() {
        TenantLoginResponse owner = signup("Not The Operator Co");
        // This user holds VIEW_MARKETPLACE_ANALYTICS - every OWNER does, because
        // permissions hang off global roles - so @PreAuthorize alone would wave them
        // through. Any 403 below can only come from the platform-owner check.
        assertThat(owner.user().permissions()).contains("VIEW_MARKETPLACE_ANALYTICS");
        assertThat(owner.user().platformOwner()).isFalse();

        for (String path : routes()) {
            ResponseEntity<ApiError> response = restTemplate.exchange(
                    BASE + path, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ApiError.class);

            assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message()).contains("marketplace operator");
        }
    }

    @Test
    void anAnonymousCallerIsRefusedOnEveryAnalyticsRoute() {
        for (String path : routes()) {
            ResponseEntity<String> response =
                    restTemplate.exchange(BASE + path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
            assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    private static List<String> routes() {
        return List.of(
                "/summary",
                "/revenue-over-time",
                "/top-customers",
                "/top-products",
                "/category-mix",
                "/fulfilment-funnel");
    }

    // ---------------------------------------------------------------------------------
    // (b) The cross-tenant read - the single most likely bug in this module.
    // ---------------------------------------------------------------------------------

    /**
     * orders.client_id is the BUYER, so under ProcurePal's own Hibernate tenant filter
     * every aggregate here would match nothing and the screen would report a marketplace
     * that has never sold anything. This is the test that fails if that regresses: the
     * orders below belong to two OTHER tenants, and none belong to the operator.
     */
    @Test
    void analyticsSeeOtherTenantsOrdersRatherThanZeroRows() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "100000.00", "5000.00", 10, 0);
        plantOrder(buyerB, OrderStatus.RECEIVED, "PAID", "MONNIFY", day(6), "50000.00", "0.00", 4, 0);

        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.orderCount()).isEqualTo(2);
        assertThat(current.grossRevenue()).isEqualByComparingTo("155000.00");
        assertThat(current.merchandiseRevenue()).isEqualByComparingTo("150000.00");
        assertThat(current.deliveryFeeRevenue()).isEqualByComparingTo("5000.00");
        assertThat(current.activeBuyingCompanies()).isEqualTo(2);
        assertThat(current.unitsSold()).isEqualTo(14);
    }

    // ---------------------------------------------------------------------------------
    // (c) What is and is not money.
    // ---------------------------------------------------------------------------------

    @Test
    void cancelledAndNeverPaidOrdersAreExcludedFromRevenueButReportedSeparately() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "100000.00", "0.00", 10, 0);
        plantOrder(buyerA, OrderStatus.CANCELLED, "FAILED", "MONNIFY", day(5), "999999.00", "0.00", 99, 0);
        plantOrder(buyerB, OrderStatus.PENDING_PAYMENT, "PENDING", "MONNIFY", day(6), "777777.00", "0.00", 77, 0);

        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.grossRevenue()).isEqualByComparingTo("100000.00");
        assertThat(current.orderCount()).isEqualTo(1);
        assertThat(current.unitsSold()).isEqualTo(10);
        // The buyer whose only order never got paid for is not an active buying company.
        assertThat(current.activeBuyingCompanies()).isEqualTo(1);

        // ...but neither is silently swallowed: both have their own counters.
        assertThat(current.cancelledOrderCount()).isEqualTo(1);
        assertThat(current.cancelledOrderValue()).isEqualByComparingTo("999999.00");
        assertThat(current.abandonedCheckoutCount()).isEqualTo(1);

        // The line-level endpoints must draw the same line, or a product chart would
        // contradict the summary card above it.
        assertThat(totalOf(topProducts("REVENUE"))).isEqualByComparingTo("100000.00");
        assertThat(categoryMix().totalRevenue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void payOnDeliveryExposureCountsOnlyOrdersWhoseCashIsStillOutstanding() {
        plantOrder(buyerA, OrderStatus.OUT_FOR_DELIVERY, "ON_DELIVERY", "PAY_ON_DELIVERY", day(4), "80000.00", "0.00", 8, 0);
        // Same payment method, already settled by the ops team - no longer exposure.
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "PAY_ON_DELIVERY", day(5), "60000.00", "0.00", 6, 0);

        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.payOnDeliveryOrderCount()).isEqualTo(1);
        assertThat(current.payOnDeliveryExposure()).isEqualByComparingTo("80000.00");
        assertThat(current.collectedRevenue()).isEqualByComparingTo("60000.00");
        assertThat(current.grossRevenue()).isEqualByComparingTo("140000.00");

        // Outstanding = owed and not yet handed over. DELIVERED is done; OUT_FOR_DELIVERY is not.
        assertThat(current.outstandingOrderCount()).isEqualTo(1);
        assertThat(current.outstandingOrderValue()).isEqualByComparingTo("80000.00");
    }

    // ---------------------------------------------------------------------------------
    // (d) Period comparison, new/repeat customers.
    // ---------------------------------------------------------------------------------

    @Test
    void theSummaryComparesAgainstThePrecedingWindowOfTheSameLength() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(10), "30000.00", "0.00", 3, 0);
        // February: inside [from - span, from), the comparison window.
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY",
                OffsetDateTime.parse("2019-02-10T09:00:00Z"), "12000.00", "0.00", 2, 0);

        MarketplaceAnalyticsSummaryResponse response = summary();

        assertThat(response.from().toInstant()).isEqualTo(FROM.toInstant());
        assertThat(response.to().toInstant()).isEqualTo(TO.toInstant());
        assertThat(response.previousTo().toInstant()).isEqualTo(FROM.toInstant());
        assertThat(response.previousFrom().toInstant())
                .isEqualTo(OffsetDateTime.parse("2019-01-29T00:00:00Z").toInstant());

        assertThat(response.current().grossRevenue()).isEqualByComparingTo("30000.00");
        assertThat(response.previous().grossRevenue()).isEqualByComparingTo("12000.00");

        // The March order is buyerA's SECOND ever, so they are not a new company in March,
        // and 100% of March's orders came from a returning buyer.
        assertThat(response.current().newBuyingCompanies()).isZero();
        assertThat(response.current().repeatOrderRate()).isEqualByComparingTo("1.0000");
        // February was their first, so the reverse holds there.
        assertThat(response.previous().newBuyingCompanies()).isEqualTo(1);
        assertThat(response.previous().repeatOrderRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    void averageOrderValueIsZeroRatherThanNullWhenNothingSold() {
        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.orderCount()).isZero();
        assertThat(current.grossRevenue()).isEqualByComparingTo("0.00");
        assertThat(current.averageOrderValue()).isEqualByComparingTo("0.00");
        assertThat(current.repeatOrderRate()).isEqualByComparingTo("0.0000");
    }

    // ---------------------------------------------------------------------------------
    // (e) Series, rankings, mix.
    // ---------------------------------------------------------------------------------

    @Test
    void revenueOverTimeZeroFillsEveryQuietBucket() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(2), "10000.00", "0.00", 1, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(5), "20000.00", "0.00", 2, 0);

        List<RevenuePoint> daily = revenueOverTime("DAY");

        // Every day in March present - a chart that skipped the quiet days would draw a
        // rise from the 2nd straight to the 5th that never happened. Asserted as "at least
        // 31, contiguous" rather than "exactly 31": date_trunc resolves day boundaries in
        // the database session's time zone (the JVM default, via pgjdbc), so a window
        // expressed in UTC instants on a non-UTC machine legitimately clips one extra
        // partial bucket at the end. The frontend sends local midnights, where it does not.
        assertThat(daily).hasSizeGreaterThanOrEqualTo(31);
        assertThat(daily.getFirst().period()).isEqualTo("2019-03-01");
        assertThat(daily).extracting(RevenuePoint::period).doesNotHaveDuplicates();
        assertThat(daily.getFirst().revenue()).isEqualByComparingTo("0.00");
        for (int i = 1; i < daily.size(); i++) {
            assertThat(java.time.LocalDate.parse(daily.get(i).period()))
                    .as("bucket %d follows the one before it", i)
                    .isEqualTo(java.time.LocalDate.parse(daily.get(i - 1).period()).plusDays(1));
        }
        assertThat(pointOn(daily, "2019-03-02").revenue()).isEqualByComparingTo("10000.00");
        assertThat(pointOn(daily, "2019-03-02").unitsSold()).isEqualTo(1);
        assertThat(pointOn(daily, "2019-03-05").revenue()).isEqualByComparingTo("20000.00");
        assertThat(pointOn(daily, "2019-03-03").revenue()).isEqualByComparingTo("0.00");

        // Month buckets collapse the same money into one point - the totals must agree.
        List<RevenuePoint> monthly = revenueOverTime("MONTH");
        assertThat(monthly.getFirst().period()).isEqualTo("2019-03-01");
        assertThat(monthly.getFirst().revenue()).isEqualByComparingTo("30000.00");
        assertThat(monthly.getFirst().orderCount()).isEqualTo(2);
        assertThat(monthly.getFirst().buyingCompanies()).isEqualTo(2);
        // Same total, one bucket instead of thirty-one: coarsening the granularity must
        // never change how much money the period made.
        assertThat(monthly.stream().map(RevenuePoint::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(daily.stream().map(RevenuePoint::revenue).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void topCustomersRankByTheRequestedMetricAndCarryLifetimeContext() {
        // buyerA: one big order. buyerB: three small ones. The two metrics must disagree.
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "500000.00", "0.00", 5, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(5), "10000.00", "0.00", 1, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(6), "10000.00", "0.00", 1, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(7), "10000.00", "0.00", 1, 0);
        // Outside the window entirely: must not move the in-window columns, must move lifetime.
        plantOrder(buyerA, OrderStatus.RECEIVED, "PAID", "MONNIFY",
                OffsetDateTime.parse("2018-11-05T09:00:00Z"), "70000.00", "0.00", 7, 0);

        List<TopCustomerEntry> byRevenue = topCustomers("REVENUE");
        assertThat(byRevenue).extracting(TopCustomerEntry::clientId).containsExactly(buyerA, buyerB);
        TopCustomerEntry a = byRevenue.getFirst();
        assertThat(a.revenue()).isEqualByComparingTo("500000.00");
        assertThat(a.orderCount()).isEqualTo(1);
        assertThat(a.unitsPurchased()).isEqualTo(5);
        assertThat(a.lifetimeSpend()).isEqualByComparingTo("570000.00");
        assertThat(a.lifetimeOrderCount()).isEqualTo(2);
        assertThat(a.firstOrderAt().toInstant())
                .isEqualTo(OffsetDateTime.parse("2018-11-05T09:00:00Z").toInstant());
        assertThat(a.lastOrderAt().toInstant()).isEqualTo(day(4).toInstant());

        List<TopCustomerEntry> byOrders = topCustomers("ORDERS");
        assertThat(byOrders).extracting(TopCustomerEntry::clientId).containsExactly(buyerB, buyerA);
        assertThat(byOrders.getFirst().orderCount()).isEqualTo(3);
    }

    @Test
    void topProductsRankByRevenueOrQuantityAndUseLineTotalsOnly() {
        // productA: few units, expensive. productB: many units, cheap. Delivery fee on top,
        // which must NOT appear in product revenue - it belongs to no product.
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "90000.00", "7500.00", 3, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(5), "40000.00", "0.00", 0, 40);

        List<TopSellingProductEntry> byRevenue = topProducts("REVENUE");
        assertThat(byRevenue).extracting(TopSellingProductEntry::productId).containsExactly(productA, productB);
        assertThat(byRevenue.getFirst().revenue()).isEqualByComparingTo("90000.00");
        assertThat(byRevenue.getFirst().quantitySold()).isEqualTo(3);
        assertThat(byRevenue.getFirst().orderCount()).isEqualTo(1);
        assertThat(byRevenue.getFirst().buyingCompanies()).isEqualTo(1);
        assertThat(totalOf(byRevenue)).isEqualByComparingTo("130000.00");

        // Delivery fee is in gross revenue and not in product revenue - by design.
        assertThat(summary().current().grossRevenue()).isEqualByComparingTo("137500.00");
        assertThat(summary().current().merchandiseRevenue()).isEqualByComparingTo("130000.00");

        assertThat(topProducts("QUANTITY"))
                .extracting(TopSellingProductEntry::productId)
                .containsExactly(productB, productA);
    }

    @Test
    void categoryMixSharesAreTakenAgainstTheTotalItReports() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "75000.00", "2000.00", 3, 1);

        CategoryMixResponse mix = categoryMix();

        assertThat(mix.categories()).hasSize(2);
        assertThat(mix.categories()).extracting(CategoryMixEntry::categoryId).containsExactlyInAnyOrder(categoryA, categoryB);
        assertThat(mix.totalRevenue()).isEqualByComparingTo("75000.00");
        assertThat(mix.categories().stream()
                        .map(CategoryMixEntry::revenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(mix.totalRevenue());
        assertThat(mix.categories().stream()
                        .map(CategoryMixEntry::share)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1.0000");
        // Ordered revenue-descending, so the biggest slice is always first.
        assertThat(mix.categories().getFirst().revenue())
                .isGreaterThanOrEqualTo(mix.categories().getLast().revenue());
    }

    // ---------------------------------------------------------------------------------
    // (f) The fulfilment funnel, which is the point of the whole screen.
    // ---------------------------------------------------------------------------------

    @Test
    void theFunnelCountsCurrentStatusesEverReachedStagesAndHopDurations() {
        // One order that went the whole way, with a deliberate 24h/48h/12h/6h history.
        UUID delivered = plantOrder(buyerA, OrderStatus.RECEIVED, "PAID", "MONNIFY", day(4), "100000.00", "0.00", 5, 0);
        OffsetDateTime placedAt = day(4);
        plantEvent(delivered, null, "PLACED", placedAt);
        plantEvent(delivered, "PLACED", "CONFIRMED", placedAt.plusHours(24));
        plantEvent(delivered, "CONFIRMED", "OUT_FOR_DELIVERY", placedAt.plusHours(72));
        plantEvent(delivered, "OUT_FOR_DELIVERY", "DELIVERED", placedAt.plusHours(84));
        plantEvent(delivered, "DELIVERED", "RECEIVED", placedAt.plusHours(90));

        // One that stalled at CONFIRMED, and one the customer walked away from.
        UUID stalled = plantOrder(buyerB, OrderStatus.CONFIRMED, "ON_DELIVERY", "PAY_ON_DELIVERY", day(6), "20000.00", "0.00", 2, 0);
        plantEvent(stalled, null, "PLACED", day(6));
        plantEvent(stalled, "PLACED", "CONFIRMED", day(6).plusHours(48));
        plantOrder(buyerB, OrderStatus.CANCELLED, "FAILED", "MONNIFY", day(7), "5000.00", "0.00", 1, 0);

        FulfilmentFunnelResponse funnel = fulfilmentFunnel();

        // The funnel counts EVERYTHING in the window, cancellations included.
        assertThat(funnel.totalOrders()).isEqualTo(3);

        // Current-status census: every status present, zero-filled, summing to the total.
        assertThat(funnel.statusCounts()).hasSize(OrderStatus.values().length);
        assertThat(statusCount(funnel, OrderStatus.RECEIVED)).isEqualTo(1);
        assertThat(statusCount(funnel, OrderStatus.CONFIRMED)).isEqualTo(1);
        assertThat(statusCount(funnel, OrderStatus.CANCELLED)).isEqualTo(1);
        assertThat(statusCount(funnel, OrderStatus.PROCESSING)).isZero();
        assertThat(funnel.statusCounts().stream().mapToLong(FunnelStatusCount::orderCount).sum()).isEqualTo(3);

        // Ever-reached: the completed order counts at every rung it passed, and the rungs
        // never increase going down.
        assertThat(stage(funnel, "PLACED").orderCount()).isEqualTo(3);
        assertThat(stage(funnel, "CONFIRMED").orderCount()).isEqualTo(2);
        assertThat(stage(funnel, "DISPATCHED").orderCount()).isEqualTo(1);
        assertThat(stage(funnel, "DELIVERED").orderCount()).isEqualTo(1);
        assertThat(stage(funnel, "RECEIVED").orderCount()).isEqualTo(1);
        assertThat(stage(funnel, "PLACED").conversionRate()).isEqualByComparingTo("1.0000");
        assertThat(stage(funnel, "DELIVERED").conversionRate()).isEqualByComparingTo("0.3333");

        // The operational number: 24h and 48h averaged over the two orders that confirmed.
        FunnelTransition toConfirmed = transition(funnel, "PLACED_TO_CONFIRMED");
        assertThat(toConfirmed.sampleSize()).isEqualTo(2);
        assertThat(toConfirmed.averageHours()).isEqualByComparingTo("36.00");
        assertThat(toConfirmed.medianHours()).isEqualByComparingTo("36.00");

        FunnelTransition toDispatched = transition(funnel, "CONFIRMED_TO_DISPATCHED");
        assertThat(toDispatched.sampleSize()).isEqualTo(1);
        assertThat(toDispatched.averageHours()).isEqualByComparingTo("48.00");

        assertThat(transition(funnel, "PLACED_TO_DELIVERED").averageHours()).isEqualByComparingTo("84.00");
    }

    /**
     * An average of nothing is not zero hours. Rendering "0h" for a hop nobody has
     * completed would read as instant fulfilment, so the fields are null - and because
     * default-property-inclusion is non_null they are absent from the JSON entirely.
     */
    @Test
    void aTransitionWithNoCompletionsReportsNoDurationRatherThanZero() {
        UUID stalled = plantOrder(buyerA, OrderStatus.PLACED, "PAID", "MONNIFY", day(4), "10000.00", "0.00", 1, 0);
        plantEvent(stalled, null, "PLACED", day(4));

        FunnelTransition toConfirmed = transition(fulfilmentFunnel(), "PLACED_TO_CONFIRMED");

        assertThat(toConfirmed.sampleSize()).isZero();
        assertThat(toConfirmed.averageHours()).isNull();
        assertThat(toConfirmed.medianHours()).isNull();
    }

    /**
     * order_status_events is the source of truth for durations, but rows written straight
     * into a status - seeds, backfills - have no events at all. The milestone columns on
     * orders are the fallback, and this pins that down: no events are planted here.
     */
    @Test
    void milestoneColumnsStandInWhenAnOrderHasNoStatusEvents() {
        UUID id = plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "10000.00", "0.00", 1, 0);
        jdbc.update(
                "UPDATE orders SET confirmed_at = ?, dispatched_at = ?, delivered_at = ? WHERE id = ?",
                java.sql.Timestamp.from(day(4).plusHours(2).toInstant()),
                java.sql.Timestamp.from(day(4).plusHours(5).toInstant()),
                java.sql.Timestamp.from(day(4).plusHours(9).toInstant()),
                id);

        FulfilmentFunnelResponse funnel = fulfilmentFunnel();

        assertThat(transition(funnel, "PLACED_TO_CONFIRMED").averageHours()).isEqualByComparingTo("2.00");
        assertThat(transition(funnel, "PLACED_TO_DELIVERED").averageHours()).isEqualByComparingTo("9.00");
        assertThat(stage(funnel, "DELIVERED").orderCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // (g) Bad input is a 400, not a 500 and not a silently different answer.
    // ---------------------------------------------------------------------------------

    @Test
    void anInvertedRangeIsRejected() {
        ResponseEntity<ApiError> response = get(
                "/summary?from=2019-04-01T00:00:00Z&to=2019-03-01T00:00:00Z", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("must not be after");
    }

    @Test
    void anAbsurdlyWideRangeIsRejectedRatherThanClamped() {
        ResponseEntity<ApiError> response = get(
                "/summary?from=2000-01-01T00:00:00Z&to=2019-03-01T00:00:00Z", ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("too wide");
    }

    @Test
    void unknownEnumsAndOutOfRangeLimitsAreBadRequestsNotServerErrors() {
        assertThat(get("/revenue-over-time" + RANGE + "&granularity=HOURLY", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/top-products" + RANGE + "&metric=PROFIT", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/top-customers" + RANGE + "&limit=0", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/top-customers" + RANGE + "&limit=500", ApiError.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/summary?from=yesterday", ApiError.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Omitting both bounds must answer month-to-date, not explode. */
    @Test
    void omittingBothBoundsDefaultsToMonthToDate() {
        ResponseEntity<MarketplaceAnalyticsSummaryResponse> response =
                get("/summary", MarketplaceAnalyticsSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().from().getDayOfMonth()).isEqualTo(1);
        assertThat(response.getBody().previousTo().toInstant())
                .isEqualTo(response.getBody().from().toInstant());
    }

    // ---------------------------------------------------------------------------------
    // (h) M6: these numbers are ProcurePal's OWN sales, not the marketplace's.
    //
    //     Every test below plants a third-party vendor's orders in the SAME window, on
    //     the same days, from the same buyers, and asserts the response is unmoved. The
    //     vendor's figures are an order of magnitude larger than ProcurePal's on purpose:
    //     an unscoped query would not be subtly wrong, it would be quotably wrong.
    // ---------------------------------------------------------------------------------

    /**
     * The headline test. Before M6 this endpoint summed every seller's orders, so
     * ProcurePal's revenue card included money it does not receive.
     */
    @Test
    void theSummaryCountsProcurePalsOwnSalesAndNoVendorsAtAll() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "100000.00", "5000.00", 10, 0);
        plantVendorOrder(buyerA, OrderStatus.DELIVERED, day(4), "900000.00", "40000.00", 90);
        plantVendorOrder(buyerB, OrderStatus.RECEIVED, day(6), "500000.00", "0.00", 50);

        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.grossRevenue()).isEqualByComparingTo("105000.00");
        assertThat(current.merchandiseRevenue()).isEqualByComparingTo("100000.00");
        assertThat(current.deliveryFeeRevenue()).isEqualByComparingTo("5000.00");
        assertThat(current.collectedRevenue()).isEqualByComparingTo("105000.00");
        assertThat(current.orderCount()).isEqualTo(1);
        assertThat(current.averageOrderValue()).isEqualByComparingTo("105000.00");
        assertThat(current.unitsSold()).isEqualTo(10);
        // buyerB bought only from the vendor in this window, so ProcurePal has one active
        // company, not two. A leak here would be silent - the number would just be bigger.
        assertThat(current.activeBuyingCompanies()).isEqualTo(1);
    }

    /**
     * "New to ProcurePal", not "new to the platform". A company whose first ever order
     * anywhere was with a VENDOR, and whose first ProcurePal order lands in this window,
     * is new to ProcurePal - and the pre-M6 query, which took the all-time MIN across
     * every seller, would have said otherwise.
     */
    @Test
    void newBuyingCompaniesMeansNewToProcurePalRatherThanNewToTheMarketplace() {
        // buyerA has been buying from the vendor since long before the window.
        plantVendorOrder(buyerA, OrderStatus.RECEIVED, day(4).minusDays(200), "80000.00", "0.00", 8);
        // ...and buys from ProcurePal for the first time inside it.
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(5), "20000.00", "0.00", 2, 0);

        MarketplacePeriodMetrics current = summary().current();

        assertThat(current.newBuyingCompanies()).isEqualTo(1);
        // Same reasoning from the other side: their vendor history is not a prior
        // ProcurePal order, so this is not a repeat ProcurePal order.
        assertThat(current.repeatOrderRate()).isEqualByComparingTo("0.0000");
    }

    @Test
    void theRevenueSeriesExcludesVendorSalesBucketByBucket() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "30000.00", "0.00", 3, 0);
        plantVendorOrder(buyerA, OrderStatus.DELIVERED, day(4), "300000.00", "0.00", 30);
        // A day ProcurePal did not trade at all but the vendor did. It must still be a
        // zero bucket, not the vendor's revenue - the seller pin lives in the LEFT JOIN
        // condition, and this is the assertion that notices if it moves to a WHERE.
        plantVendorOrder(buyerB, OrderStatus.DELIVERED, day(7), "700000.00", "0.00", 70);

        List<RevenuePoint> points = revenueOverTime("DAY");

        assertThat(pointOn(points, "2019-03-04").revenue()).isEqualByComparingTo("30000.00");
        assertThat(pointOn(points, "2019-03-04").orderCount()).isEqualTo(1);
        assertThat(pointOn(points, "2019-03-07").revenue()).isEqualByComparingTo("0.00");
        assertThat(pointOn(points, "2019-03-07").orderCount()).isZero();
        // Nothing anywhere in the series carries vendor money: the whole month sums to
        // ProcurePal's single order, so a leak cannot hide in a bucket this test does not
        // name explicitly.
        assertThat(points.stream().map(RevenuePoint::revenue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("30000.00");
    }

    /**
     * A ranking is a revenue statement about a named company, so it narrows - unlike the
     * customers ROSTER at /api/marketplace/admin/customers, which is an ops list and
     * deliberately stays wide. Both halves of that decision are asserted here: the
     * vendor's much bigger buyer must not outrank ProcurePal's, and lifetime spend must
     * count ProcurePal's sales only.
     */
    @Test
    void theCustomerRankingCountsSpendWithProcurePalOnlyInWindowAndLifetime() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "40000.00", "0.00", 4, 0);
        plantOrder(buyerB, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(5), "10000.00", "0.00", 1, 0);
        // buyerB is the vendor's biggest customer, in window and historically.
        plantVendorOrder(buyerB, OrderStatus.DELIVERED, day(5), "900000.00", "0.00", 90);
        plantVendorOrder(buyerB, OrderStatus.RECEIVED, day(5).minusDays(300), "900000.00", "0.00", 90);

        List<TopCustomerEntry> ranking = topCustomers("REVENUE");

        TopCustomerEntry first = ranking.stream()
                .filter(entry -> entry.clientId().equals(buyerA) || entry.clientId().equals(buyerB))
                .findFirst()
                .orElseThrow();
        assertThat(first.clientId()).as("buyerA outspends buyerB WITH PROCUREPAL").isEqualTo(buyerA);

        TopCustomerEntry b = ranking.stream()
                .filter(entry -> entry.clientId().equals(buyerB))
                .findFirst()
                .orElseThrow();
        assertThat(b.revenue()).isEqualByComparingTo("10000.00");
        assertThat(b.lifetimeSpend()).isEqualByComparingTo("10000.00");
        assertThat(b.lifetimeOrderCount()).isEqualTo(1);
    }

    @Test
    void topProductsAndCategoryMixNeverShowAVendorsGoods() {
        plantOrder(buyerA, OrderStatus.DELIVERED, "PAID", "MONNIFY", day(4), "50000.00", "0.00", 5, 0);
        plantVendorOrder(buyerA, OrderStatus.DELIVERED, day(4), "800000.00", "0.00", 80);

        List<TopSellingProductEntry> products = topProducts("REVENUE");
        assertThat(products).extracting(TopSellingProductEntry::productId).doesNotContain(vendorProductId);
        assertThat(totalOf(products)).isEqualByComparingTo("50000.00");

        // The vendor's product is filed under categoryA, the same category productA is in,
        // so a leak would show as an inflated figure on a category ProcurePal really sells
        // in - the version of this bug hardest to spot by eye.
        CategoryMixResponse mix = categoryMix();
        assertThat(mix.totalRevenue()).isEqualByComparingTo("50000.00");
        CategoryMixEntry categoryAEntry = mix.categories().stream()
                .filter(entry -> categoryA.equals(entry.categoryId()))
                .findFirst()
                .orElseThrow();
        assertThat(categoryAEntry.revenue()).isEqualByComparingTo("50000.00");
        assertThat(categoryAEntry.share()).isEqualByComparingTo("1.0000");
    }

    /**
     * The funnel is the report on ProcurePal's own fulfilment queue, and that queue has
     * been seller-scoped since V11. If the two disagreed, the same app would show two
     * different numbers for "orders awaiting dispatch".
     */
    @Test
    void theFulfilmentFunnelCountsOnlyOrdersProcurePalHasToFulfil() {
        plantOrder(buyerA, OrderStatus.CONFIRMED, "PAID", "MONNIFY", day(4), "10000.00", "0.00", 1, 0);
        plantVendorOrder(buyerA, OrderStatus.CONFIRMED, day(4), "600000.00", "0.00", 60);
        plantVendorOrder(buyerB, OrderStatus.CANCELLED, day(5), "70000.00", "0.00", 7);

        FulfilmentFunnelResponse funnel = fulfilmentFunnel();

        assertThat(funnel.totalOrders()).isEqualTo(1);
        assertThat(statusCount(funnel, OrderStatus.CONFIRMED)).isEqualTo(1);
        // The one endpoint that counts cancellations still counts only ProcurePal's.
        assertThat(statusCount(funnel, OrderStatus.CANCELLED)).isZero();
        assertThat(stage(funnel, "PLACED").orderCount()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private static OffsetDateTime day(int dayOfMonth) {
        return FROM.plusDays(dayOfMonth - 1L).plusHours(9);
    }

    /**
     * created_at is set to the same instant as placed_at: the module dates an order by
     * COALESCE(placed_at, created_at), and a PENDING_PAYMENT fixture has no placed_at at
     * all, so the fallback is the only thing that can put it in the window. JPA cannot
     * write that column ({@code @CreationTimestamp}, {@code updatable = false}), which is
     * why this is SQL.
     */
    private UUID plantOrder(
            UUID clientId,
            OrderStatus status,
            String paymentStatus,
            String paymentMethod,
            OffsetDateTime at,
            String subtotal,
            String deliveryFee,
            int quantityA,
            int quantityB) {
        return plantOrder(
                platformOwnerId(),
                clientId,
                status,
                paymentStatus,
                paymentMethod,
                at,
                subtotal,
                deliveryFee,
                quantityA,
                quantityB,
                productA,
                productB);
    }

    /**
     * The general form: any seller, any two products. The two-argument-per-line shape is
     * inherited from the ProcurePal overload above rather than redesigned, so a reader
     * comparing a vendor fixture with a ProcurePal one is comparing like with like.
     */
    private UUID plantOrder(
            UUID sellerClientId,
            UUID clientId,
            OrderStatus status,
            String paymentStatus,
            String paymentMethod,
            OffsetDateTime at,
            String subtotal,
            String deliveryFee,
            int quantityA,
            int quantityB,
            UUID lineProductA,
            UUID lineProductB) {
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
                clientId,
                // NOT NULL since V11. client_id above is the BUYER; this names who SOLD.
                // Since M6 it is the column every query in the module filters on, which
                // is why it is a parameter here rather than a hard-coded platform owner
                // id: section (h) plants vendor sales through the same helper and
                // asserts they are excluded.
                sellerClientId,
                // NOT NULL since V12. Each fixture order is its own checkout - a group
                // of one - which is exactly what V12's backfill made every historical
                // row, and what a single-seller basket still produces. Set to the
                // order's own id for the same reason the backfill uses it: it is a
                // value guaranteed unique and already to hand.
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

        // Lines are split across the two products so the totals stay internally consistent:
        // whatever the caller asked for in units, the money still adds up to `subtotal`.
        int totalUnits = quantityA + quantityB;
        if (totalUnits > 0) {
            BigDecimal perUnit = goods.divide(BigDecimal.valueOf(totalUnits), 2, java.math.RoundingMode.HALF_UP);
            if (quantityA > 0) {
                plantItem(id, lineProductA, quantityA, perUnit, goods, quantityB == 0);
            }
            if (quantityB > 0) {
                plantItem(id, lineProductB, quantityB, perUnit, goods, quantityA == 0);
            }
        }
        return id;
    }

    /**
     * One vendor account with one listed product, filed under the SAME category as
     * ProcurePal's productA.
     *
     * <p>The shared category is the point: if category-mix ever stopped filtering on
     * seller, the leak would show up as an inflated figure on a category ProcurePal really
     * does sell in, which is the version of the bug an operator would be least likely to
     * notice. Giving the vendor its own category would have made the same test pass
     * against a much weaker query.
     *
     * <p>Straight through JDBC and with no user account: nothing here logs in as the
     * vendor, it only needs to be a valid {@code seller_client_id} with a products row for
     * the top-products join to reach.
     */
    private void createVendorSeller() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        vendorSellerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                vendorSellerId,
                VENDOR_FIXTURE_PREFIX + suffix,
                VENDOR_FIXTURE_PREFIX.toLowerCase() + suffix,
                "vendor-" + suffix + "@example.com");

        vendorProductId = UUID.randomUUID();
        String sku = VENDOR_FIXTURE_PREFIX + suffix;
        String name = "Competitor Rice " + suffix;
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, category_id, "
                        + "is_active, is_marketplace_listed, approval_status, min_order_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, TRUE, 'APPROVED', 1)",
                vendorProductId,
                vendorSellerId,
                name,
                sku,
                new BigDecimal("10000.00"),
                500,
                categoryA);

        productRows.put(vendorProductId, Map.of("name", name, "sku", sku));
    }

    /** A sale by the third-party vendor, on the vendor's own product. */
    private UUID plantVendorOrder(
            UUID buyerId, OrderStatus status, OffsetDateTime at, String subtotal, String deliveryFee, int quantity) {
        return plantOrder(
                vendorSellerId,
                buyerId,
                status,
                "PAID",
                "MONNIFY",
                at,
                subtotal,
                deliveryFee,
                quantity,
                0,
                vendorProductId,
                vendorProductId);
    }

    /** {@code takesRemainder} keeps the two lines summing exactly to the order subtotal. */
    private void plantItem(UUID orderId, UUID productId, int quantity, BigDecimal unitPrice, BigDecimal goods, boolean takesRemainder) {
        BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (takesRemainder) {
            lineTotal = goods;
        }
        Map<String, Object> product = productRows.get(productId);
        jdbc.update(
                "INSERT INTO order_items (id, order_id, product_id, product_name, product_sku, unit_price, quantity, line_total) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                orderId,
                productId,
                product.get("name"),
                product.get("sku"),
                lineTotal.divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP),
                quantity,
                lineTotal);
    }

    private void plantEvent(UUID orderId, String fromStatus, String toStatus, OffsetDateTime at) {
        jdbc.update(
                "INSERT INTO order_status_events (id, order_id, from_status, to_status, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                orderId,
                fromStatus,
                toStatus,
                java.sql.Timestamp.from(at.toInstant()));
    }

    /**
     * ON DELETE CASCADE on order_items and order_status_events takes the children with it.
     *
     * <p>Order matters for the vendor fixture: {@code orders.seller_client_id} is ON DELETE
     * RESTRICT, so the orders have to go before the clients row that sold them, and the
     * products row before its owner for the same reason.
     */
    private void cleanFixtures() {
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", VENDOR_FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM clients WHERE name LIKE ?", VENDOR_FIXTURE_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------
    // Callers
    // ---------------------------------------------------------------------------------

    private MarketplaceAnalyticsSummaryResponse summary() {
        return body(get("/summary" + RANGE, MarketplaceAnalyticsSummaryResponse.class));
    }

    private List<RevenuePoint> revenueOverTime(String granularity) {
        return list("/revenue-over-time" + RANGE + "&granularity=" + granularity, new ParameterizedTypeReference<>() {});
    }

    private List<TopCustomerEntry> topCustomers(String metric) {
        return list("/top-customers" + RANGE + "&metric=" + metric, new ParameterizedTypeReference<>() {});
    }

    private List<TopSellingProductEntry> topProducts(String metric) {
        return list("/top-products" + RANGE + "&metric=" + metric, new ParameterizedTypeReference<>() {});
    }

    private CategoryMixResponse categoryMix() {
        return body(get("/category-mix" + RANGE, CategoryMixResponse.class));
    }

    private FulfilmentFunnelResponse fulfilmentFunnel() {
        return body(get("/fulfilment-funnel" + RANGE, FulfilmentFunnelResponse.class));
    }

    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        return restTemplate.exchange(BASE + path, HttpMethod.GET, new HttpEntity<>(operatorHeaders), type);
    }

    private <T> List<T> list(String path, ParameterizedTypeReference<List<T>> type) {
        ResponseEntity<List<T>> response =
                restTemplate.exchange(BASE + path, HttpMethod.GET, new HttpEntity<>(operatorHeaders), type);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static <T> T body(ResponseEntity<T> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static RevenuePoint pointOn(List<RevenuePoint> points, String period) {
        return points.stream()
                .filter(point -> point.period().equals(period))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bucket for " + period));
    }

    private static long statusCount(FulfilmentFunnelResponse funnel, OrderStatus status) {
        return funnel.statusCounts().stream()
                .filter(count -> count.status() == status)
                .findFirst()
                .orElseThrow()
                .orderCount();
    }

    private static FunnelStage stage(FulfilmentFunnelResponse funnel, String name) {
        return funnel.stages().stream()
                .filter(stage -> stage.stage().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stage " + name));
    }

    private static FunnelTransition transition(FulfilmentFunnelResponse funnel, String key) {
        return funnel.transitions().stream()
                .filter(transition -> transition.transition().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no transition " + key));
    }

    private static BigDecimal totalOf(List<TopSellingProductEntry> entries) {
        return entries.stream().map(TopSellingProductEntry::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------------------------
    // Auth
    // ---------------------------------------------------------------------------------

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

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
