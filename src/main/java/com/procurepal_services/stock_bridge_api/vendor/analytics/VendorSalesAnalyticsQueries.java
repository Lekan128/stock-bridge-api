package com.procurepal_services.stock_bridge_api.vendor.analytics;

/**
 * Every SQL statement this module runs, in one place.
 *
 * <h2>The seller predicate is not optional and is not a filter</h2>
 * Read {@link #OWN_SALES} first. Every statement below splices it in, and it is
 * the ONLY thing standing between one vendor and every other vendor's revenue.
 * {@code MarketplaceAnalyticsQueries} now carries the same predicate for the same
 * reason - M6 narrowed the operator's own analytics to its own sales, having
 * previously left them unscoped on the grounds that the numbers were properties of
 * the whole marketplace. That reasoning held only while ProcurePal was the only
 * seller. The single genuinely unscoped module is
 * {@code superadmin.PlatformRevenueQueries}, which is unreachable from any tenant
 * token. Here the numbers are properties of one account, and a statement that
 * forgot the predicate
 * would not fail, return nothing, or look wrong - it would quietly report the
 * marketplace's totals as the caller's own. VENDOR_RESEARCH.md Section C item 7
 * calls the first cross-vendor leak a commercial incident, and this is where it
 * would happen.
 *
 * <p>So the predicate is a spliced constant rather than something each query
 * writes out, {@code :sellerId} is bound on every call from
 * {@link VendorSalesAnalyticsService}, and there is no constant here that lacks
 * it. A new statement that does not concatenate {@link #OWN_SALES} is a bug.
 *
 * <h2>Why native SQL</h2>
 * Same reason {@code MarketplaceAnalyticsQueries} gives, arrived at from the
 * other side. {@code orders.client_id} is the BUYER, so under the SELLER's own
 * Hibernate tenant filter a JPQL aggregate over orders matches nothing at all -
 * a vendor who has apparently never sold anything, with no error to explain it.
 * Native SQL is not subject to that filter. Nothing in this package calls
 * {@code Session.disableFilter}, and nothing needs
 * {@code VendorGuard.readOwnSales} either: that helper exists for JPA reads that
 * must escape the filter, and these are not JPA reads.
 *
 * <h2>The two definitions shared with the marketplace view</h2>
 * {@link #REVENUE_BEARING} and {@link #ORDER_AT}, copied verbatim from
 * {@code MarketplaceAnalyticsQueries} rather than reinterpreted. A vendor and the
 * operator looking at the same month have to agree about what a sale is; if these
 * ever drift, the first commission dispute starts with two different numbers and
 * no way to tell which is right.
 *
 * <h2>Bind parameters only</h2>
 * The seller id, the window and the limit are bound. The only values reaching the
 * SQL text as text are {@code date_trunc}/{@code generate_series} arguments taken
 * from the closed {@code AnalyticsGranularity} enum, and even those are bound.
 */
final class VendorSalesAnalyticsQueries {

    private VendorSalesAnalyticsQueries() {
    }

    /**
     * The one predicate that makes this module a vendor's own view rather than the
     * marketplace's.
     *
     * <p>{@code orders.seller_client_id} (V11, NOT NULL) names who SOLD; {@code
     * client_id} names who bought. Every historical row was backfilled to the
     * platform owner and every new one is stamped at checkout by the splitter, so
     * this is total: there is no order this predicate cannot classify, and no need
     * for a fallback that would have to guess.
     */
    private static final String OWN_SALES = "o.seller_client_id = :sellerId";

    /** Money that exists. CANCELLED never happened; PENDING_PAYMENT was never paid for. */
    private static final String REVENUE_BEARING = "o.status NOT IN ('CANCELLED', 'PENDING_PAYMENT')";

    /**
     * When an order counts as having happened: the moment it became real money,
     * falling back to creation for rows that never reached PLACED (abandoned
     * checkouts). Used for bucketing and for every window predicate, so the summary
     * and the series can never disagree about which period an order belongs to.
     */
    private static final String ORDER_AT = "COALESCE(o.placed_at, o.created_at)";

    /** Half-open [from, to): a period and its predecessor share a boundary without double-counting. */
    private static final String IN_WINDOW = ORDER_AT + " >= :from AND " + ORDER_AT + " < :to";

    /** Owed to a buyer and not yet handed over. DELIVERED/RECEIVED are done. */
    private static final String OUTSTANDING_STATUSES = "('PLACED', 'CONFIRMED', 'PROCESSING', 'OUT_FOR_DELIVERY')";

    // ---------------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------------

