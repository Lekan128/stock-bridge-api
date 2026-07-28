package com.procurepal_services.stock_bridge_api.marketplace.analytics;

/**
 * Every SQL statement the module runs, in one place.
 *
 * <h2>Why native SQL, and why that is the isolation story rather than a hole in it</h2>
 * {@code orders.client_id} is the BUYER. Under ProcurePal's own Hibernate tenant filter,
 * a JPQL aggregate over orders matches nothing at all - not an error, just a marketplace
 * that appears to have never sold anything. Native SQL is not subject to that filter,
 * which is the same reasoning (and the same shape) as
 * {@code order.CatalogStockService}: the numbers being computed are properties of the
 * MARKETPLACE, not of any one tenant, so a per-tenant predicate would be wrong rather
 * than merely inconvenient. Authorization is carried entirely by
 * {@code PlatformOwnerGuard.requirePlatformOwner()}, called before every one of these
 * runs. Nothing here touches {@code Session.disableFilter}.
 *
 * <h2>The two definitions every statement shares</h2>
 * {@link #REVENUE_BEARING} - status NOT IN (CANCELLED, PENDING_PAYMENT) - and
 * {@link #ORDER_AT} - COALESCE(placed_at, created_at). They are string constants spliced
 * into these queries rather than repeated by hand precisely so that "what counts as
 * revenue" cannot drift between the summary card and the chart under it.
 *
 * <h2>Bind parameters only</h2>
 * Windows and limits are bound. The only values that reach the SQL text as text are
 * {@code date_trunc}/{@code generate_series} arguments taken from
 * {@link AnalyticsGranularity}, and even those are bound as parameters - a closed enum
 * means no caller string can reach a function name.
 */
final class MarketplaceAnalyticsQueries {

    private MarketplaceAnalyticsQueries() {
    }

    /** Money that exists. CANCELLED never happened; PENDING_PAYMENT was never paid for. */
    static final String REVENUE_BEARING = "o.status NOT IN ('CANCELLED', 'PENDING_PAYMENT')";

    /**
     * When an order counts as having happened: the moment it became real money, falling
     * back to creation for rows that never reached PLACED (abandoned checkouts). Used for
     * bucketing and for every window predicate, so the summary and the series can never
     * disagree about which period an order belongs to.
     */
    static final String ORDER_AT = "COALESCE(o.placed_at, o.created_at)";

    /** Half-open [from, to): a period and its predecessor share a boundary without double-counting. */
    private static final String IN_WINDOW = ORDER_AT + " >= :from AND " + ORDER_AT + " < :to";

    /** Owed to a customer and not yet handed over. DELIVERED/RECEIVED are done. */
    private static final String OUTSTANDING_STATUSES = "('PLACED', 'CONFIRMED', 'PROCESSING', 'OUT_FOR_DELIVERY')";

    // ---------------------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------------------

