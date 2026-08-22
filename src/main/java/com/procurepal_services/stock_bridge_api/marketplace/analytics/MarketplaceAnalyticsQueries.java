package com.procurepal_services.stock_bridge_api.marketplace.analytics;

/**
 * Every SQL statement the module runs, in one place.
 *
 * <h2>The seller predicate is not optional and is not a filter</h2>
 * Read {@link #OWN_SALES} first. Every statement below splices it in, and it is what makes
 * this module ProcurePal's OWN sales rather than the marketplace's takings.
 *
 * <p>It was not always here. Until M6 these queries were deliberately unscoped, because
 * ProcurePal was the only seller and "the marketplace's revenue" and "ProcurePal's revenue"
 * named the same money. Opening selling to third-party vendors broke that identity without
 * changing a line of SQL: the same statements silently began adding other companies'
 * takings to the operator's own revenue card. So the predicate is a spliced constant rather
 * than something each query writes out, {@code :sellerId} is bound on every call from
 * {@link MarketplaceAnalyticsService}, and there is no constant here that lacks it. A new
 * statement that does not concatenate {@link #OWN_SALES} is a bug.
 *
 * <p>This is now the same arrangement {@code VendorSalesAnalyticsQueries} uses, and
 * deliberately so - the two modules had drifted into opposite postures on the one question
 * that matters. What still differs is DEPTH, not scope: see
 * {@link MarketplaceAnalyticsService} for the per-metric ruling.
 *
 * <h2>Why native SQL, given the queries are now scoped</h2>
 * {@code orders.client_id} is the BUYER, and these aggregates span every company that
 * bought FROM ProcurePal. Under ProcurePal's own Hibernate tenant filter a JPQL version
 * would match only orders ProcurePal itself placed - which is none - so the screen would
 * report a seller that had never sold anything, with no error to explain it. Native SQL is
 * not subject to that filter, the same reasoning (and the same shape) as
 * {@code order.CatalogStockService}. Authorization is carried by
 * {@code PlatformOwnerGuard.requirePlatformOwner()}, called before every one of these runs,
 * and row scoping is carried by {@link #OWN_SALES}. Nothing here touches
 * {@code Session.disableFilter}.
 *
 * <h2>The two definitions every statement shares</h2>
 * {@link #REVENUE_BEARING} - status NOT IN (CANCELLED, PENDING_PAYMENT) - and
 * {@link #ORDER_AT} - COALESCE(placed_at, created_at). They are string constants spliced
 * into these queries rather than repeated by hand precisely so that "what counts as
 * revenue" cannot drift between the summary card and the chart under it. They are also
 * identical to {@code VendorSalesAnalyticsQueries}' copies and to the super admin's
 * {@code PlatformRevenueQueries}: three screens quoting the same month have to agree about
 * what a sale is, or the first commission dispute starts with three numbers and no way to
 * tell which is right.
 *
 * <h2>Bind parameters only</h2>
 * The seller id, windows and limits are bound. The only values that reach the SQL text as
 * text are {@code date_trunc}/{@code generate_series} arguments taken from
 * {@link AnalyticsGranularity}, and even those are bound as parameters - a closed enum
 * means no caller string can reach a function name.
 */
final class MarketplaceAnalyticsQueries {

    private MarketplaceAnalyticsQueries() {
    }

