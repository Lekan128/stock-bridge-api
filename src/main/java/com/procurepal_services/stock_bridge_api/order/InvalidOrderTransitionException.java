package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;

/**
 * The requested status change is not one {@link OrderStatus#canTransitionTo} allows.
 * 409 rather than 400: the request was well formed and would have been valid a moment
 * ago - the order has simply moved on, usually because someone else advanced it.
 */
public class InvalidOrderTransitionException extends RuntimeException {

    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("An order that is " + from + " cannot be moved to " + to + ".");
    }

    public InvalidOrderTransitionException(String message) {
        super(message);
    }
}
