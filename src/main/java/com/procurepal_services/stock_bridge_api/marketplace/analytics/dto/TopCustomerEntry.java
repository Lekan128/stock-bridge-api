package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of ProcurePal's top-customers ranking. Mixes in-window figures with all-time
 * ones on purpose: "spent ₦2m this quarter" and "has spent ₦40m since 2024" answer
 * different questions, and an operator deciding who to call needs both on the same line.
 *
 * <p>Every figure is spend WITH PROCUREPAL. The all-time columns are unbounded in TIME,
 * not in seller: a company that buys ten times as much from a third-party vendor is not
 * ProcurePal's best customer, and showing it as one would both mis-rank the list and
 * disclose that vendor's book on the operator's screen.
 *
 * @param clientId the BUYER tenant - orders.client_id. Links straight to the existing
 *     customers screen.
 * @param revenue gross revenue from this company's revenue-bearing orders dated in the
 *     window (goods + delivery).
 * @param orderCount that company's revenue-bearing orders dated in the window.
 * @param unitsPurchased order_items.quantity summed over those orders.
 * @param lifetimeSpend gross revenue over ALL of that company's revenue-bearing orders
 *     FROM PROCUREPAL, ignoring the window entirely. Note this differs from the customers list's
 *     lifetimeSpend, which counts only payment_status = PAID; this one counts everything
 *     booked, so a company on pay-on-delivery terms is not shown as worthless.
 * @param lifetimeOrderCount all-time count on the same basis.
 * @param firstOrderAt / lastOrderAt all-time first and last revenue-bearing order dates.
 *     lastOrderAt is the churn signal - a big lifetime number with an old last order is
 *     the row worth acting on.
 */
public record TopCustomerEntry(
        UUID clientId,
        String name,
        String slug,
        BigDecimal revenue,
        long orderCount,
        long unitsPurchased,
        BigDecimal lifetimeSpend,
        long lifetimeOrderCount,
        OffsetDateTime firstOrderAt,
        OffsetDateTime lastOrderAt) {
}