    /**
     * The one predicate that makes this module ProcurePal's own view rather than every
     * seller's.
     *
     * <p>{@code orders.seller_client_id} (V11, NOT NULL) names who SOLD; {@code client_id}
     * names who bought. Every historical row was backfilled to the platform owner and every
     * new one is stamped at checkout by the splitter, so this is total: there is no order
     * this predicate cannot classify, and no need for a fallback that would have to guess.
     *
     * <p>Because of that backfill, narrowing these queries does not rewrite history.
     * Every order placed before vendors existed was ProcurePal's, so the operator's
     * pre-M6 numbers are identical either side of this change - only new vendor rows
     * are excluded, which is the entire point.
     */
    private static final String OWN_SALES = "o.seller_client_id = :sellerId";

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
                    // Scoped to ProcurePal's own sales like everything else, and note that
                    // pay-on-delivery is disabled for baskets containing vendor goods
                    // anyway (CheckoutService.payOnDeliveryReasons), so this column would
                    // have been ProcurePal's even unscoped.
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_method = 'PAY_ON_DELIVERY' AND o.payment_status = 'ON_DELIVERY'), 0), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'CANCELLED'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE o.status = 'CANCELLED'), 0), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'PENDING_PAYMENT') "
                    + "FROM orders o "
                    + "WHERE " + OWN_SALES + " AND " + IN_WINDOW;

    /** Units are on the lines, not the order, so they need the join the aggregate above deliberately avoids. */
    static final String SUMMARY_UNITS_SOLD =
            "SELECT COALESCE(SUM(oi.quantity), 0) "
                    + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                    + "WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW;

    /**
     * Companies whose FIRST EVER revenue-bearing order FROM PROCUREPAL lands in the window.
     * The MIN is taken over all of that company's ProcurePal history and then filtered, not
     * taken within the window - otherwise every returning customer would look new every
     * month.
     *
     * <p>The seller pin sits inside the grouped subquery, which is what makes "new" mean
     * "new to ProcurePal". Leaving it off the inner query and pinning only the outer one
     * would produce a subtler wrong answer than an unscoped query does: a company that had
     * been buying from a vendor for a year would never count as new to ProcurePal on its
     * first ProcurePal order. This is the metric where the two readings genuinely differ,
     * and "new to me" is the one a seller's own page has to mean.
     */
    static final String SUMMARY_NEW_COMPANIES =
            "SELECT COUNT(*) FROM ("
                    + "  SELECT o.client_id, MIN(" + ORDER_AT + ") AS first_at "
                    + "  FROM orders o WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " GROUP BY o.client_id"
                    + ") f WHERE f.first_at >= :from AND f.first_at < :to";

    /**
     * Repeat-ORDER rate, not repeat-customer rate: of the window's ProcurePal orders, how
     * many came from a company that had already bought FROM PROCUREPAL before. "Before"
     * means strictly earlier than that order, at any time in history - so a company's first
     * ever ProcurePal order is never counted as repeat even if their second lands the same
     * afternoon.
     *
     * <p>The correlated lookback carries the seller pin too, for the same reason
     * {@link #SUMMARY_NEW_COMPANIES} does: loyalty to a vendor is not loyalty to ProcurePal,
     * and counting it as such would flatter this number with somebody else's customers.
     *
     * Row: [ordersInWindow, ordersFromReturningBuyers].
     */
    static final String SUMMARY_REPEAT_ORDERS =
            "SELECT COUNT(*), COUNT(*) FILTER (WHERE x.prior_orders > 0) FROM ("
                    + "  SELECT ("
                    + "    SELECT COUNT(*) FROM orders p "
                    + "    WHERE p.client_id = o.client_id "
                    + "      AND p.seller_client_id = :sellerId "
                    + "      AND p.status NOT IN ('CANCELLED', 'PENDING_PAYMENT') "
                    + "      AND COALESCE(p.placed_at, p.created_at) < " + ORDER_AT
                    + "  ) AS prior_orders "
                    + "  FROM orders o WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW
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
     * <p>The order predicates - the seller pin included - sit in the JOIN condition, not in
     * a WHERE. Moving any of them out would turn the outer join back into an inner one and
     * throw the zeros away; moving the SELLER one out would do that AND make the join match
     * every seller's orders first, so it is the predicate to check hardest when editing
     * this statement.
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
                    + "  ON " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + "  AND date_trunc(CAST(:granularity AS text), " + ORDER_AT + ") = b.bucket "
                    + "LEFT JOIN (SELECT order_id, SUM(quantity) AS units FROM order_items GROUP BY order_id) u "
                    + "  ON u.order_id = o.id "
                    + "GROUP BY b.bucket ORDER BY b.bucket";

    // ---------------------------------------------------------------------------------
    // Top customers
    // ---------------------------------------------------------------------------------

    /**
     * In-window spend WITH PROCUREPAL and all-time standing WITH PROCUREPAL on one row. The
     * lifetime CTE is unbounded in time by design - it is what makes "big customer, hasn't
     * ordered since March" visible, which the in-window columns alone can never show - but
     * it is not unbounded in SELLER: "lifetime spend" on ProcurePal's own page means what
     * they have spent with ProcurePal, not what they have spent on the platform. A company
     * that buys ten times as much from a vendor is not ProcurePal's best customer.
     *
     * <p>Both CTEs therefore carry the pin. This is also what stops the ranking becoming a
     * cross-seller disclosure: without it, ProcurePal's screen would rank companies partly
     * on money that went to its competitors, and the vendor's own customer list is
     * precisely what {@code MarketplaceOrderAdminService} is careful never to leak.
     *
     * {@code is_platform_owner = FALSE} is belt-and-braces: ProcurePal cannot be its own
     * buyer today, but a sales analytic that could ever count the seller's own name as its
     * best customer is not one anybody would trust again.
     *
     * Row: [clientId, name, slug, revenue, orderCount, units, lifetimeSpend,
     * lifetimeOrders, firstOrderAt, lastOrderAt].
     */
    private static final String TOP_CUSTOMERS_BODY =
            "WITH scoped AS ("
                    + "  SELECT o.id, o.client_id, o.total FROM orders o "
                    + "  WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW
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
                    + "  FROM orders o WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " GROUP BY o.client_id"
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
     * That join is scoped by the seller predicate on {@code orders}, not by one on
     * {@code products}: an order line can only reference a product ProcurePal sold, so
     * pinning both would be redundant - but note the consequence, which is that removing
     * the seller pin from the WHERE clause would silently start listing vendors' catalogue
     * rows on ProcurePal's own best-sellers chart.
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
                    + "WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW + " "
                    + "GROUP BY p.id, p.name, p.sku, pc.name ";

    static final String TOP_PRODUCTS_BY_REVENUE = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.line_total) DESC, SUM(oi.quantity) DESC, p.name ASC LIMIT :limit";

    static final String TOP_PRODUCTS_BY_QUANTITY = TOP_PRODUCTS_BODY
            + "ORDER BY SUM(oi.quantity) DESC, SUM(oi.line_total) DESC, p.name ASC LIMIT :limit";

    // ---------------------------------------------------------------------------------
    // Category mix
    // ---------------------------------------------------------------------------------

    /**
     * Same line-level basis as top products, grouped one level up, and scoped to
     * ProcurePal's own sales for the same reason: the response carries a TOTAL REVENUE
     * figure alongside the shares, and an unscoped total is exactly the number the operator
     * objected to seeing on its own page. This is a revenue split, not a demand signal -
     * marketplace-wide category demand is a super admin question, and lives there.
     *
     * <p>Products with no category collapse into a single null-id row the service labels
     * explicitly, rather than being dropped - hiding them would make the shares add up to
     * less than the revenue actually taken.
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
                    + "WHERE " + OWN_SALES + " AND " + REVENUE_BEARING + " AND " + IN_WINDOW + " "
                    + "GROUP BY pc.id, pc.name "
                    + "ORDER BY 3 DESC, 2 ASC";

    // ---------------------------------------------------------------------------------
    // Fulfilment funnel
    // ---------------------------------------------------------------------------------

    /**
     * The funnel's population and its milestone timestamps. Unlike everywhere else in this
     * class the window is NOT restricted to revenue-bearing orders: a funnel that hides the
     * orders that dropped out is not a funnel. It IS restricted to ProcurePal's own sales,
     * because this is the report on ProcurePal's own fulfilment queue - and that queue has
     * been seller-scoped since V11 (see {@code MarketplaceOrderAdminService}: "ProcurePal is
     * not privileged HERE"). A funnel measuring a wider set of orders than the queue it
     * describes would put two numbers for "orders awaiting dispatch" on two screens of the
     * same app.
     *
     * <p>The stronger argument is that the numbers would not be actionable: a vendor's
     * dispatch time is not something ProcurePal's operations can fix, and averaging it into
     * ProcurePal's own hop durations hides the thing the chart exists to show.
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
                    + "  FROM orders o WHERE " + OWN_SALES + " AND " + IN_WINDOW
                    + ") ";

    /** Row: [status(text), orderCount, orderValue]. Statuses with no orders are absent; the service zero-fills. */
    static final String FUNNEL_STATUS_COUNTS =
            "SELECT o.status, COUNT(*), COALESCE(SUM(o.total), 0) "
                    + "FROM orders o WHERE " + OWN_SALES + " AND " + IN_WINDOW + " GROUP BY o.status";

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
