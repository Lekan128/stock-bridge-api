package com.procurepal_services.stock_bridge_api.order;

import java.math.BigDecimal;

/**
 * A verified payment did not cover the order total.
 *
 * The payment module already performs this check before calling
 * {@link OrderPaymentApplication#applyPaymentSuccess}; this is the second,
 * independent one. Duplication is deliberate - marking an underpaid order as paid is
 * unrecoverable without a human, so the check that protects against it should not
 * live in only one module.
 *
 * Throwing rolls back the caller's payment transaction with it, which is what we
 * want: the attempt stays unapplied and the reconciliation sweep will look at it
 * again.
 */
public class PaymentAmountMismatchException extends RuntimeException {

    public PaymentAmountMismatchException(String orderNumber, BigDecimal expected, BigDecimal actual) {
        super("Payment for order " + orderNumber + " was " + actual + " but the order total is " + expected + ".");
    }
}
