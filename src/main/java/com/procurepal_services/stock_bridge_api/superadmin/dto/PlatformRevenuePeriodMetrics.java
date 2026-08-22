package com.procurepal_services.stock_bridge_api.superadmin.dto;

import java.math.BigDecimal;

/**
 * Every cross-seller headline number for one window, so the revenue summary can return the
 * same record twice (this period, the one before it) and the client can diff them field by
 * field rather than the API inventing a bespoke "delta" shape per metric. The shape is
 * deliberately the same idea as {@code MarketplacePeriodMetrics}, which does this for one
 * seller.
 *
 * <h2>What this counts that the operator's own screen does not</h2>
 * Every seller's orders - ProcurePal's and every vendor's, together. That is the whole
 * point of this surface, and it is why it is a super admin one: ProcurePal's own analytics
 * narrowed to its own sales in M6, and this is where the platform-wide figure went. A
 * marketplace total on a tenant screen is one seller reading another's revenue.
 *
 * <h2>The two definitions everything below inherits</h2>
 * Identical to the per-seller modules', on purpose. <b>Revenue-bearing order</b>:
 * {@code status NOT IN (CANCELLED, PENDING_PAYMENT)}. <b>Order date</b>:
 * {@code COALESCE(placed_at, created_at)}. Windows are half-open, {@code [from, to)}. The
 * consequence worth stating: this total must equal the sum of what every seller sees on
 * their own screen for the same month, and if it ever does not, one of the three copies of
 * these definitions has drifted.
 *
 * @param grossRevenue total charged - goods plus delivery - on revenue-bearing orders
 *     dated in the window, across every seller. This is what the MARKETPLACE booked, not
 *     what the platform earns from it: commission is a later module and nothing here
 *     computes a take rate.
 * @param merchandiseRevenue the goods half of {@code grossRevenue} (sum of subtotal).
 * @param deliveryFeeRevenue the delivery half of {@code grossRevenue}.
 * @param collectedRevenue the subset of {@code grossRevenue} on orders already
 *     payment_status = PAID. Gross minus collected is what is still owed somewhere in the
 *     chain - mostly unsettled pay-on-delivery, which is ProcurePal-only today.
 * @param orderCount revenue-bearing orders dated in the window, across every seller.
 * @param averageOrderValue {@code grossRevenue / orderCount}, 2dp, zero when there are no
 *     orders. A platform-wide AOV, so a marketplace of one large seller and many small
 *     ones will sit near the large one's.
 * @param unitsSold sum of order_items.quantity across those orders. Units of measure
 *     differ per product, so this is a volume indicator, not a physical total.
 * @param sellingSellerCount distinct sellers that took at least one revenue-bearing order
 *     in the window. Deliberately measured from the ORDERS, not from the clients table, so
 *     it means "sellers who actually traded" rather than "accounts flagged as vendors".
 * @param buyingCompanyCount distinct buying companies with at least one revenue-bearing
 *     order in the window, anywhere on the marketplace. Note this does NOT sum the
 *     per-seller buyer counts: a company that bought from two sellers is one company here
 *     and one on each of their rows.
 * @param cancelledOrderCount orders dated in the window that ended CANCELLED. Excluded
 *     from every revenue figure above; reported separately because the trend matters.
 * @param cancelledOrderValue what those cancellations would have been worth.
 */
public record PlatformRevenuePeriodMetrics(
        BigDecimal grossRevenue,
        BigDecimal merchandiseRevenue,
        BigDecimal deliveryFeeRevenue,
        BigDecimal collectedRevenue,
        long orderCount,
        BigDecimal averageOrderValue,
        long unitsSold,
        long sellingSellerCount,
        long buyingCompanyCount,
        long cancelledOrderCount,
        BigDecimal cancelledOrderValue) {
}
