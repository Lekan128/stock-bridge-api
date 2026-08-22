package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.util.List;

/**
 * Where orders are, how many got through, and how long each hop took.
 *
 * Three views of the same population - every order PROCUREPAL SOLD that is dated in the
 * window, including cancelled and never-paid ones, because a funnel that hides its
 * drop-outs is not a funnel.
 *
 * <p>Seller-scoped since M6, and it has to be: this is the report on ProcurePal's own
 * fulfilment queue, which has been seller-scoped since V11
 * ({@code MarketplaceOrderAdminService}). A funnel counting more orders than the queue it
 * describes would put two different numbers for "awaiting dispatch" on two screens of the
 * same app - and a third party's dispatch time is not something ProcurePal's operations
 * can act on, so averaging it in hides the bottleneck this chart exists to find.
 *
 *
 * <ul>
 *   <li>{@code statusCounts} - where those orders sit RIGHT NOW. One row per
 *       OrderStatus, zero-filled, so the chart's categories never move between refreshes.
 *       These sum to the window's total order count.</li>
 *   <li>{@code stages} - how many EVER reached each milestone, from the order_status_events
 *       audit trail. Monotonically non-increasing, which is what makes it a funnel: an
 *       order that is now RECEIVED counts in every stage it passed through.</li>
 *   <li>{@code transitions} - how long the hops took, which is the operational number.
 *       A tall CONFIRMED bar plus a 40-hour confirmed→dispatched median is ProcurePal's
 *       warehouse being the bottleneck, and neither half says that on its own.</li>
 * </ul>
 */
public record FulfilmentFunnelResponse(
        long totalOrders,
        List<FunnelStatusCount> statusCounts,
        List<FunnelStage> stages,
        List<FunnelTransition> transitions) {
}
