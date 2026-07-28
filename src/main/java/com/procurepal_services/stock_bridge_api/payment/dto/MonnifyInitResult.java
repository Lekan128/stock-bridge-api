package com.procurepal_services.stock_bridge_api.payment.dto;

/**
 * The three fields of Monnify's init-transaction {@code responseBody} that matter
 * to us.
 *
 * <p>{@code checkoutUrl} is valid for <b>40 minutes</b>. It is stored on the
 * payment attempt for forensics, never re-served: a buyer retrying payment on an
 * unpaid order gets a brand-new attempt with a brand-new paymentReference, because
 * a stale URL lands them on a Monnify error page with no way forward. See
 * {@code MonnifyPaymentService.initialize}.
 *
 * <p>{@code transactionReference} is Monnify's ("MNFY|20190915200044|000090") and
 * is the key the verify endpoint is addressed by - note the pipes, which is why
 * every use of it is URL-encoded.
 */
public record MonnifyInitResult(String checkoutUrl, String transactionReference, String paymentReference) {
}
