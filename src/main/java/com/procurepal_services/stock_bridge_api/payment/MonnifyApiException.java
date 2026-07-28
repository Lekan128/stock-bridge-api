package com.procurepal_services.stock_bridge_api.payment;

/**
 * Monnify is configured but the call did not produce a usable answer - a
 * transport failure, a timeout, a non-2xx, or a body whose
 * {@code requestSuccessful} was false.
 *
 * Deliberately NOT thrown for "the transaction is not paid": that is a perfectly
 * good answer and comes back as a MonnifyTransactionStatus. This is only for
 * "we do not know", which is the case that must never be mistaken for either
 * outcome. Callers on the webhook and reconciliation paths swallow it and leave
 * the attempt PENDING so the sweep retries; the interactive paths surface it as
 * a 502.
 *
 * The message never carries the secret key or a card number - see
 * MonnifyRestClient, which logs by paymentReference only.
 */
public class MonnifyApiException extends RuntimeException {

    public MonnifyApiException(String message) {
        super(message);
    }

    public MonnifyApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
