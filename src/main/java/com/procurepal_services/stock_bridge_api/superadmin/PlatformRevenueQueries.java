package com.procurepal_services.stock_bridge_api.superadmin;

/**
 * Every SQL statement the cross-seller revenue module runs, in one place.
 *
 * <h2>This is the one analytics surface that is SUPPOSED to be unscoped</h2>
 * {@code MarketplaceAnalyticsQueries} and {@code VendorSalesAnalyticsQueries} both pin
 * every statement to one {@code seller_client_id}, and in both the pin is the only thing
 * standing between one seller and another's revenue. Here there is deliberately no pin,
 * because the question being asked is "what did the whole marketplace take, and which
 * vendors are growing" - and the only principal that can ask it is the super admin.
 *
 * <p>That inversion is the hazard to hold in mind when editing this file. A statement
 * copied out of here into a tenant-facing module would be the cross-vendor leak
 * VENDOR_RESEARCH.md Section C item 7 calls a commercial incident. The protection is
 * entirely positional: these constants are package-private, they are used by exactly one
 * service, and that service is reachable only from {@code SuperAdminAnalyticsController}
 * under {@code /api/superadmin/**}, which SecurityConfig gates on the super admin audience
 * authority. There is no tenant token - ProcurePal's included - that reaches them.
 *
 * <h2>No tenant filter to escape, and therefore no escape hatch</h2>
 * A {@code SuperAdminPrincipal} is deliberately not a {@code TenantPrincipal}, so
 * {@code TenantResolutionFilter} never enables the Hibernate tenant filter on a super
 * admin request and there is nothing here for {@code readAcrossTenants} to lift. That is
 * the same situation {@code ProductModerationService} is in and it is handled the same
 * way: cross-tenant reads are the normal case on this surface, so the obligation is to
 * scope EXPLICITLY where scoping is wanted (the optional seller filter below) rather than
 * to reach for a filter-lifting helper that would do nothing.
 *
 * <p>These are native SQL for a different reason from the tenant modules' - not to escape
 * a filter, but because the figures are aggregates over {@code orders} joined to
 * {@code clients} across every tenant, which JPQL over tenant-scoped entities has no
 * honest way to express.
 *
 * <h2>The definitions, copied verbatim rather than reinterpreted</h2>
 * {@link #REVENUE_BEARING} and {@link #ORDER_AT} are the same two strings
 * {@code MarketplaceAnalyticsQueries} and {@code VendorSalesAnalyticsQueries} use. The
 * super admin's total for a month must equal the sum of what each seller sees for that
 * month, or the first commission dispute starts with two numbers and no way to tell which
 * is right. If these ever drift, that guarantee is gone silently.
 *
 * <h2>Bind parameters only, filters included</h2>
 * Windows, granularity, and every optional filter value are bound. The optional filters
 * change the TEXT of the statement only by adding a fixed fragment from
 * {@link #SELLER_FILTER}, {@link #STATUS_FILTER} or {@link #PAYMENT_STATUS_FILTER} - the
 * caller's value never reaches the SQL text, only the {@code :parameter} it binds to.
 * Sorting is deliberately NOT expressed here; see {@code PlatformRevenueService} for why
 * the per-seller ordering is done in Java.
 */
final class PlatformRevenueQueries {

    private PlatformRevenueQueries() {
    }

    /** Money that exists. CANCELLED never happened; PENDING_PAYMENT was never paid for. */
    static final String REVENUE_BEARING = "o.status NOT IN ('CANCELLED', 'PENDING_PAYMENT')";

    /**
     * When an order counts as having happened: the moment it became real money, falling
     * back to creation for rows that never reached PLACED (abandoned checkouts). Used for
     * bucketing and for every window predicate, so the totals and the series can never
     * disagree about which period an order belongs to.
     */
    static final String ORDER_AT = "COALESCE(o.placed_at, o.created_at)";

