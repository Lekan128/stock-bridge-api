package com.procurepal_services.stock_bridge_api.superadmin;

/**
 * How to order the per-seller revenue breakdown.
 *
 * <p>An enum rather than a free {@code sort=} string for the reason
 * {@code AnalyticsGranularity} gives: a closed type means an unknown value fails to BIND
 * and becomes a 400 before it reaches any code, rather than needing a validation call
 * somebody has to remember to make. Unlike that enum these values never reach SQL at all -
 * the ordering is applied in Java - but the same argument holds for keeping the contract
 * closed, and it means adding a column here cannot accidentally add a SQL injection point
 * later.
 *
 * <p>{@link #GROWTH} sorts on the absolute naira change rather than the percentage. A
 * percentage sort would put a vendor that went from ₦2,000 to ₦6,000 above one that went
 * from ₦40m to ₦60m, and the operator asking "which vendors are growing" means the second.
 * The percentage is on every row for whoever wants it.
 */
public enum SellerRevenueSort {
    REVENUE,
    ORDERS,
    UNITS,
    AVERAGE_ORDER_VALUE,
    GROWTH,
    NAME
}
