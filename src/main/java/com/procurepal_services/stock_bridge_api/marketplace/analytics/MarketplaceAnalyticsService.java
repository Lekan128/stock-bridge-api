package com.procurepal_services.stock_bridge_api.marketplace.analytics;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.marketplace.PlatformOwnerGuard;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProcurePal's view of ITS OWN sales: what it sold, to whom, and how fast it got it out of
 * the door.
 *
 * <h2>Own sales, not the marketplace's - the M6 correction</h2>
 * Until M6 every query in this module was unscoped, and the class doc said so with some
 * pride: the numbers were "properties of the MARKETPLACE, not of any one tenant". That was
 * true while ProcurePal was the only seller. It stopped being true the moment third-party
 * vendors could sell, and nothing about the SQL changed to mark the transition - the same
 * statements simply began adding other companies' takings to the operator's revenue card.
 * ProcurePal was reading a revenue figure that included money it does not receive.
 *
 * <p>Every statement in {@link MarketplaceAnalyticsQueries} therefore now carries
 * {@code seller_client_id = <ProcurePal>}, spliced from one constant, bound from
 * {@link #requireOperatorId()} and never from a request parameter. The model is
 * {@code vendor.analytics.VendorSalesAnalyticsService}, which has done own-sales scoping
 * correctly since M5; this module was the one that had drifted.
 *
 * <h2>Per-metric ruling: what narrowed, and what deliberately did not</h2>
 * Everything served by {@link MarketplaceAnalyticsController} narrowed. Each decision, and
 * the one nearby surface that stayed wide:
 * <ul>
 *   <li><b>summary</b> - narrowed. Revenue, merchandise, delivery fees, cash collected,
 *       order count, AOV, units, outstanding and pay-on-delivery exposure are all money
 *       ProcurePal does or does not receive. {@code activeCompanies}, {@code newCompanies}
 *       and the repeat-order rate narrowed with them: on a seller's own page "new company"
 *       has to mean "new to me", and a customer base is measured by who bought from you.
 *       See {@code SUMMARY_NEW_COMPANIES} for the subtlety.</li>
 *   <li><b>revenue-over-time</b> - narrowed. It is the summary's revenue, bucketed.</li>
 *   <li><b>top-customers</b> - narrowed, in-window AND lifetime. "Lifetime spend" on
 *       ProcurePal's page means spend with ProcurePal; ranking companies partly on money
 *       that went to a competing vendor would be both wrong and a disclosure of that
 *       vendor's book.</li>
 *   <li><b>top-products</b> - narrowed. A best-sellers chart listing a vendor's SKUs is
 *       not ProcurePal's merchandising signal, it is somebody else's.</li>
 *   <li><b>category-mix</b> - narrowed, and this is the one that could have gone either
 *       way. Marketplace-wide CATEGORY DEMAND is a legitimate platform-operations view -
 *       VENDOR_RESEARCH.md Section C item 7 names it as the aggregate half of the split.
 *       But this endpoint does not report demand: it reports a share of REVENUE and
 *       returns the total alongside it, and an unscoped total is precisely the figure the
 *       stakeholder objected to. A cross-seller demand view belongs on the super admin
 *       surface, where cross-seller revenue now lives.</li>
 *   <li><b>fulfilment-funnel</b> - narrowed. It is the report on ProcurePal's own
 *       fulfilment queue, and that queue has been seller-scoped since V11 - see
 *       {@code MarketplaceOrderAdminService}, "ProcurePal is not privileged HERE". A funnel
 *       counting more orders than the queue it describes would put two different numbers
 *       for "awaiting dispatch" on two screens of the same app. It also carries order
 *       VALUE per status, so leaving it wide would have left vendor money on this page.</li>
 *   <li><b>NOT narrowed, and not touched: {@code MarketplaceOrderAdminService.customers}</b>
 *       ({@code /api/marketplace/admin/customers}). ProcurePal sees every buying COMPANY
 *       there, including ones that have never bought from it, while a vendor sees only its
 *       own buyers. That asymmetry is deliberate and predates M6: the roster is a prospect
 *       list and an ops screen, not a revenue figure, and its per-company AGGREGATES were
 *       already seller-scoped. Narrowing the roster would delete a working screen to fix a
 *       problem it does not have.</li>
 * </ul>
 *
 * <h2>Then why is this not just VendorSalesAnalyticsService?</h2>
 * Because after M6 the two differ in DEPTH, not in scope. Both report one seller's own
 * sales. This module additionally reports named customers, new/repeat rates, category mix
 * and funnel hop durations - the buyer-identity half that
 * {@code VendorSalesPeriodMetrics} deliberately withholds from vendors. Folding them
 * together would mean one class with a "which seller" parameter and a permission check
 * deciding how much of the answer to return, which is one {@code if} away from the leak
 * Section C item 7 calls a commercial incident.
 *
 * <h2>The cross-tenant read, which is still the difficulty of this module</h2>
 * {@code orders.client_id} is the BUYER. Every aggregate here spans other tenants' rows by
 * definition - they are the companies that bought from ProcurePal - so under ProcurePal's
 * own Hibernate tenant filter a JPQL version of any of these queries would return zero: a
 * seller that has apparently never sold anything, with no error to explain it. The queries
 * in {@link MarketplaceAnalyticsQueries} are therefore native SQL, which the filter does
 * not touch, exactly as {@code order.CatalogStockService} does for the same reason.
 * Authorization does not come from the filter at all:
 * {@link PlatformOwnerGuard#requirePlatformOwner()} runs first on every public method here,
 * and the controller carries VIEW_MARKETPLACE_ANALYTICS on top of it. Neither gate alone is
 * sufficient - every tenant's OWNER holds that permission. Row scoping is carried by the
 * {@code seller_client_id} predicate, which is a third thing again and the only one that
 * decides which rows are ProcurePal's. Nothing in this package calls
 * {@code Session.disableFilter}.
 *
 * <h2>What every number does and does not count</h2>
 * Stated once, in {@link MarketplacePeriodMetrics}, and inherited by the rest of the
 * module: revenue-bearing means {@code status NOT IN (CANCELLED, PENDING_PAYMENT)}, an
 * order is dated by {@code COALESCE(placed_at, created_at)}, and windows are half-open.
 * The fulfilment funnel is the one deliberate exception - it counts every order in the
 * window, drop-outs included. All of it now sits behind the seller predicate, which is not
 * an exception to anything: a cancelled vendor order is not ProcurePal's drop-out either.
 *
 * <h2>Rounding and division</h2>
 * Ratios come back as 0..1 BigDecimals at 4dp and money at 2dp, both HALF_UP. Every
 * division guards its denominator and yields zero rather than null, so the client never
 * has to distinguish "no orders" from "field missing" - which matters more than usual
 * here, because {@code default-property-inclusion: non_null} would drop a null outright.
 * The one place null is used on purpose is a transition duration with no sample: see
 * {@link FunnelTransition}.
 *
 * <h2>A note on time zones</h2>
 * Window predicates compare absolute instants and are time-zone-free. Bucketing is not:
 * {@code date_trunc} resolves day/week/month boundaries in the DATABASE SESSION's time
 * zone, which pgjdbc sets from the JVM default, and nothing here pins it. That is the
 * behaviour a chart wants - buckets are local days - but it has one consequence worth
 * knowing: a window whose bounds are not local midnights can clip a partial bucket at
 * either end. The frontend's DateRangeControl sends local midnights, so it does not.
 */
@Service
@RequiredArgsConstructor
public class MarketplaceAnalyticsService {

    /**
     * Two years. Wide enough for a month-bucketed multi-year view, narrow enough that no
     * single request can ask the database to scan the whole order history twice (the
     * summary also runs the preceding window of the same length). Rejected rather than
     * clamped - see {@link InvalidAnalyticsRangeException}.
     */
    private static final Duration MAX_RANGE = Duration.ofDays(731);

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    /** Shown for order lines whose product has no category, rather than dropping the slice. */
    private static final String UNCATEGORISED = "Uncategorised";

    private static final BigDecimal SECONDS_PER_HOUR = new BigDecimal("3600");

    private final PlatformOwnerGuard platformOwnerGuard;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------------

    /**
     * This window and the one immediately before it, computed identically so the client can
     * diff them field by field. The comparison window is {@code [from - span, from)} - same
     * length, ending where this one starts - which makes "this month vs last month" and
     * "last 7 days vs the 7 before" the same rule rather than two.
     */
    @Transactional(readOnly = true)
    public MarketplaceAnalyticsSummaryResponse summary(OffsetDateTime from, OffsetDateTime to) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);
        Window previous = window.previous();

        return new MarketplaceAnalyticsSummaryResponse(
                window.from(),
                window.to(),
                previous.from(),
                previous.to(),
                metricsFor(sellerId, window),
                metricsFor(sellerId, previous));
    }

    /**
     * Four queries per window rather than one heroic statement. They slice different
     * grains - orders, order lines, all-time first purchases, and a per-order correlated
     * lookback - and folding them together would produce a query nobody can verify against
     * the definitions it is supposed to implement.
     */
    private MarketplacePeriodMetrics metricsFor(UUID sellerId, Window window) {
        Object[] row = (Object[]) bind(MarketplaceAnalyticsQueries.SUMMARY_ORDER_AGGREGATES, sellerId, window)
                .getSingleResult();

        BigDecimal grossRevenue = money(row[0]);
        long orderCount = count(row[4]);

        long unitsSold =
                count(bind(MarketplaceAnalyticsQueries.SUMMARY_UNITS_SOLD, sellerId, window).getSingleResult());
        long newCompanies =
                count(bind(MarketplaceAnalyticsQueries.SUMMARY_NEW_COMPANIES, sellerId, window).getSingleResult());

        Object[] repeat = (Object[]) bind(MarketplaceAnalyticsQueries.SUMMARY_REPEAT_ORDERS, sellerId, window)
                .getSingleResult();

        return new MarketplacePeriodMetrics(
                grossRevenue,
                money(row[1]),
                money(row[2]),
                money(row[3]),
                orderCount,
                ratio(grossRevenue, BigDecimal.valueOf(orderCount), 2),
                unitsSold,
                count(row[5]),
                newCompanies,
                ratio(BigDecimal.valueOf(count(repeat[1])), BigDecimal.valueOf(count(repeat[0])), 4),
                count(row[6]),
                money(row[7]),
                count(row[8]),
                money(row[9]),
                count(row[10]),
                money(row[11]),
                count(row[12]));
    }

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /** Zero-filled by the query itself - see {@link MarketplaceAnalyticsQueries#REVENUE_OVER_TIME}. */
    @Transactional(readOnly = true)
    public List<RevenuePoint> revenueOverTime(OffsetDateTime from, OffsetDateTime to, AnalyticsGranularity granularity) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);
        AnalyticsGranularity effective = granularity == null ? AnalyticsGranularity.DAY : granularity;

        Query query = bind(MarketplaceAnalyticsQueries.REVENUE_OVER_TIME, sellerId, window)
                .setParameter("granularity", effective.datePart())
                .setParameter("step", effective.step());

        return rows(query).stream()
                .map(row -> new RevenuePoint(
                        (String) row[0], money(row[1]), count(row[2]), count(row[4]), count(row[3])))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Rankings
    // ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TopCustomerEntry> topCustomers(
            OffsetDateTime from, OffsetDateTime to, Integer limit, CustomerRankMetric metric) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);

        String sql = metric == CustomerRankMetric.ORDERS
                ? MarketplaceAnalyticsQueries.TOP_CUSTOMERS_BY_ORDERS
                : MarketplaceAnalyticsQueries.TOP_CUSTOMERS_BY_REVENUE;

        Query query = bind(sql, sellerId, window).setParameter("limit", effectiveLimit(limit));

        return rows(query).stream()
                .map(row -> new TopCustomerEntry(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        money(row[3]),
                        count(row[4]),
                        count(row[5]),
                        money(row[6]),
                        count(row[7]),
                        timestamp(row[8]),
                        timestamp(row[9])))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TopSellingProductEntry> topProducts(
            OffsetDateTime from, OffsetDateTime to, Integer limit, ProductRankMetric metric) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);

        String sql = metric == ProductRankMetric.QUANTITY
                ? MarketplaceAnalyticsQueries.TOP_PRODUCTS_BY_QUANTITY
                : MarketplaceAnalyticsQueries.TOP_PRODUCTS_BY_REVENUE;

        Query query = bind(sql, sellerId, window).setParameter("limit", effectiveLimit(limit));

        return rows(query).stream()
                .map(row -> new TopSellingProductEntry(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        row[3] == null ? UNCATEGORISED : (String) row[3],
                        money(row[4]),
                        count(row[5]),
                        count(row[6]),
                        count(row[7])))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Category mix
    // ---------------------------------------------------------------------------------

    /**
     * Shares are computed here rather than in SQL so they are guaranteed to be taken
     * against the same total that is returned alongside them - a window function over the
     * grouped set would be a second place for that total to be defined.
     */
    @Transactional(readOnly = true)
    public CategoryMixResponse categoryMix(OffsetDateTime from, OffsetDateTime to) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);

        List<Object[]> rows = rows(bind(MarketplaceAnalyticsQueries.CATEGORY_MIX, sellerId, window));

        BigDecimal total = rows.stream()
                .map(row -> money(row[2]))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<CategoryMixEntry> categories = rows.stream()
                .map(row -> {
                    BigDecimal revenue = money(row[2]);
                    return new CategoryMixEntry(
                            (UUID) row[0],
                            row[1] == null ? UNCATEGORISED : (String) row[1],
                            revenue,
                            count(row[3]),
                            count(row[4]),
                            ratio(revenue, total, 4));
                })
                .toList();

        return new CategoryMixResponse(total, categories);
    }

    // ---------------------------------------------------------------------------------
    // Fulfilment funnel
    // ---------------------------------------------------------------------------------

    /** The one endpoint that counts CANCELLED and PENDING_PAYMENT orders - see the DTO. */
    @Transactional(readOnly = true)
    public FulfilmentFunnelResponse fulfilmentFunnel(OffsetDateTime from, OffsetDateTime to) {
        UUID sellerId = requireOperatorId();
        Window window = Window.resolve(from, to);

        List<FunnelStatusCount> statusCounts = statusCounts(sellerId, window);
        long totalOrders = statusCounts.stream().mapToLong(FunnelStatusCount::orderCount).sum();

        Object[] stageRow = (Object[]) bind(MarketplaceAnalyticsQueries.FUNNEL_STAGES, sellerId, window)
                .getSingleResult();
        long placed = count(stageRow[0]);
        BigDecimal placedBase = BigDecimal.valueOf(placed);

        List<FunnelStage> stages = List.of(
                stage("PLACED", placed, placedBase),
                stage("CONFIRMED", count(stageRow[1]), placedBase),
                stage("DISPATCHED", count(stageRow[2]), placedBase),
                stage("DELIVERED", count(stageRow[3]), placedBase),
                stage("RECEIVED", count(stageRow[4]), placedBase));

        return new FulfilmentFunnelResponse(totalOrders, statusCounts, stages, transitions(sellerId, window));
    }

    /**
     * Zero-filled across every OrderStatus so the chart's categories are stable between
     * refreshes: a bar that disappears when its count hits zero reads as a data problem.
     */
    private List<FunnelStatusCount> statusCounts(UUID sellerId, Window window) {
        Map<String, Object[]> byStatus = new LinkedHashMap<>();
        for (Object[] row : rows(bind(MarketplaceAnalyticsQueries.FUNNEL_STATUS_COUNTS, sellerId, window))) {
            byStatus.put((String) row[0], row);
        }

        List<FunnelStatusCount> counts = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            Object[] row = byStatus.get(status.name());
            counts.add(row == null
                    ? new FunnelStatusCount(status, 0L, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    : new FunnelStatusCount(status, count(row[1]), money(row[2])));
        }
        return counts;
    }

    /**
     * Seconds out of Postgres, hours into the DTO: an operator thinks in "we take a day and
     * a half to dispatch", and hours is the unit that survives both a 20-minute hop and a
     * two-week one without scientific notation.
     */
    private List<FunnelTransition> transitions(UUID sellerId, Window window) {
        List<FunnelTransition> transitions = new ArrayList<>();
        for (Object[] row : rows(bind(MarketplaceAnalyticsQueries.FUNNEL_TRANSITIONS, sellerId, window))) {
            String key = (String) row[0];
            long sampleSize = count(row[1]);
            String[] ends = key.split("_TO_");
            transitions.add(new FunnelTransition(
                    key, ends[0], ends[1], sampleSize, hours(row[2], sampleSize), hours(row[3], sampleSize)));
        }
        return transitions;
    }

    /** Null (and so absent from the JSON) when nothing completed the hop - never a misleading zero. */
    private static BigDecimal hours(Object seconds, long sampleSize) {
        if (sampleSize == 0 || seconds == null) {
            return null;
        }
        return new BigDecimal(seconds.toString()).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private static FunnelStage stage(String name, long reached, BigDecimal placedBase) {
        return new FunnelStage(name, reached, ratio(BigDecimal.valueOf(reached), placedBase, 4));
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    /**
     * Both gates and the row scope in one call: every public method above starts here, and
     * the id it returns is the only value ever bound to {@code :sellerId}. Nothing takes a
     * seller id from a request, which is what makes "ProcurePal's own sales" a property of
     * the authenticated tenant rather than of a query string somebody could edit.
     *
     * <p>{@code requirePlatformOwner} and not {@code requireSeller}: this surface is the
     * OPERATOR's, and its extra depth - named customers, new/repeat rates, category mix -
     * is exactly what a vendor must not be handed about its buyers. Vendors have their own
     * route; see {@code VendorSalesAnalyticsService}.
     */
    private UUID requireOperatorId() {
        return platformOwnerGuard.requirePlatformOwner().getId();
    }

    private Query bind(String sql, UUID sellerId, Window window) {
        return window.bind(entityManager.createNativeQuery(sql)).setParameter("sellerId", sellerId);
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    /** 1..50, defaulting to 10. Clamped rather than rejected: a limit is a display choice, not a claim about the data. */
    private static int effectiveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static BigDecimal money(Object value) {
        BigDecimal decimal = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static OffsetDateTime timestamp(Object value) {
        if (value == null) {
            return null;
        }
        // Postgres timestamptz arrives as java.sql.Timestamp or OffsetDateTime depending on
        // the driver's type resolution for a CTE column; both are normalised to UTC here so
        // the wire format never depends on which one turned up.
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Timestamp sqlTimestamp) {
            return sqlTimestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass());
    }

    /** Zero when the denominator is zero, rather than null or an exception - see the class doc. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    /**
     * A validated, bindable date range.
     *
     * Defaults match the tenant dashboard's {@code AnalyticsService}: month-to-date when
     * either bound is omitted, so the two analytics screens open on the same period and a
     * reader flipping between them is comparing like with like.
     */
    private record Window(OffsetDateTime from, OffsetDateTime to) {

        static Window resolve(OffsetDateTime from, OffsetDateTime to) {
            OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime effectiveFrom = from != null
                    ? from
                    : effectiveTo.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

            if (effectiveFrom.isAfter(effectiveTo)) {
                throw new InvalidAnalyticsRangeException("'from' must not be after 'to'.");
            }
            if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
                throw new InvalidAnalyticsRangeException(
                        "Date range is too wide - " + MAX_RANGE.toDays() + " days is the maximum.");
            }
            return new Window(effectiveFrom, effectiveTo);
        }

        /** Same length, ending exactly where this one begins. Half-open windows make that seamless. */
        Window previous() {
            Duration span = Duration.between(from, to);
            return new Window(from.minus(span), from);
        }

        Query bind(Query query) {
            return query.setParameter("from", from).setParameter("to", to);
        }
    }
}
