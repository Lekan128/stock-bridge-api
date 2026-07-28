package com.procurepal_services.stock_bridge_api.entity;

/**
 * How the buyer chose to pay at checkout (orders.payment_method). Kept to the
 * two options the product actually offers rather than modelling card/transfer/
 * USSD here - those are Monnify channels, recorded on the payment attempt as
 * payments.payment_method_used, because we learn them from the provider after
 * the fact and never ask the buyer to pick one.
 */
public enum PaymentMethod {

    MONNIFY,
    PAY_ON_DELIVERY;

    public boolean isPayOnDelivery() {
        return this == PAY_ON_DELIVERY;
    }
}
