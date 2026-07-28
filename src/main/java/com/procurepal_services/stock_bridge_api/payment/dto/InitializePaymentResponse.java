package com.procurepal_services.stock_bridge_api.payment.dto;

/**
 * Where to send the buyer, plus the two references the return page needs.
 *
 * The frontend must redirect to {@code checkoutUrl} immediately and never cache
 * it: Monnify expires it 40 minutes after issue. It should also keep
 * {@code paymentReference}, which is what {@code GET /api/payments/{ref}/verify}
 * is addressed by when the buyer comes back.
 */
public record InitializePaymentResponse(
        String checkoutUrl, String paymentReference, String transactionReference) {
}
