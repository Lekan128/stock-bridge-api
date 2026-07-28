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
 * ProcurePal's view of its own marketplace: what it sold, to whom, and how fast it got it
 * out of the door.
 *
 * <h2>The cross-tenant read, which is the whole difficulty of this module</h2>
 * {@code orders.client_id} is the BUYER. Every aggregate here spans other tenants' rows by
 * definition, so under ProcurePal's own Hibernate tenant filter a JPQL version of any of
 * these queries would return zero - a marketplace that has apparently never sold anything,
 * with no error to explain it. The queries in {@link MarketplaceAnalyticsQueries} are
 * therefore native SQL, which the filter does not touch, exactly as
 * {@code order.CatalogStockService} does for the same reason. Authorization does not come
 * from the filter at all: {@link PlatformOwnerGuard#requirePlatformOwner()} runs first on
 * every public method here, and the controller carries VIEW_MARKETPLACE_ANALYTICS on top
 * of it. Neither gate alone is sufficient - every tenant's OWNER holds that permission.
 * Nothing in this package calls {@code Session.disableFilter}.
 *
 * <h2>What every number does and does not count</h2>
 * Stated once, in {@link MarketplacePeriodMetrics}, and inherited by the rest of the
 * module: revenue-bearing means {@code status NOT IN (CANCELLED, PENDING_PAYMENT)}, an
 * order is dated by {@code COALESCE(placed_at, created_at)}, and windows are half-open.
 * The fulfilment funnel is the one deliberate exception - it counts every order in the
 * window, drop-outs included.
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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);
        Window previous = window.previous();

        return new MarketplaceAnalyticsSummaryResponse(
                window.from(),
                window.to(),
                previous.from(),
                previous.to(),
                metricsFor(window),
                metricsFor(previous));
    }

    /**
     * Four queries per window rather than one heroic statement. They slice different
     * grains - orders, order lines, all-time first purchases, and a per-order correlated
     * lookback - and folding them together would produce a query nobody can verify against
     * the definitions it is supposed to implement.
     */
    private MarketplacePeriodMetrics metricsFor(Window window) {
        Object[] row = (Object[]) window.bind(nativeQuery(MarketplaceAnalyticsQueries.SUMMARY_ORDER_AGGREGATES))
                .getSingleResult();

        BigDecimal grossRevenue = money(row[0]);
        long orderCount = count(row[4]);

        long unitsSold = count(window.bind(nativeQuery(MarketplaceAnalyticsQueries.SUMMARY_UNITS_SOLD))
                .getSingleResult());
        long newCompanies = count(window.bind(nativeQuery(MarketplaceAnalyticsQueries.SUMMARY_NEW_COMPANIES))
                .getSingleResult());

        Object[] repeat = (Object[]) window.bind(nativeQuery(MarketplaceAnalyticsQueries.SUMMARY_REPEAT_ORDERS))
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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);
        AnalyticsGranularity effective = granularity == null ? AnalyticsGranularity.DAY : granularity;

        Query query = window.bind(nativeQuery(MarketplaceAnalyticsQueries.REVENUE_OVER_TIME))
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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);

        String sql = metric == CustomerRankMetric.ORDERS
                ? MarketplaceAnalyticsQueries.TOP_CUSTOMERS_BY_ORDERS
                : MarketplaceAnalyticsQueries.TOP_CUSTOMERS_BY_REVENUE;

        Query query = window.bind(nativeQuery(sql)).setParameter("limit", effectiveLimit(limit));

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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);

        String sql = metric == ProductRankMetric.QUANTITY
                ? MarketplaceAnalyticsQueries.TOP_PRODUCTS_BY_QUANTITY
                : MarketplaceAnalyticsQueries.TOP_PRODUCTS_BY_REVENUE;

        Query query = window.bind(nativeQuery(sql)).setParameter("limit", effectiveLimit(limit));

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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);

        List<Object[]> rows = rows(window.bind(nativeQuery(MarketplaceAnalyticsQueries.CATEGORY_MIX)));

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
        platformOwnerGuard.requirePlatformOwner();
        Window window = Window.resolve(from, to);

        List<FunnelStatusCount> statusCounts = statusCounts(window);
        long totalOrders = statusCounts.stream().mapToLong(FunnelStatusCount::orderCount).sum();

        Object[] stageRow = (Object[]) window.bind(nativeQuery(MarketplaceAnalyticsQueries.FUNNEL_STAGES))
                .getSingleResult();
        long placed = count(stageRow[0]);
        BigDecimal placedBase = BigDecimal.valueOf(placed);

        List<FunnelStage> stages = List.of(
                stage("PLACED", placed, placedBase),
                stage("CONFIRMED", count(stageRow[1]), placedBase),
                stage("DISPATCHED", count(stageRow[2]), placedBase),
                stage("DELIVERED", count(stageRow[3]), placedBase),
                stage("RECEIVED", count(stageRow[4]), placedBase));

        return new FulfilmentFunnelResponse(totalOrders, statusCounts, stages, transitions(window));
    }

    /**
     * Zero-filled across every OrderStatus so the chart's categories are stable between
     * refreshes: a bar that disappears when its count hits zero reads as a data problem.
     */
    private List<FunnelStatusCount> statusCounts(Window window) {
        Map<String, Object[]> byStatus = new LinkedHashMap<>();
        for (Object[] row : rows(window.bind(nativeQuery(MarketplaceAnalyticsQueries.FUNNEL_STATUS_COUNTS)))) {
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
    private List<FunnelTransition> transitions(Window window) {
        List<FunnelTransition> transitions = new ArrayList<>();
        for (Object[] row : rows(window.bind(nativeQuery(MarketplaceAnalyticsQueries.FUNNEL_TRANSITIONS)))) {
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

    private Query nativeQuery(String sql) {
        return entityManager.createNativeQuery(sql);
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
