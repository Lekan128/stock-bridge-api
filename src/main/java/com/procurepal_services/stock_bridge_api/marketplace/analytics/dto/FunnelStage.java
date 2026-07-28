package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;

/**
 * One rung of the ever-reached funnel.
 *
 * @param stage the milestone: PLACED, CONFIRMED, DISPATCHED (= OUT_FOR_DELIVERY),
 *     DELIVERED, RECEIVED. PROCESSING is deliberately not a rung - it is an internal
 *     warehouse state an order can be advanced straight past, so counting it would show
 *     a drop-off that never happened.
 * @param orderCount orders dated in the window that reached this milestone at any time,
 *     even if they have since moved on or been cancelled afterwards.
 * @param conversionRate {@code orderCount / (orders that reached PLACED)}, 0..1 to 4dp.
 *     Based against PLACED rather than against every order, because PENDING_PAYMENT rows
 *     never entered fulfilment at all and would drag every rung down for a reason that
 *     belongs to checkout, not to operations. Zero when nothing reached PLACED.
 */
public record FunnelStage(String stage, long orderCount, BigDecimal conversionRate) {
}
