package com.procurepal_services.stock_bridge_api.vendor.analytics;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.AnalyticsGranularity;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.ProductRankMetric;
import com.procurepal_services.stock_bridge_api.order.CatalogStockService;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.vendor.VendorGuard;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorOrderStatusCount;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorRevenuePoint;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorSalesPeriodMetrics;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorSalesSummaryResponse;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorStockOutEntry;
import com.procurepal_services.stock_bridge_api.vendor.analytics.dto.VendorTopProductEntry;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A seller's view of its OWN sales: what it sold, how much of it, what is still
 * outstanding, and what it has run out of. ProcurePal's or a vendor's - the same
 * routes, and who is asking decides which orders exist.
 *
 * <h2>The permission this module exists to serve</h2>
 * V11 seeded VIEW_OWN_SALES_ANALYTICS onto the VENDOR role precisely so a vendor
 * would never have to be handed VIEW_MARKETPLACE_ANALYTICS - a code every tenant
 * OWNER already holds and whose plain meaning at the time was "the entire
 * marketplace, every seller's revenue". Until this module nothing served the new
 * code, so the permission existed and answered nothing. (M6 narrowed what the OLD
 * code reaches to ProcurePal's own sales without renaming it - see
 * {@code VendorGuard} - so the argument for keeping the codes separate is now about
 * depth rather than scope. It is no less worth having: what the operator's routes
 * add is the buyer-identity half, which is exactly what a vendor must not see.)
 *
 * <h2>Two gates, and the second one is doing the real work</h2>
 * The controller carries
 * {@code hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')}.
 * It has to accept EITHER, and the reason is a consequence V11 flagged: ProcurePal's
 * own staff do NOT hold the new code - they hold the old one, and ProcurePal is a
 * seller with own-sales numbers like anybody else. So the permission proves only
 * "this person looks at sales figures", which every tenant OWNER on the platform
 * can claim. {@link VendorGuard#requireSeller()}, called first in every public
 * method below, proves the caller's COMPANY sells; and the {@code seller_client_id}
 * predicate on every statement decides which rows are theirs. The predicate is the
 * one that actually keeps vendors apart - see {@link VendorSalesAnalyticsQueries}.
 *
 * <p>{@code requireSeller} and not {@code requireVendor}: refusing ProcurePal here
 * would deny the platform owner its own sales figures, which is the mistake
 * VendorGuard's Javadoc names as the most likely one in this feature.
 *
 * <h2>Why this is not MarketplaceAnalyticsService with a parameter</h2>
 * They compute deliberately different things, and folding them together would put
 * one {@code if} between a vendor and every other vendor's revenue. Since M6 both
 * services are seller-scoped - the marketplace one used to be unscoped by design,
 * on the grounds that its numbers were properties of the whole marketplace, which
 * stopped being true the moment a second seller existed - so the difference is now
 * DEPTH rather than scope: the operator's view adds named customers, new/repeat
 * rates, category mix and funnel timings that a vendor must never see about its
 * buyers. A shared class would therefore need a "how much may this caller see"
 * mode, and the failure of reaching the wrong one by accident is the commercial
 * incident VENDOR_RESEARCH.md Section C item 7 describes. Separate classes mean the
 * vendor path has no such mode to reach. Genuinely cross-seller figures live in
 * {@code superadmin.PlatformRevenueService}, behind a different principal entirely.
 * What IS shared is the vocabulary: {@code AnalyticsGranularity}, {@code ProductRankMetric},
 * {@code InvalidAnalyticsRangeException}, the revenue-bearing definition and the
 * window rules, so the two screens cannot disagree about what a sale is.
 *
 * <h2>What is deliberately not here</h2>
 * No top customers, no new/repeat buyer rates, no category mix across the
 * marketplace, no funnel timings benchmarked against other sellers, and nothing
 * about any seller but the caller. See {@code VendorSalesPeriodMetrics} for the
 * buyer-identity half of that and Section C item 7 for the rest.
 *
 * <h2>Rounding, division and time zones</h2>
 * Money at 2dp HALF_UP; every division guards its denominator and yields zero
 * rather than null, so the client never has to distinguish "no orders" from "field
 * missing" - which matters because {@code default-property-inclusion: non_null}
 * would drop a null outright. Window predicates compare absolute instants and are
 * time-zone-free; bucketing is not - {@code date_trunc} resolves boundaries in the
 * database session's time zone, which is what a chart wants (buckets are local
 * days) and which the frontend's local-midnight bounds keep tidy.
 */
@Service
@RequiredArgsConstructor
public class VendorSalesAnalyticsService {

    /**
     * Two years. Wide enough for a month-bucketed multi-year view, narrow enough
     * that no single request can ask the database to scan a seller's whole order
     * history twice (the summary also runs the preceding window). Rejected rather
     * than clamped - see {@link InvalidAnalyticsRangeException}.
     */
    private static final Duration MAX_RANGE = Duration.ofDays(731);

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    /** Shown for order lines whose product has no category, rather than dropping the row. */
    private static final String UNCATEGORISED = "Uncategorised";

    private final VendorGuard vendorGuard;
    private final ProductRepository productRepository;
    private final CatalogStockService catalogStockService;

    @PersistenceContext
    private EntityManager entityManager;

    // ---------------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------------

    /**
     * This window and the one immediately before it, computed identically so the
     * client can diff them field by field. The comparison window is
     * {@code [from - span, from)} - same length, ending where this one starts.
     */
    @Transactional(readOnly = true)
    public VendorSalesSummaryResponse summary(OffsetDateTime from, OffsetDateTime to) {
        UUID sellerId = requireSellerId();
        Window window = Window.resolve(from, to);
        Window previous = window.previous();

        return new VendorSalesSummaryResponse(
                window.from(),
                window.to(),
                previous.from(),
                previous.to(),
                metricsFor(sellerId, window),
                metricsFor(sellerId, previous));
    }

    private VendorSalesPeriodMetrics metricsFor(UUID sellerId, Window window) {
        Object[] row = (Object[]) bind(VendorSalesAnalyticsQueries.SUMMARY_ORDER_AGGREGATES, sellerId, window)
                .getSingleResult();

        BigDecimal grossRevenue = money(row[0]);
        long orderCount = count(row[4]);
        long unitsSold =
                count(bind(VendorSalesAnalyticsQueries.SUMMARY_UNITS_SOLD, sellerId, window).getSingleResult());

        return new VendorSalesPeriodMetrics(
                grossRevenue,
                money(row[1]),
                money(row[2]),
                money(row[3]),
                orderCount,
                ratio(grossRevenue, BigDecimal.valueOf(orderCount)),
                unitsSold,
                count(row[5]),
                money(row[6]),
                count(row[7]),
                money(row[8]),
                count(row[9]),
                money(row[10]));
    }

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /** Zero-filled by the query itself - see {@link VendorSalesAnalyticsQueries#REVENUE_OVER_TIME}. */
    @Transactional(readOnly = true)
    public List<VendorRevenuePoint> revenueOverTime(
            OffsetDateTime from, OffsetDateTime to, AnalyticsGranularity granularity) {
        UUID sellerId = requireSellerId();
        Window window = Window.resolve(from, to);
        AnalyticsGranularity effective = granularity == null ? AnalyticsGranularity.DAY : granularity;

        Query query = bind(VendorSalesAnalyticsQueries.REVENUE_OVER_TIME, sellerId, window)
                .setParameter("granularity", effective.datePart())
                .setParameter("step", effective.step());

        return rows(query).stream()
                .map(row -> new VendorRevenuePoint((String) row[0], money(row[1]), count(row[2]), count(row[3])))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Top products
    // ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<VendorTopProductEntry> topProducts(
            OffsetDateTime from, OffsetDateTime to, Integer limit, ProductRankMetric metric) {
        UUID sellerId = requireSellerId();
        Window window = Window.resolve(from, to);

        String sql = metric == ProductRankMetric.QUANTITY
                ? VendorSalesAnalyticsQueries.TOP_PRODUCTS_BY_QUANTITY
                : VendorSalesAnalyticsQueries.TOP_PRODUCTS_BY_REVENUE;

        Query query = bind(sql, sellerId, window).setParameter("limit", effectiveLimit(limit));

        return rows(query).stream()
                .map(row -> new VendorTopProductEntry(
                        (UUID) row[0],
                        (String) row[1],
                        (String) row[2],
                        row[3] == null ? UNCATEGORISED : (String) row[3],
                        money(row[4]),
                        count(row[5]),
                        count(row[6])))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Order status breakdown
    // ---------------------------------------------------------------------------------

    /**
     * Zero-filled across every {@link OrderStatus} so the chart's categories are
     * stable between refreshes: a bar that disappears when its count hits zero reads
     * as a data problem rather than as an empty queue.
     *
     * <p>The one endpoint here that counts CANCELLED and PENDING_PAYMENT orders -
     * see {@code VendorOrderStatusCount}.
     */
    @Transactional(readOnly = true)
    public List<VendorOrderStatusCount> orderStatusBreakdown(OffsetDateTime from, OffsetDateTime to) {
        UUID sellerId = requireSellerId();
        Window window = Window.resolve(from, to);

        Map<String, Object[]> byStatus = new LinkedHashMap<>();
        for (Object[] row : rows(bind(VendorSalesAnalyticsQueries.ORDER_STATUS_COUNTS, sellerId, window))) {
            byStatus.put((String) row[0], row);
        }

        List<VendorOrderStatusCount> counts = new ArrayList<>();
        for (OrderStatus status : OrderStatus.values()) {
            Object[] row = byStatus.get(status.name());
            counts.add(row == null
                    ? new VendorOrderStatusCount(status, 0L, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    : new VendorOrderStatusCount(status, count(row[1]), money(row[2])));
        }
        return counts;
    }

    // ---------------------------------------------------------------------------------
    // Stock-outs
    // ---------------------------------------------------------------------------------

    /**
     * The seller's own products that are out of sellable stock, or under their own
     * low-stock threshold.
     *
     * <h2>Why "available to sell" and not quantity_on_hand</h2>
     * A product with a full pallet in the warehouse is still unsellable if every
     * unit is committed to orders awaiting dispatch, and the storefront already says
     * so. Reporting the raw column would tell a vendor they have stock while buyers
     * are being turned away - the opposite of the point. The commitment figures come
     * from {@link CatalogStockService}, the same source the storefront's own
     * availability uses, in ONE batched query for the whole catalogue rather than
     * one per row.
     *
     * <h2>Ungrouped by time, unlike everything else in this module</h2>
     * A stock-out is a fact about now, not about a window, so this endpoint takes no
     * date range. Ordering puts the listed rows first and the emptiest first within
     * that, because a LISTED product at zero is a storefront entry a buyer is about
     * to fail to order, and an unlisted one is a decision the seller already made.
     *
     * <p>No tenant-filter escape is needed here and none is taken: {@code products}
     * is scoped to the seller's own tenant, which is the caller, so the ordinary
     * filter is the right one. The explicit {@code clientId} predicate is layer 2 as
     * usual.
     */
    @Transactional(readOnly = true)
    public List<VendorStockOutEntry> stockOuts(Integer limit) {
        UUID sellerId = requireSellerId();

        List<Product> catalogue = productRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(sellerId);
        if (catalogue.isEmpty()) {
            return List.of();
        }

        Map<UUID, Integer> committed =
                catalogStockService.committedQuantities(catalogue.stream().map(Product::getId).toList());

        return catalogue.stream()
                .map(product -> {
                    int committedQuantity = committed.getOrDefault(product.getId(), 0);
                    // Floored at zero for the same reason CatalogStockService floors
                    // it: an oversold product is at zero available, and a negative
                    // number here would read as something the seller could act on.
                    int available = Math.max(0, product.getQuantityOnHand() - committedQuantity);
                    return new VendorStockOutEntry(
                            product.getId(),
                            product.getName(),
                            product.getSku(),
                            product.getQuantityOnHand(),
                            committedQuantity,
                            available,
                            product.isMarketplaceListed(),
                            product.getLowStockThreshold());
                })
                // A threshold of null still qualifies at zero: a vendor who never set
                // one has still run out, and silence is the worst possible answer.
                .filter(entry -> entry.availableToSell() == 0
                        || (entry.lowStockThreshold() != null
                                && entry.availableToSell() <= entry.lowStockThreshold()))
                .sorted(Comparator.comparing(VendorStockOutEntry::listed)
                        .reversed()
                        .thenComparingInt(VendorStockOutEntry::availableToSell)
                        .thenComparing(VendorStockOutEntry::name, String.CASE_INSENSITIVE_ORDER))
                .limit(effectiveLimit(limit))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    /**
     * The second gate, in one place so no endpoint can be added without it: every
     * public method above starts here, and the id it returns is the only value ever
     * bound to {@code :sellerId}. Nothing takes a seller id from a request.
     */
    private UUID requireSellerId() {
        return vendorGuard.requireSeller().getId();
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

    /** Zero when the denominator is zero, rather than null or an exception - see the class doc. */
    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /**
     * A validated, bindable date range.
     *
     * <p>Defaults, bounds and the previous-window rule are identical to
     * {@code MarketplaceAnalyticsService}'s: month-to-date when either bound is
     * omitted, so a vendor's sales screen and the operator's marketplace screen open
     * on the same period and a number quoted from one can be checked against the
     * other.
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
