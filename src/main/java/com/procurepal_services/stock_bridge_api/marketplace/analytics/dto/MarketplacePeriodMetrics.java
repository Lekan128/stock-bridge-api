package com.procurepal_services.stock_bridge_api.marketplace.analytics.dto;

import java.math.BigDecimal;

/**
 * Every headline number for one window, so the summary endpoint can return the same
 * record twice (this period, the one before it) and the UI can diff them field by
 * field rather than the API inventing a bespoke "delta" shape per metric.
 *
 * <h2>The two definitions everything below inherits</h2>
 * <b>Revenue-bearing order</b>: {@code status NOT IN (CANCELLED, PENDING_PAYMENT)}.
 * A cancelled order is money that never existed, and PENDING_PAYMENT is a Monnify
 * checkout that was created and never paid - counting either would let an abandoned
 * cart inflate the marketplace's revenue.
 * <p>
 * <b>Order date</b>: {@code COALESCE(placed_at, created_at)} - the moment the order
 * became real money, falling back to creation for rows that never reached PLACED
 * (abandoned checkouts, which only the cancelled/abandoned counters look at).
 * Windows are half-open, {@code [from, to)}, so a period and the one before it share
 * a boundary instant without double-counting the order that landed on it.
 *
 * @param grossRevenue total charged - goods plus delivery - on revenue-bearing orders
 *     dated in the window. This is the top line the marketplace booked, not the cash
 *     it has collected.
 * @param merchandiseRevenue the goods half of {@code grossRevenue} (sum of subtotal).
 * @param deliveryFeeRevenue the delivery half of {@code grossRevenue}.
 * @param collectedRevenue the subset of {@code grossRevenue} on orders whose
 *     payment_status is already PAID. gross minus collected is what is still owed:
 *     unsettled pay-on-delivery, mostly.
 * @param orderCount revenue-bearing orders dated in the window.
 * @param averageOrderValue {@code grossRevenue / orderCount}, 2dp, zero when there
 *     are no orders (rather than null, so the UI never has to special-case it).
 * @param unitsSold sum of order_items.quantity across those orders. Units of measure
 *     differ per product, so this is a volume indicator, not a physical total.
 * @param activeBuyingCompanies distinct buyer tenants with at least one revenue-bearing
 *     order dated in the window.
 * @param newBuyingCompanies companies whose FIRST EVER revenue-bearing order is dated
 *     in the window. All-time first, not first-in-window - so a company that bought
 *     last year and again today is not "new".
 * @param repeatOrderRate share (0..1, 4dp) of the window's revenue-bearing orders that
 *     were placed by a company which already had an earlier revenue-bearing order at
 *     any point before that order. Zero when the window has no orders.
 * @param outstandingOrderCount revenue-bearing orders dated in the window still in
 *     PLACED / CONFIRMED / PROCESSING / OUT_FOR_DELIVERY - i.e. owed to a customer and
 *     not yet marked delivered. DELIVERED and RECEIVED are done; RECEIVED is the buyer
 *     signing for it.
 * @param outstandingOrderValue sum of total over those orders - the delivery backlog in naira.
 * @param payOnDeliveryOrderCount revenue-bearing orders dated in the window with
 *     payment_method = PAY_ON_DELIVERY that are still payment_status = ON_DELIVERY,
 *     i.e. goods promised on credit and the cash not yet reconciled.
 * @param payOnDeliveryExposure sum of total over those orders - the money at risk.
 * @param cancelledOrderCount orders dated in the window that ended CANCELLED. Excluded
 *     from every revenue figure above; reported separately because the trend matters.
 * @param cancelledOrderValue what those cancellations would have been worth.
 * @param abandonedCheckoutCount orders still sitting in PENDING_PAYMENT and created in
 *     the window - Monnify checkouts nobody completed. Also excluded from revenue.
 */
public record MarketplacePeriodMetrics(
        BigDecimal grossRevenue,
        BigDecimal merchandiseRevenue,
        BigDecimal deliveryFeeRevenue,
        BigDecimal collectedRevenue,
        long orderCount,
        BigDecimal averageOrderValue,
        long unitsSold,
        long activeBuyingCompanies,
        long newBuyingCompanies,
        BigDecimal repeatOrderRate,
        long outstandingOrderCount,
        BigDecimal outstandingOrderValue,
        long payOnDeliveryOrderCount,
        BigDecimal payOnDeliveryExposure,
        long cancelledOrderCount,
        BigDecimal cancelledOrderValue,
        long abandonedCheckoutCount) {
}