    /** Half-open [from, to): a period and its predecessor share a boundary without double-counting. */
    private static final String IN_WINDOW = ORDER_AT + " >= :from AND " + ORDER_AT + " < :to";

    // ---------------------------------------------------------------------------------
    // Optional filters
    // ---------------------------------------------------------------------------------

    /**
     * Narrow to ONE seller. Optional, and the only place a caller-supplied client id
     * reaches these queries - which is safe here and nowhere else in the application,
     * because this surface has already established the caller is a super admin. Compare
     * {@code VendorSalesAnalyticsService}, where the seller id may only ever come from a
     * guard: there, a request-supplied id would be the whole vulnerability.
     */
    static final String SELLER_FILTER = " AND o.seller_client_id = :sellerId";

    /**
     * Narrow to one fulfilment status. Applied ON TOP of {@link #REVENUE_BEARING} rather
     * than instead of it, so the revenue columns keep one meaning: filtering to CANCELLED
     * correctly reports zero revenue and a non-zero cancelled value, rather than quietly
     * redefining "revenue" to include money that was never taken.
     */
    static final String STATUS_FILTER = " AND o.status = :status";

    /** Narrow to one payment status - PAID, ON_DELIVERY, and so on. */
    static final String PAYMENT_STATUS_FILTER = " AND o.payment_status = :paymentStatus";

    // ---------------------------------------------------------------------------------
    // Totals
    // ---------------------------------------------------------------------------------

