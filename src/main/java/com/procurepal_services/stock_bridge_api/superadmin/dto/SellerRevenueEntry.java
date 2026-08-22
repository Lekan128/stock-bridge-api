package com.procurepal_services.stock_bridge_api.superadmin.dto;

import com.procurepal_services.stock_bridge_api.entity.ClientType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One seller's row in the cross-seller breakdown: what they took this period, what they
 * took last period, and therefore whether they are growing.
 *
 * <h2>Who appears</h2>
 * Every seller with an order in EITHER window, not every seller account. Two consequences,
 * both deliberate:
 * <ul>
 *   <li>A vendor that traded last month and not this one still has a row, with
 *       {@code revenue} zero and a negative growth. That is the row an operator most needs
 *       to see, and a table driven off "sellers with sales this period" would delete it.</li>
 *   <li>A seller whose ACCOUNT has since been deactivated still has a row for the periods
 *       it traded in. {@code active} is carried so the operator can tell the difference
 *       between "stopped selling" and "we switched them off".</li>
 * </ul>
 *
 * <p>{@code platformOwner} distinguishes ProcurePal's own row from the third-party
 * vendors'. It is the field that answers the question this whole surface exists for - how
 * much of the marketplace's revenue is the operator's own and how much is other people's -
 * and it is why ProcurePal is included here rather than netted off.
 *
 * @param revenue gross revenue (goods + delivery) on this seller's revenue-bearing orders
 *     in the window. This is what the SELLER booked, not what the platform earns from it;
 *     commission is a later module and nothing here nets it off.
 * @param merchandiseRevenue the goods half of {@code revenue}.
 * @param orderCount revenue-bearing orders in the window.
 * @param averageOrderValue {@code revenue / orderCount}, 2dp, zero when there are no
 *     orders rather than null - so a client never has to distinguish "no orders" from
 *     "field missing", which matters because non-null JSON inclusion would drop a null.
 * @param unitsSold order_items.quantity over those orders.
 * @param buyingCompanyCount distinct companies that bought from this seller in the window.
 * @param revenueShare this seller's share (0..1, 4dp) of the window's total revenue across
 *     all sellers in the response. Zero when the marketplace took nothing.
 * @param previousRevenue the same gross revenue over the preceding window of equal length.
 * @param revenueGrowth {@code revenue - previousRevenue}, in naira. Signed.
 * @param revenueGrowthRate {@code revenueGrowth / previousRevenue} as a 0..1-style ratio at
 *     4dp, signed, and NULL - not zero - when the seller took nothing in the previous
 *     window. A percentage change from zero is undefined, and reporting it as 0% or as some
 *     large number would both be lies about a seller's first trading month. The absolute
 *     {@code revenueGrowth} is always present, so a client can render "new" from the null.
 */
public record SellerRevenueEntry(
        UUID sellerClientId,
        String name,
        String slug,
        ClientType clientType,
        boolean platformOwner,
        boolean active,
        BigDecimal revenue,
        BigDecimal merchandiseRevenue,
        long orderCount,
        BigDecimal averageOrderValue,
        long unitsSold,
        long buyingCompanyCount,
        BigDecimal revenueShare,
        BigDecimal previousRevenue,
        BigDecimal revenueGrowth,
        BigDecimal revenueGrowthRate) {
}
