package com.procurepal_services.stock_bridge_api.payment;

/**
 * The order exists and belongs to the caller, but a Monnify checkout may not be
 * opened for it - it is already paid, it is pay-on-delivery, or it has been
 * cancelled.
 *
 * Being explicit about "already paid" matters more than it looks: without this
 * check a double-clicked Pay button would open a second checkout against an order
 * that is already settled, and the buyer would be charged twice for goods they
 * already own. The message is safe to show a buyer.
 */
public class OrderNotPayableException extends RuntimeException {

    public OrderNotPayableException(String message) {
        super(message);
    }
}
