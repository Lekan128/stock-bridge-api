package com.procurepal_services.stock_bridge_api.vendor.analytics.dto;

import java.math.BigDecimal;

/**
 * Every headline number for one window of ONE SELLER's sales, so the summary
 * endpoint can return the same record twice (this period, the one before it) and
 * the UI can diff them field by field.
 *
 * <h2>Same definitions as the marketplace view, on purpose</h2>
 * <b>Revenue-bearing order</b>: {@code status NOT IN (CANCELLED,
 * PENDING_PAYMENT)}. <b>Order date</b>: {@code COALESCE(placed_at, created_at)}.
 * Windows are half-open, {@code [from, to)}. These are copied from
 * {@code MarketplacePeriodMetrics} deliberately rather than reinterpreted: a
 * vendor and the operator looking at the same month must agree about what a sale
 * is, or the first commission conversation starts with two different numbers.
 * The one difference is the population - every row here carries
 * {@code seller_client_id = <the caller>} - and that difference is the whole
 * module.
 *
 * <h2>What is deliberately absent, and why the omissions are the design</h2>
 * There is no counterpart to the marketplace record's
 * {@code activeBuyingCompanies}, {@code newBuyingCompanies} or
 * {@code repeatOrderRate}, and there is no top-customers endpoint anywhere in
 * this module. VENDOR_RESEARCH.md Section C item 7 is the reason: "see
 * marketplace analytics", read literally, lets a third party see competitors'
 * sales inside a multi-tenant platform, and the settled scope is a vendor's OWN
 * sales only. Buyer-identity aggregates sit on the wrong side of that line even
 * when every underlying order is the vendor's - "your three biggest customers
 * and what they spend" is a customer list, and the vendor already learns each
 * buyer's name from the order they are shipping, which is different from being
 * handed a ranked book of them. If it is ever wanted it needs its own decision,
 * not an extra field here.
 *
 * @param grossRevenue total charged - goods plus delivery - on this seller's
 *     revenue-bearing orders dated in the window. The top line booked, not cash
 *     collected. STILL GROSS OF COMMISSION, deliberately, now that the ledger this
 *     javadoc used to say was unbuilt exists (M7): this module reports SALES and the
 *     statement reports the ACCOUNT, and conflating them would leave a vendor with no
 *     figure for what they actually sold. The two will normally differ for the same
 *     month, for three reasons a screen showing both must be ready to explain -
 *     timing (a sale is dated when the order was PLACED here, and when delivery was
 *     CONFIRMED in the ledger), the delivery fee (included here, never in the ledger,
 *     because it is the platform's logistics charge rather than the vendor's goods),
 *     and commission itself (deducted only on the statement). See
 *     {@code settlement.dto.VendorStatementResponse}, which carries the same
 *     explanation in its payload so the frontend does not have to hard-code it.
 * @param merchandiseRevenue the goods half of {@code grossRevenue} (sum of subtotal).
 * @param deliveryFeeRevenue the delivery half of {@code grossRevenue}.
 * @param collectedRevenue the subset of {@code grossRevenue} on orders already
 *     payment_status PAID. Gross minus collected is what is still owed - unsettled
 *     pay-on-delivery, mostly.
 * @param orderCount this seller's revenue-bearing orders dated in the window.
 * @param averageOrderValue {@code grossRevenue / orderCount}, 2dp, zero when there
 *     are no orders rather than null.
 * @param unitsSold sum of order_items.quantity across those orders. Units of measure
 *     differ per product, so this is a volume indicator, not a physical total.
 * @param outstandingOrderCount orders still in PLACED / CONFIRMED / PROCESSING /
 *     OUT_FOR_DELIVERY - owed to a buyer and not yet delivered. This is the number a
 *     vendor should act on today.
 * @param outstandingOrderValue sum of total over those orders.
 * @param payOnDeliveryOrderCount revenue-bearing orders with payment_method
 *     PAY_ON_DELIVERY still at payment_status ON_DELIVERY - goods gone, cash not
 *     reconciled. For a third-party vendor this is money the PLATFORM is holding on
 *     their behalf (Section C item 5), which is exactly why they get to see it.
 * @param payOnDeliveryExposure sum of total over those orders.
 * @param cancelledOrderCount orders dated in the window that ended CANCELLED.
 *     Excluded from every revenue figure above; reported separately because the
 *     trend is the thing a seller can do something about.
 * @param cancelledOrderValue what those cancellations would have been worth.
 */
public record VendorSalesPeriodMetrics(
        BigDecimal grossRevenue,
        BigDecimal merchandiseRevenue,
        BigDecimal deliveryFeeRevenue,
        BigDecimal collectedRevenue,
        long orderCount,
        BigDecimal averageOrderValue,
        long unitsSold,
        long outstandingOrderCount,
        BigDecimal outstandingOrderValue,
        long payOnDeliveryOrderCount,
        BigDecimal payOnDeliveryExposure,
        long cancelledOrderCount,
        BigDecimal cancelledOrderValue) {
}