    /**
     * All order-level headline figures for one window in a single pass. FILTER
     * clauses rather than CASE-inside-SUM, so each slice's predicate stays readable
     * next to the number it produces.
     *
     * Row: [gross, merchandise, deliveryFee, collected, orderCount, outstandingCount,
     * outstandingValue, codCount, codValue, cancelledCount, cancelledValue].
     *
     * <p>Note there is no activeCompanies / newCompanies column, and no repeat-order
     * companion query. That is the scope decision, not an omission - see
     * {@code VendorSalesPeriodMetrics}.
     */
    static final String SUMMARY_ORDER_AGGREGATES =
            "SELECT "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.subtotal) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.delivery_fee) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_status = 'PAID'), 0), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.status IN " + OUTSTANDING_STATUSES + "), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.status IN " + OUTSTANDING_STATUSES + "), 0), "
                    // Pay-on-delivery exposure. For a third-party vendor this is cash
                    // the PLATFORM collected on their behalf and has not settled -
                    // payment_status is the axis that matters, not fulfilment status,
                    // since a COD order is routinely DELIVERED days before the float
                    // comes in.
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), 0), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'CANCELLED'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE o.status = 'CANCELLED'), 0) "
                    + "FROM orders o "
                    + "WHERE " + OWN_SALES + " AND " + IN_WINDOW;

    /** Units are on the lines, not the order, so they need the join the aggregate above avoids. */
    static final String SUMMARY_UNITS_SOLD =
            "SELECT COALESCE(SUM(oi.quantity), 0) "
                    + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                    + "WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW;

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /**
     * Zero-filled bucket series. generate_series LEFT JOINed to orders rather than a
     * plain GROUP BY, because a quiet week must come back as a zero: a line chart
     * that skips empty buckets draws a rise that never happened, and the client
     * cannot reconstruct the gaps without re-implementing Postgres's week/month
     * boundaries.
     *
     * <p>The order predicates - the seller pin included - sit in the JOIN condition,
     * not in a WHERE. Moving any of them out would turn the outer join back into an
     * inner one and throw the zeros away; moving the SELLER one out would do that
     * AND make the join match every seller's orders first, so it is the predicate to
     * check hardest when editing this statement.
     *
     * <p>The upper bound is nudged back a microsecond so a {@code to} landing exactly
     * on a boundary does not emit a trailing empty bucket for a period the window
     * excludes.
     *
     * Row: [period(text), revenue, orderCount, unitsSold].
     */
    static final String REVENUE_OVER_TIME =
            "SELECT to_char(b.bucket, 'YYYY-MM-DD'), "
                    + "  COALESCE(SUM(o.total), 0), "
                    + "  COUNT(o.id), "
                    + "  COALESCE(SUM(u.units), 0) "
                    + "FROM generate_series("
                    + "    date_trunc(CAST(:granularity AS text), CAST(:from AS timestamptz)), "
                    + "    date_trunc(CAST(:granularity AS text), CAST(:to AS timestamptz) - INTERVAL '1 microsecond'), "
                    + "    CAST(:step AS interval)) AS b(bucket) "
                    + "LEFT JOIN orders o "
                    + "  ON " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + "  AND date_trunc(CAST(:granularity AS text), " + ORDER_AT + ") = b.bucket "
                    + "LEFT JOIN (SELECT order_id, SUM(quantity) AS units FROM order_items GROUP BY order_id) u "
                    + "  ON u.order_id = o.id "
                    + "GROUP BY b.bucket ORDER BY b.bucket";

    // ---------------------------------------------------------------------------------
    // Top products
    // ---------------------------------------------------------------------------------

    /**
     * Aggregated over order_items, so revenue here is line_total - goods only. The
     * per-order delivery fee is not attributable to any one product, which is why
     * product revenue sums to the summary's merchandiseRevenue and not to its
     * grossRevenue.
     *
     * <p>Names come from the live catalogue row (JOIN products), not the order-line
     * snapshot, so renaming a product merges its history instead of splitting it.
     * That join is scoped by the seller predicate on {@code orders}, not by one on
     * {@code products}: an order line can only reference a product the seller sold,
     * so pinning both would be redundant - but note the consequence, which is that
     * removing the seller pin from the WHERE clause would silently start reading
     * other sellers' catalogue rows too.
     *
     * Row: [productId, name, sku, categoryName, revenue, quantity, orderCount].
     */
    private static final String TOP_PRODUCTS_BODY =
            "SELECT p.id, p.name, p.sku, pc.name, "
                    + "  COALESCE(SUM(oi.line_total), 0), "
                    + "  COALESCE(SUM(oi.quantity), 0), "
                    + "  COUNT(DISTINCT o.id) "
                    + "FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id "
                    + "JOIN products p ON p.id = oi.product_id "
                    + "LEFT JOIN product_categories pc ON pc.id = p.category_id "
                    + "WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW + " "
                    + "GROUP BY p.id, p.name, p.sku, pc.name ";

    /**
     * Two constants rather than one with a conditional ORDER BY: the sort keys are a
     * numeric and a bigint, so a CASE expression would have to cast one of them, and
     * a cast in an ORDER BY is how a ranking quietly starts sorting by something
     * else. The tie-break is always the other metric, so the order is total.
     */
    static final String TOP_PRODUCTS_BY_REVENUE = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.line_total) DESC, SUM(oi.quantity) DESC, p.name ASC LIMIT :limit";

    static final String TOP_PRODUCTS_BY_QUANTITY = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.quantity) DESC, SUM(oi.line_total) DESC, p.name ASC LIMIT :limit";

    // ---------------------------------------------------------------------------------
    // Order status breakdown
    // ---------------------------------------------------------------------------------

    /**
     * Row: [status(text), orderCount, orderValue]. Statuses with no orders are
     * absent; the service zero-fills across the whole enum.
     *
     * <p>Deliberately NOT restricted to revenue-bearing orders - see
     * {@code VendorOrderStatusCount}. A breakdown that hid the cancelled and
     * never-paid orders would hide the two statuses a seller most needs to act on.
     */
    static final String ORDER_STATUS_COUNTS =
            "SELECT o.status, COUNT(*), COALESCE(SUM(o.total), 0) "
                    + "FROM orders o WHERE " + OWN_SALES + " AND " + IN_WINDOW + " GROUP BY o.status";
}
