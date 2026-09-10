package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.AnalyticsGranularity;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenuePeriodMetrics;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenuePoint;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformRevenueSummaryResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SellerRevenueBreakdownResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SellerRevenueEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The super admin's cross-seller revenue view: what the whole marketplace took, how it is
 * growing, and which sellers are behind it.
 *
 * <h2>Why this exists, and why it is HERE</h2>
 * M6 narrowed {@code MarketplaceAnalyticsService} to ProcurePal's own sales, because the
 * operator was reading a revenue figure that included third-party vendors' money. That
 * removed a number somebody legitimately needs. This is where it went, and the placement is
 * the point: "every seller's revenue" is a platform-operations question, so it belongs to
 * the platform operator's principal - the super admin - and not to any tenant, however
 * privileged. ProcurePal's own tenant token cannot reach this class; see
 * {@link PlatformRevenueQueries} for how that is enforced.
 *
 * <h2>Extending the super admin analytics surface rather than starting a parallel one</h2>
 * This is served by the existing {@link SuperAdminAnalyticsController}, alongside
 * {@code SuperAdminAggregateService}'s stock-movement aggregate, and its exceptions are
 * mapped by the existing {@link SuperAdminExceptionHandler} - whose {@code assignableTypes}
 * allow-list already names that controller. A second controller would have needed adding to
 * that list, and the class doc there is explicit that forgetting is a silent 500.
 *
 * <h2>How it differs from SuperAdminAggregateService, and why it is not folded into it</h2>
 * That service answers a different question from a different table: it folds
 * {@code AnalyticsService.summaryForClient} once per client to report STOCK MOVEMENT value
 * per tenant - what every company moved through its own inventory. This one reports SALES
 * from {@code orders}, which is money changing hands between tenants. Merging them would
 * produce a response where two columns called "value" mean unrelated things.
 *
 * <p>It also does not reuse the once-per-client loop, and deliberately: that shape is fine
 * at N clients doing N cheap per-tenant queries, but a per-seller revenue breakdown over
 * {@code orders} is one GROUP BY, and running it once per client would be N round trips to
 * compute something SQL already groups for free - while also being unable to answer
 * "sellers who actually traded" without post-filtering.
 *
 * <h2>No tenant filter, and therefore no escape hatch</h2>
 * A super admin principal is not a {@code TenantPrincipal}, so
 * {@code TenantResolutionFilter} never enables the Hibernate tenant filter for these
 * requests and there is nothing for {@code readAcrossTenants} to lift. Reaching for it
 * would be cargo cult: it would also fail, because it asserts the caller is the PLATFORM
 * OWNER TENANT, which a super admin is not. Cross-tenant reads are the normal case here,
 * exactly as they are in {@code ProductModerationService}, and are handled the same way -
 * explicit predicates where scoping is wanted, and nothing that pretends to be one where it
 * is not.
 *
 * <h2>Rounding, division and time zones</h2>
 * Money at 2dp HALF_UP, ratios at 4dp. Every division guards its denominator and yields
 * zero rather than null, with ONE deliberate exception: {@code revenueGrowthRate} is null
 * when the previous window was empty, because a percentage change from zero is undefined
 * and any number there would be a lie about a seller's first trading month. See
 * {@link SellerRevenueEntry}. Window predicates compare absolute instants and are
 * time-zone-free; bucketing is not - {@code date_trunc} resolves boundaries in the database
 * session's time zone, which is what a chart wants and which the frontend's local-midnight
 * bounds keep tidy.
 */
@Service
@RequiredArgsConstructor
public class PlatformRevenueService {

    /**
     * Two years. Wide enough for a month-bucketed multi-year view, narrow enough that no
     * single request can ask the database to scan the whole order history twice (the
     * summary and the breakdown both run the preceding window as well). Rejected rather
     * than clamped, and the same bound the per-seller modules use, so the three screens
     * refuse the same ranges.
     */
    private static final Duration MAX_RANGE = Duration.ofDays(731);

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------------------------
    // Totals + growth
    // ---------------------------------------------------------------------------------

