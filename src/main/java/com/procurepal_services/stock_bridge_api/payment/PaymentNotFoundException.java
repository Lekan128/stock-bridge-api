package com.procurepal_services.stock_bridge_api.payment;

/**
 * No such payment reference - OR one that exists but belongs to another company.
 * The two collapse into one 404 on purpose: distinguishing them would turn
 * {@code GET /api/payments/{ref}/verify} into an oracle for whether a reference
 * is real, which is exactly the cross-tenant leak §F of the contract forbids.
 */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException() {
        super("Payment not found.");
    }
}
