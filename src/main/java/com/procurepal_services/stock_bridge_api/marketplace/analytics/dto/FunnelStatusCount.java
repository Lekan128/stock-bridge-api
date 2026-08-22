package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import java.math.BigDecimal;

/**
 * Current-status census of the orders PROCUREPAL SOLD that are dated in the window.
 *
 * @param orderValue sum of total, INCLUDING the CANCELLED and PENDING_PAYMENT rows -
 *     which is the one place in this module those two are given a naira figure, because
 *     "we lost ₦3.2m to cancellations" is the point of showing them. Nothing here feeds
 *     the revenue metrics.
 */
public record FunnelStatusCount(OrderStatus status, long orderCount, BigDecimal orderValue) {
}
