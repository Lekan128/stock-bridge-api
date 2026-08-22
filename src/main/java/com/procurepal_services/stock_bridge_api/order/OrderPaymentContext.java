package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The read-only projection of a PAYABLE CHECKOUT that the payment module works from.
 *
 * <h2>It projects a checkout, not an order, and that is the whole point</h2>
 * A basket holding several sellers' goods splits into one order per seller (V12), but
 * the buyer presses pay once and Monnify takes one amount. The payable unit is
 * therefore the checkout group, not the order, and this record is what tells the
 * payment module so: {@link #total} is the sum across every order in the group and
 * {@link #orderIds} names them all.
 *
 * <p>{@link #orderId} remains, as the ANCHOR: the one order the {@code payments} row
 * hangs off, because {@code payments.order_id} is a NOT NULL foreign key to a single
 * order. It is a handle, not a claim that the payment covers only that order - see
 * {@code Payment} for the full account of that decision.
 *
 * <p>{@code total} is the authoritative figure the amount check runs against, and the
 * customer fields are what Monnify's hosted checkout displays. Deliberately carries no
 * delivery address or line items: the payment module has no business knowing what was
 * bought.
 *
 * <p>Note there is no {@code clientId} here. Ownership is asserted by the payment
 * module against an explicit client_id predicate before this is ever loaded, so a
 * caller cannot open a checkout - or read a verification - for a company they do not
 * belong to. Because every order in a group shares one buyer by construction, proving
 * ownership of the anchor proves it for the group.
 */
public record OrderPaymentContext(
        UUID orderId,
        String orderNumber,
        UUID checkoutGroupId,
        /**
         * Every order this one payment settles, anchor included, in order-number order.
         * Exactly one element for the ordinary single-seller checkout.
         */
        List<UUID> orderIds,
        /** The sum across {@link #orderIds} - what the buyer is asked to pay, once. */
        BigDecimal total,
        String currency,
        OrderStatus status,
        PaymentStatus paymentStatus,
        String customerName,
        String customerEmail,
        String customerPhone) {

    /** True when this payment covers more than one order. */
    public boolean isSplit() {
        return orderIds != null && orderIds.size() > 1;
    }
}