    /**
     * All order-level headline figures for one window in a single pass. FILTER clauses
     * rather than CASE-inside-SUM: the same table is being sliced eleven ways, and FILTER
     * keeps each slice's predicate readable next to the number it produces.
     *
     * Row: [gross, merchandise, deliveryFee, collected, orderCount, activeCompanies,
     * outstandingCount, outstandingValue, codCount, codValue, cancelledCount,
     * cancelledValue, abandonedCount].
     */
    static final String SUMMARY_ORDER_AGGREGATES =
            "SELECT "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.subtotal) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.delivery_fee) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_status = 'PAID'), 0), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(DISTINCT o.client_id) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.status IN " + OUTSTANDING_STATUSES + "), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.status IN " + OUTSTANDING_STATUSES + "), 0), "
                    // Pay-on-delivery exposure: promised on credit, cash not yet reconciled.
                    // payment_status is the axis that matters here, not fulfilment status -
                    // a COD order is routinely DELIVERED days before the float comes in.
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), 0), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'CANCELLED'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE o.status = 'CANCELLED'), 0), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'PENDING_PAYMENT') "
                    + "FROM orders o "
                    + "WHERE " + IN_WINDOW;

    /** Units are on the lines, not the order, so they need the join the aggregate above deliberately avoids. */
    static final String SUMMARY_UNITS_SOLD =
            "SELECT COALESCE(SUM(oi.quantity), 0) "
                    + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                    + "WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW;

    /**
     * Companies whose FIRST EVER revenue-bearing order lands in the window. The MIN is
     * taken over all time and then filtered, not taken within the window - otherwise every
     * returning customer would look new every month.
     */
    static final String SUMMARY_NEW_COMPANIES =
            "SELECT COUNT(*) FROM ("
                    + "  SELECT o.client_id, MIN(" + ORDER_AT + ") AS first_at "
                    + "  FROM orders o WHERE " + REVENUE_BEARING + " GROUP BY o.client_id"
                    + ") f WHERE f.first_at >= :from AND f.first_at < :to";

    /**
     * Repeat-ORDER rate, not repeat-customer rate: of the window's orders, how many came
     * from a company that had already bought before. "Before" means strictly earlier than
     * that order, at any time in history - so a company's first-ever order is never
     * counted as repeat even if their second lands the same afternoon.
     *
     * Row: [ordersInWindow, ordersFromReturningBuyers].
     */
    static final String SUMMARY_REPEAT_ORDERS =
            "SELECT COUNT(*), COUNT(*) FILTER (WHERE x.prior_orders > 0) FROM ("
                    + "  SELECT ("
                    + "    SELECT COUNT(*) FROM orders p "
                    + "    WHERE p.client_id = o.client_id "
                    + "      AND p.status NOT IN ('CANCELLED', 'PENDING_PAYMENT') "
                    + "      AND COALESCE(p.placed_at, p.created_at) < " + ORDER_AT
                    + "  ) AS prior_orders "
                    + "  FROM orders o WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + ") x";

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /**
     * Zero-filled bucket series. generate_series LEFT JOINed to orders rather than a plain
     * GROUP BY, because a quiet week must come back as a zero: a line chart that skips
     * empty buckets draws a rise that never happened, and the client cannot reconstruct
     * the gaps without re-implementing Postgres's week/month boundaries.
     *
     * The order predicates sit in the JOIN condition, not in a WHERE - moving them out
     * would turn the outer join back into an inner one and throw the zeros away again.
     *
     * The upper bound is nudged back a microsecond so a {@code to} that lands exactly on a
     * boundary does not emit a trailing empty bucket for a period the window excludes.
     *
     * Row: [period(text), revenue, orderCount, buyingCompanies, unitsSold].
     */
    static final String REVENUE_OVER_TIME =
            "SELECT to_char(b.bucket, 'YYYY-MM-DD'), "
                    + "  COALESCE(SUM(o.total), 0), "
                    + "  COUNT(o.id), "
                    + "  COUNT(DISTINCT o.client_id), "
                    + "  COALESCE(SUM(u.units), 0) "
                    + "FROM generate_series("
                    + "    date_trunc(CAST(:granularity AS text), CAST(:from AS timestamptz)), "
                    + "    date_trunc(CAST(:granularity AS text), CAST(:to AS timestamptz) - INTERVAL '1 microsecond'), "
                    + "    CAST(:step AS interval)) AS b(bucket) "
                    + "LEFT JOIN orders o "
                    + "  ON " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + "  AND date_trunc(CAST(:granularity AS text), " + ORDER_AT + ") = b.bucket "
                    + "LEFT JOIN (SELECT order_id, SUM(quantity) AS units FROM order_items GROUP BY order_id) u "
                    + "  ON u.order_id = o.id "
                    + "GROUP BY b.bucket ORDER BY b.bucket";

    // ---------------------------------------------------------------------------------
    // Top customers
    // ---------------------------------------------------------------------------------

    /**
     * In-window spend and all-time standing on one row. The lifetime CTE is unbounded by
     * design - it is what makes "big customer, hasn't ordered since March" visible, which
     * the in-window columns alone can never show.
     *
     * {@code is_platform_owner = FALSE} is belt-and-braces: ProcurePal cannot be its own
     * buyer today, but a marketplace analytic that could ever count the operator's own
     * name as its best customer is not one anybody would trust again.
     *
     * Row: [clientId, name, slug, revenue, orderCount, units, lifetimeSpend,
     * lifetimeOrders, firstOrderAt, lastOrderAt].
     */
    private static final String TOP_CUSTOMERS_BODY =
            "WITH scoped AS ("
                    + "  SELECT o.id, o.client_id, o.total FROM orders o "
                    + "  WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + "), "
                    + "period AS ("
                    + "  SELECT client_id, SUM(total) AS revenue, COUNT(*) AS orders FROM scoped GROUP BY client_id"
                    + "), "
                    + "units AS ("
                    + "  SELECT s.client_id, SUM(oi.quantity) AS units "
                    + "  FROM scoped s JOIN order_items oi ON oi.order_id = s.id GROUP BY s.client_id"
                    + "), "
                    + "lifetime AS ("
                    + "  SELECT o.client_id, SUM(o.total) AS spend, COUNT(*) AS orders, "
                    + "         MIN(" + ORDER_AT + ") AS first_at, MAX(" + ORDER_AT + ") AS last_at "
                    + "  FROM orders o WHERE " + REVENUE_BEARING + " GROUP BY o.client_id"
                    + ") "
                    + "SELECT c.id, c.name, c.slug, p.revenue, p.orders, COALESCE(u.units, 0), "
                    + "       COALESCE(l.spend, 0), COALESCE(l.orders, 0), l.first_at, l.last_at "
                    + "FROM period p "
                    + "JOIN clients c ON c.id = p.client_id AND c.is_platform_owner = FALSE "
                    + "LEFT JOIN units u ON u.client_id = p.client_id "
                    + "LEFT JOIN lifetime l ON l.client_id = p.client_id ";

    /**
     * Two constants rather than one with a conditional ORDER BY: the sort keys are a
     * numeric and a bigint, so a CASE expression would have to cast one of them, and a
     * cast in an ORDER BY is how a ranking quietly starts sorting by something else.
     * The tie-break is always the other metric, so the order is total and stable.
     */
    static final String TOP_CUSTOMERS_BY_REVENUE =
            TOP_CUSTOMERS_BODY + "ORDER BY p.revenue DESC, p.orders DESC, c.name ASC LIMIT :limit";

    static final String TOP_CUSTOMERS_BY_ORDERS =
            TOP_CUSTOMERS_BODY + "ORDER BY p.orders DESC, p.revenue DESC, c.name ASC LIMIT :limit";

    // ---------------------------------------------------------------------------------
    // Top products
    // ---------------------------------------------------------------------------------

    /**
     * Aggregated over order_items, so revenue here is line_total - goods only. The
     * per-order delivery fee is not attributable to any one product, which is why product
     * revenue sums to the summary's merchandiseRevenue and not to its grossRevenue.
     *
     * Names come from the live catalog row (JOIN products), not from the order-line
     * snapshot, so renaming a product merges its history instead of splitting it in two.
     *
     * Row: [productId, name, sku, categoryName, revenue, quantity, orderCount, companies].
     */
    private static final String TOP_PRODUCTS_BODY =
            "SELECT p.id, p.name, p.sku, pc.name, "
                    + "  COALESCE(SUM(oi.line_total), 0), "
                    + "  COALESCE(SUM(oi.quantity), 0), "
                    + "  COUNT(DISTINCT o.id), "
                    + "  COUNT(DISTINCT o.client_id) "
                    + "FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id "
                    + "JOIN products p ON p.id = oi.product_id "
                    + "LEFT JOIN product_categories pc ON pc.id = p.category_id "
                    + "WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW + " "
                    + "GROUP BY p.id, p.name, p.sku, pc.name ";

    static final String TOP_PRODUCTS_BY_REVENUE = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.line_total) DESC, SUM(oi.quantity) DESC, p.name ASC LIMIT :limit";

    static final String TOP_PRODUCTS_BY_QUANTITY = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.quantity) DESC, SUM(oi.line_total) DESC, p.name ASC LIMIT :limit";

    // ---------------------------------------------------------------------------------
    // Category mix
    // ---------------------------------------------------------------------------------

    /**
     * Same line-level basis as top products, grouped one level up. Products with no
     * category collapse into a single null-id row the service labels explicitly, rather
     * than being dropped - hiding them would make the shares add up to less than the
     * revenue actually taken.
     *
     * Row: [categoryId, categoryName, revenue, quantity, orderCount].
     */
    static final String CATEGORY_MIX =
            "SELECT pc.id, pc.name, "
                    + "  COALESCE(SUM(oi.line_total), 0), "
                    + "  COALESCE(SUM(oi.quantity), 0), "
                    + "  COUNT(DISTINCT o.id) "
                    + "FROM order_items oi "
                    + "JOIN orders o ON o.id = oi.order_id "
                    + "JOIN products p ON p.id = oi.product_id "
                    + "LEFT JOIN product_categories pc ON pc.id = p.category_id "
                    + "WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW + " "
                    + "GROUP BY pc.id, pc.name "
                    + "ORDER BY 3 DESC, 2 ASC";

    // ---------------------------------------------------------------------------------
    // Fulfilment funnel
    // ---------------------------------------------------------------------------------

    /**
     * The funnel's population and its milestone timestamps. Unlike everywhere else in this
     * class the window is NOT restricted to revenue-bearing orders: a funnel that hides the
     * orders that dropped out is not a funnel.
     *
     * Each milestone is the first order_status_events row for that status, falling back to
     * the denormalised column on orders. The audit trail is the source of truth per the
     * module brief; the fallback covers rows written straight into a status without an
     * event (seed data, backfills), where the milestone column is the only record left.
     */
    private static final String FUNNEL_SCOPED =
            "WITH scoped AS ("
                    + "  SELECT o.id, o.status, o.total, "
                    + "    COALESCE((SELECT MIN(e.created_at) FROM order_status_events e "
                    + "              WHERE e.order_id = o.id AND e.to_status = 'PLACED'), o.placed_at) AS placed_at, "
                    + "    COALESCE((SELECT MIN(e.created_at) FROM order_status_events e "
                    + "              WHERE e.order_id = o.id AND e.to_status = 'CONFIRMED'), o.confirmed_at) AS confirmed_at, "
                    + "    COALESCE((SELECT MIN(e.created_at) FROM order_status_events e "
                    + "              WHERE e.order_id = o.id AND e.to_status = 'OUT_FOR_DELIVERY'), o.dispatched_at) "
                    + "        AS dispatched_at, "
                    + "    COALESCE((SELECT MIN(e.created_at) FROM order_status_events e "
                    + "              WHERE e.order_id = o.id AND e.to_status = 'DELIVERED'), o.delivered_at) AS delivered_at, "
                    + "    COALESCE((SELECT MIN(e.created_at) FROM order_status_events e "
                    + "              WHERE e.order_id = o.id AND e.to_status = 'RECEIVED'), o.received_at) AS received_at "
                    + "  FROM orders o WHERE " + IN_WINDOW
                    + ") ";

    /** Row: [status(text), orderCount, orderValue]. Statuses with no orders are absent; the service zero-fills. */
    static final String FUNNEL_STATUS_COUNTS =
            "SELECT o.status, COUNT(*), COALESCE(SUM(o.total), 0) "
                    + "FROM orders o WHERE " + IN_WINDOW + " GROUP BY o.status";

    /** Row: [placed, confirmed, dispatched, delivered, received] - orders that EVER reached each milestone. */
    static final String FUNNEL_STAGES = FUNNEL_SCOPED
            + "SELECT COUNT(*) FILTER (WHERE placed_at IS NOT NULL), "
            + "       COUNT(*) FILTER (WHERE confirmed_at IS NOT NULL), "
            + "       COUNT(*) FILTER (WHERE dispatched_at IS NOT NULL), "
            + "       COUNT(*) FILTER (WHERE delivered_at IS NOT NULL), "
            + "       COUNT(*) FILTER (WHERE received_at IS NOT NULL) "
            + "FROM scoped";

    /**
     * Hop durations, one row per transition. Rows where the later timestamp precedes the
     * earlier one are excluded rather than clamped: a negative duration means the two
     * sources disagree, and averaging in a zero would quietly flatter the number.
     *
     * percentile_cont gives a true interpolated median; the mean is kept alongside it
     * because the gap between the two is itself the signal (see FunnelTransition).
     *
     * Row per transition: [key(text), sampleSize, meanSeconds, medianSeconds].
     */
    static final String FUNNEL_TRANSITIONS = FUNNEL_SCOPED
            + "SELECT 'PLACED_TO_CONFIRMED', COUNT(*), "
            + "       AVG(EXTRACT(EPOCH FROM (confirmed_at - placed_at))), "
            + "       percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (confirmed_at - placed_at))) "
            + "FROM scoped WHERE placed_at IS NOT NULL AND confirmed_at IS NOT NULL AND confirmed_at >= placed_at "
            + "UNION ALL "
            + "SELECT 'CONFIRMED_TO_DISPATCHED', COUNT(*), "
            + "       AVG(EXTRACT(EPOCH FROM (dispatched_at - confirmed_at))), "
            + "       percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (dispatched_at - confirmed_at))) "
            + "FROM scoped WHERE confirmed_at IS NOT NULL AND dispatched_at IS NOT NULL AND dispatched_at >= confirmed_at "
            + "UNION ALL "
            + "SELECT 'DISPATCHED_TO_DELIVERED', COUNT(*), "
            + "       AVG(EXTRACT(EPOCH FROM (delivered_at - dispatched_at))), "
            + "       percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (delivered_at - dispatched_at))) "
            + "FROM scoped WHERE dispatched_at IS NOT NULL AND delivered_at IS NOT NULL AND delivered_at >= dispatched_at "
            + "UNION ALL "
            + "SELECT 'DELIVERED_TO_RECEIVED', COUNT(*), "
            + "       AVG(EXTRACT(EPOCH FROM (received_at - delivered_at))), "
            + "       percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (received_at - delivered_at))) "
            + "FROM scoped WHERE delivered_at IS NOT NULL AND received_at IS NOT NULL AND received_at >= delivered_at "
            + "UNION ALL "
            + "SELECT 'PLACED_TO_DELIVERED', COUNT(*), "
            + "       AVG(EXTRACT(EPOCH FROM (delivered_at - placed_at))), "
            + "       percentile_cont(0.5) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (delivered_at - placed_at))) "
            + "FROM scoped WHERE placed_at IS NOT NULL AND delivered_at IS NOT NULL AND delivered_at >= placed_at";
}
