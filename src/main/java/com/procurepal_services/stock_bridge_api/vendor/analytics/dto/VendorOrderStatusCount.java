package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import java.math.BigDecimal;

/**
 * How many of a seller's orders sit at each status in the window, and what they
 * are worth.
 *
 * <p>Zero-filled across every {@link OrderStatus} by the service, so the chart's
 * categories are stable between refreshes: a bar that vanishes when its count hits
 * zero reads as a data problem rather than as good news.
 *
 * <p>Unlike everything else in this module the population is NOT restricted to
 * revenue-bearing orders. A status breakdown that hid the cancelled and
 * never-paid-for orders would be hiding the two statuses a seller most needs to
 * see - the same exception {@code FulfilmentFunnelResponse} makes, for the same
 * reason.
 *
 * @param orderValue sum of total at this status. Present so "6 orders" can be read
 *     next to what they are worth; a seller with six ₦2,000 orders and one with six
 *     ₦2m orders have different mornings.
 */
public record VendorOrderStatusCount(OrderStatus status, long orderCount, BigDecimal orderValue) {
}