    /**
     * Platform-wide headline figures for one window in a single pass, across every seller.
     *
     * <p>{@code sellingSellers} counts DISTINCT seller_client_id rather than reading the
     * clients table, so it means "sellers who actually took money in this window" - which
     * is the number an operator wants next to the revenue - and not "accounts we have
     * flagged as vendors", which would be flat and uninformative.
     *
     * Row: [gross, merchandise, deliveryFee, collected, orderCount, sellingSellers,
     * buyingCompanies, cancelledCount, cancelledValue].
     *
     * <p>Ends with a bare {@code WHERE} the caller appends filters to - see
     * {@code PlatformRevenueService.Filters}.
     */
    static final String TOTALS =
            "SELECT "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.subtotal) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.delivery_fee) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING
                    + "        AND o.payment_status = 'PAID'), 0), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(DISTINCT o.seller_client_id) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(DISTINCT o.client_id) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(*) FILTER (WHERE o.status = 'CANCELLED'), "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE o.status = 'CANCELLED'), 0) "
                    + "FROM orders o "
                    + "WHERE " + IN_WINDOW;

    /** Units are on the lines, not the order, so they need the join the aggregate above avoids. */
    static final String UNITS_SOLD =
            "SELECT COALESCE(SUM(oi.quantity), 0) "
                    + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                    + "WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW;

    // ---------------------------------------------------------------------------------
    // Revenue over time
    // ---------------------------------------------------------------------------------

    /**
     * Zero-filled bucket series across every seller. Same shape and the same reasoning as
     * the two per-seller modules': generate_series LEFT JOINed to orders rather than a
     * plain GROUP BY, because a quiet week must come back as a zero - a line chart that
     * skips empty buckets draws a rise that never happened.
     *
     * <p>The order predicates, the optional filters included, sit in the JOIN condition
     * rather than a WHERE. Moving any of them out would turn the outer join back into an
     * inner one and throw the zeros away, which is why {@code Filters} appends to the JOIN
     * here and to the WHERE everywhere else.
     *
     * <p>The upper bound is nudged back a microsecond so a {@code to} landing exactly on a
     * boundary does not emit a trailing empty bucket for a period the window excludes.
     *
     * Row: [period(text), revenue, orderCount, sellingSellers, unitsSold].
     */
    static final String REVENUE_OVER_TIME_HEAD =
            "SELECT to_char(b.bucket, 'YYYY-MM-DD'), "
                    + "  COALESCE(SUM(o.total), 0), "
                    + "  COUNT(o.id), "
                    + "  COUNT(DISTINCT o.seller_client_id), "
                    + "  COALESCE(SUM(u.units), 0) "
                    + "FROM generate_series("
                    + "    date_trunc(CAST(:granularity AS text), CAST(:from AS timestamptz)), "
                    + "    date_trunc(CAST(:granularity AS text), CAST(:to AS timestamptz) - INTERVAL '1 microsecond'), "
                    + "    CAST(:step AS interval)) AS b(bucket) "
                    + "LEFT JOIN orders o "
                    + "  ON " + REVENUE_BEARING + " AND " + IN_WINDOW
                    + "  AND date_trunc(CAST(:granularity AS text), " + ORDER_AT + ") = b.bucket";

    /** Appended after the caller's filter fragment - see {@link #REVENUE_OVER_TIME_HEAD}. */
    static final String REVENUE_OVER_TIME_TAIL =
            " LEFT JOIN (SELECT order_id, SUM(quantity) AS units FROM order_items GROUP BY order_id) u "
                    + "  ON u.order_id = o.id "
                    + "GROUP BY b.bucket ORDER BY b.bucket";

    // ---------------------------------------------------------------------------------
    // Per-seller breakdown
    // ---------------------------------------------------------------------------------

    /**
     * One row per seller that took an order in the window, with the account details the
     * operator needs to act on it.
     *
     * <p>Driven by the ORDERS present and joined out to {@code clients}, not the other way
     * round. A seller whose account has since been deactivated still sold what it sold, and
     * an operator investigating a revenue drop must be able to see the row go to zero
     * rather than have it vanish from the table. That also means this deliberately does NOT
     * go through {@code SellerDirectory}, whose whole job is "who may sell TODAY" - a
     * different question from "who sold in March".
     *
     * <p>The join is an INNER one on a NOT NULL foreign key with ON DELETE RESTRICT, so it
     * cannot drop a row: an order's seller row is guaranteed to exist.
     *
     * <p>No ORDER BY and no LIMIT. Both are applied in Java - see
     * {@code PlatformRevenueService.sorted} for the reasoning.
     *
     * Row: [sellerId, name, slug, clientType, isPlatformOwner, isActive, revenue,
     * merchandise, orderCount, buyingCompanies].
     */
    static final String BY_SELLER =
            "SELECT o.seller_client_id, c.name, c.slug, c.client_type, c.is_platform_owner, c.is_active, "
                    + "  COALESCE(SUM(o.total) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COALESCE(SUM(o.subtotal) FILTER (WHERE " + REVENUE_BEARING + "), 0), "
                    + "  COUNT(*) FILTER (WHERE " + REVENUE_BEARING + "), "
                    + "  COUNT(DISTINCT o.client_id) FILTER (WHERE " + REVENUE_BEARING + ") "
                    + "FROM orders o "
                    + "JOIN clients c ON c.id = o.seller_client_id "
                    + "WHERE " + IN_WINDOW;

    /** Closes {@link #BY_SELLER} after the caller's filter fragment. */
    static final String BY_SELLER_GROUP_BY =
            " GROUP BY o.seller_client_id, c.name, c.slug, c.client_type, c.is_platform_owner, c.is_active";

    /**
     * Units per seller, kept as a second statement for the same reason the totals do:
     * folding the order_items join into {@link #BY_SELLER} would multiply the order-level
     * FILTER aggregates by the number of lines on each order. The two are merged by seller
     * id in Java.
     *
     * Row: [sellerId, units].
     */
    static final String UNITS_BY_SELLER =
            "SELECT o.seller_client_id, COALESCE(SUM(oi.quantity), 0) "
                    + "FROM order_items oi JOIN orders o ON o.id = oi.order_id "
                    + "WHERE " + REVENUE_BEARING + " AND " + IN_WINDOW;

    /** Closes {@link #UNITS_BY_SELLER} after the caller's filter fragment. */
    static final String UNITS_BY_SELLER_GROUP_BY = " GROUP BY o.seller_client_id";
}