    /**
     * Total marketplace revenue for the window and for the one immediately before it,
     * computed identically so the client can diff them field by field. The comparison
     * window is {@code [from - span, from)} - same length, ending where this one starts -
     * which is the rule every other analytics service in the application already uses.
     */
    @Transactional(readOnly = true)
    public PlatformRevenueSummaryResponse summary(
            OffsetDateTime from,
            OffsetDateTime to,
            UUID sellerId,
            OrderStatus status,
            PaymentStatus paymentStatus) {
        Window window = Window.resolve(from, to);
        Window previous = window.previous();
        Filters filters = new Filters(sellerId, status, paymentStatus);

        return new PlatformRevenueSummaryResponse(
                window.from(),
                window.to(),
                previous.from(),
                previous.to(),
                metricsFor(window, filters),
                metricsFor(previous, filters));
    }

    private PlatformRevenuePeriodMetrics metricsFor(Window window, Filters filters) {
        Object[] row = (Object[]) bind(PlatformRevenueQueries.TOTALS + filters.fragment(), window, filters)
                .getSingleResult();
        long unitsSold =
                count(bind(PlatformRevenueQueries.UNITS_SOLD + filters.fragment(), window, filters).getSingleResult());

        BigDecimal grossRevenue = money(row[0]);
        long orderCount = count(row[4]);

        return new PlatformRevenuePeriodMetrics(
                grossRevenue,
                money(row[1]),
                money(row[2]),
                money(row[3]),
                orderCount,
                ratio(grossRevenue, BigDecimal.valueOf(orderCount), 2),
                unitsSold,
                count(row[5]),
                count(row[6]),
                count(row[7]),
                money(row[8]));
    }

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /**
     * The growth curve. Zero-filled by the query itself - see
     * {@link PlatformRevenueQueries#REVENUE_OVER_TIME_HEAD}, and note that the filter
     * fragment is appended to the JOIN condition rather than a WHERE, which is what keeps
     * the zeros.
     */
    @Transactional(readOnly = true)
    public List<PlatformRevenuePoint> revenueOverTime(
            OffsetDateTime from,
            OffsetDateTime to,
            AnalyticsGranularity granularity,
            UUID sellerId,
            OrderStatus status,
            PaymentStatus paymentStatus) {
        Window window = Window.resolve(from, to);
        Filters filters = new Filters(sellerId, status, paymentStatus);
        AnalyticsGranularity effective = granularity == null ? AnalyticsGranularity.DAY : granularity;

        String sql = PlatformRevenueQueries.REVENUE_OVER_TIME_HEAD
                + filters.fragment()
                + PlatformRevenueQueries.REVENUE_OVER_TIME_TAIL;

        Query query = bind(sql, window, filters)
                .setParameter("granularity", effective.datePart())
                .setParameter("step", effective.step());

        return rows(query).stream()
                .map(row -> new PlatformRevenuePoint(
                        (String) row[0], money(row[1]), count(row[2]), count(row[4]), count(row[3])))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Per-seller breakdown
    // ---------------------------------------------------------------------------------

    /**
     * Which sellers took what, with each one's preceding window alongside so growth is a
     * property of the row rather than something the client has to fetch twice and join.
     *
     * <h2>Why the two windows are merged here rather than in one statement</h2>
     * A self-join or a two-window CTE would compute the same thing, but it would also have
     * to decide in SQL what happens to a seller present in only one of the windows - and
     * the answer (keep it, with a zero on the missing side) is exactly the case an operator
     * cares most about. Doing the outer union of the two key sets in Java makes that
     * decision visible instead of hiding it in a join type.
     */
    @Transactional(readOnly = true)
    public SellerRevenueBreakdownResponse bySeller(
            OffsetDateTime from,
            OffsetDateTime to,
            UUID sellerId,
            OrderStatus status,
            PaymentStatus paymentStatus,
            SellerRevenueSort sort,
            Boolean ascending) {
        Window window = Window.resolve(from, to);
        Window previous = window.previous();
        Filters filters = new Filters(sellerId, status, paymentStatus);

        Map<UUID, SellerRow> current = sellerRows(window, filters);
        Map<UUID, SellerRow> before = sellerRows(previous, filters);

        BigDecimal total = current.values().stream()
                .map(SellerRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal previousTotal = before.values().stream()
                .map(SellerRow::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Every seller present in EITHER window. A vendor that traded last month and not
        // this one is the row an operator most needs; a table keyed only on the current
        // window would delete it.
        Set<UUID> allSellers = new LinkedHashSet<>(current.keySet());
        allSellers.addAll(before.keySet());

        List<SellerRevenueEntry> entries = new ArrayList<>(allSellers.size());
        for (UUID id : allSellers) {
            SellerRow now = current.get(id);
            SellerRow then = before.get(id);
            // Identity comes from whichever window has the row; both name the same
            // clients row, so "the one that exists" is unambiguous rather than a choice.
            SellerRow identity = now != null ? now : then;

            BigDecimal revenue = now == null ? zero(2) : now.revenue();
            BigDecimal previousRevenue = then == null ? zero(2) : then.revenue();
            long orderCount = now == null ? 0L : now.orderCount();

            entries.add(new SellerRevenueEntry(
                    id,
                    identity.name(),
                    identity.slug(),
                    identity.clientType(),
                    identity.platformOwner(),
                    identity.active(),
                    revenue,
                    now == null ? zero(2) : now.merchandiseRevenue(),
                    orderCount,
                    ratio(revenue, BigDecimal.valueOf(orderCount), 2),
                    now == null ? 0L : now.unitsSold(),
                    now == null ? 0L : now.buyingCompanyCount(),
                    ratio(revenue, total, 4),
                    previousRevenue,
                    revenue.subtract(previousRevenue),
                    growthRate(revenue, previousRevenue)));
        }

        return new SellerRevenueBreakdownResponse(
                window.from(),
                window.to(),
                previous.from(),
                previous.to(),
                total.setScale(2, RoundingMode.HALF_UP),
                previousTotal.setScale(2, RoundingMode.HALF_UP),
                sorted(entries, sort, ascending));
    }

    /** The order-level row plus its units, merged by seller id - see {@code UNITS_BY_SELLER}. */
    private Map<UUID, SellerRow> sellerRows(Window window, Filters filters) {
        Map<UUID, Long> units = new LinkedHashMap<>();
        String unitsSql = PlatformRevenueQueries.UNITS_BY_SELLER
                + filters.fragment()
                + PlatformRevenueQueries.UNITS_BY_SELLER_GROUP_BY;
        for (Object[] row : rows(bind(unitsSql, window, filters))) {
            units.put((UUID) row[0], count(row[1]));
        }

        String sql = PlatformRevenueQueries.BY_SELLER
                + filters.fragment()
                + PlatformRevenueQueries.BY_SELLER_GROUP_BY;

        Map<UUID, SellerRow> byId = new LinkedHashMap<>();
        for (Object[] row : rows(bind(sql, window, filters))) {
            UUID id = (UUID) row[0];
            byId.put(id, new SellerRow(
                    (String) row[1],
                    (String) row[2],
                    clientType(row[3]),
                    Boolean.TRUE.equals(row[4]),
                    Boolean.TRUE.equals(row[5]),
                    money(row[6]),
                    money(row[7]),
                    count(row[8]),
                    units.getOrDefault(id, 0L),
                    count(row[9])));
        }
        return byId;
    }

    /**
     * Ordering in Java rather than in the SQL.
     *
     * <p>Two of the six sort keys - average order value and growth - do not exist as
     * columns: they are derived from the two windows after the merge. Sorting the others in
     * SQL would mean two different ordering mechanisms for one control, and the codebase's
     * own warning about conditional ORDER BY (see {@code MarketplaceAnalyticsQueries}: a
     * cast in an ORDER BY is how a ranking quietly starts sorting by something else) applies
     * with more force when half the keys cannot be expressed there at all. The result set is
     * one row per SELLER, which is a number bounded by how many companies the operator has
     * onboarded, so there is nothing here to page and nothing to gain from pushing it down.
     *
     * <p>Every comparator falls back to name so the order is total and a refresh never
     * reshuffles equal rows.
     */
    private static List<SellerRevenueEntry> sorted(
            List<SellerRevenueEntry> entries, SellerRevenueSort sort, Boolean ascending) {
        SellerRevenueSort effective = sort == null ? SellerRevenueSort.REVENUE : sort;

        // Every comparator is written in ASCENDING form and reversed once below, so the
        // direction is decided in one place rather than as a per-branch mix of forwards
        // and backwards keys that has to be read twice to check.
        Comparator<SellerRevenueEntry> comparator = switch (effective) {
            case REVENUE -> Comparator.comparing(SellerRevenueEntry::revenue);
            case ORDERS -> Comparator.comparingLong(SellerRevenueEntry::orderCount);
            case UNITS -> Comparator.comparingLong(SellerRevenueEntry::unitsSold);
            case AVERAGE_ORDER_VALUE -> Comparator.comparing(SellerRevenueEntry::averageOrderValue);
            case GROWTH -> Comparator.comparing(SellerRevenueEntry::revenueGrowth);
            case NAME -> Comparator.comparing(SellerRevenueEntry::name, String.CASE_INSENSITIVE_ORDER);
        };

        // A null `ascending` means "the natural direction for this key", which differs per
        // key and is why the parameter is a Boolean rather than a primitive: money and
        // counts default to biggest-first, because "top sellers" means the big ones at the
        // top, while a name column that opened Z-to-A would just look broken. An explicit
        // value from the caller always wins.
        boolean effectiveAscending = ascending != null ? ascending : effective == SellerRevenueSort.NAME;
        if (!effectiveAscending) {
            comparator = comparator.reversed();
        }
        return entries.stream()
                .sorted(comparator.thenComparing(SellerRevenueEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    /**
     * The optional filters, as a SQL fragment plus the parameters it needs.
     *
     * <p>Kept as one object so a fragment can never be appended without its bindings being
     * appended too - the failure mode otherwise is an {@code IllegalArgumentException} at
     * runtime naming a parameter nobody set, on whichever endpoint was edited last.
     *
     * <p>Note the seller id here DOES come from the request, which is the opposite of the
     * rule every tenant-facing analytics module follows. That is safe only because this
     * surface has already proven the caller is a super admin: on a tenant surface a
     * request-supplied seller id is the entire vulnerability, which is why
     * {@code VendorSalesAnalyticsService} takes its id from a guard and says so loudly.
     */
    private record Filters(UUID sellerId, OrderStatus status, PaymentStatus paymentStatus) {

        String fragment() {
            StringBuilder sql = new StringBuilder();
            if (sellerId != null) {
                sql.append(PlatformRevenueQueries.SELLER_FILTER);
            }
            if (status != null) {
                sql.append(PlatformRevenueQueries.STATUS_FILTER);
            }
            if (paymentStatus != null) {
                sql.append(PlatformRevenueQueries.PAYMENT_STATUS_FILTER);
            }
            return sql.toString();
        }

        Query bind(Query query) {
            if (sellerId != null) {
                query.setParameter("sellerId", sellerId);
            }
            if (status != null) {
                query.setParameter("status", status.name());
            }
            if (paymentStatus != null) {
                query.setParameter("paymentStatus", paymentStatus.name());
            }
            return query;
        }
    }

    /** The raw per-seller aggregate for ONE window, before the two windows are diffed. */
    private record SellerRow(
            String name,
            String slug,
            ClientType clientType,
            boolean platformOwner,
            boolean active,
            BigDecimal revenue,
            BigDecimal merchandiseRevenue,
            long orderCount,
            long unitsSold,
            long buyingCompanyCount) {
    }

    private Query bind(String sql, Window window, Filters filters) {
        return filters.bind(window.bind(entityManager.createNativeQuery(sql)));
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static BigDecimal money(Object value) {
        BigDecimal decimal = value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
        return decimal.setScale(2, RoundingMode.HALF_UP);
    }

    private static long count(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
    }

    /** The clients.client_type column arrives as text from a native query. */
    private static ClientType clientType(Object value) {
        return value == null ? null : ClientType.valueOf(value.toString());
    }

    /** Zero when the denominator is zero, rather than null or an exception - see the class doc. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (denominator == null || denominator.signum() == 0) {
            return zero(scale);
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_UP);
    }

    /**
     * Null when the previous window was empty - the one place in this class a null is
     * deliberate. See {@link SellerRevenueEntry#revenueGrowthRate()}.
     */
    private static BigDecimal growthRate(BigDecimal revenue, BigDecimal previousRevenue) {
        if (previousRevenue == null || previousRevenue.signum() == 0) {
            return null;
        }
        return revenue.subtract(previousRevenue).divide(previousRevenue, 4, RoundingMode.HALF_UP);
    }

    /**
     * A validated, bindable date range.
     *
     * <p>Defaults, bounds and the previous-window rule are identical to
     * {@code MarketplaceAnalyticsService}'s and {@code VendorSalesAnalyticsService}'s:
     * month-to-date when either bound is omitted, so the operator's screen, a vendor's own
     * screen and this one all open on the same period and a number quoted from one can be
     * checked against the others.
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
