package com.procurepal_services.stock_bridge_api.payment;

import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitCommand;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyInitResult;
import com.procurepal_services.stock_bridge_api.payment.dto.MonnifyTransactionStatus;

/**
 * The whole of our dependency on Monnify's HTTP API, behind three methods.
 *
 * <p>An interface rather than a concrete class purely so the flow tests can
 * substitute a fake and never touch a socket. {@link MonnifyRestClient} is the
 * only implementation shipped, and it is tested separately against a stubbed
 * request factory so the wire-level details - endpoint paths, envelope shape,
 * token caching, URL encoding - are covered too rather than being faked away.
 */
public interface MonnifyClient {

    /** False when credentials are absent; card checkout then degrades cleanly instead of failing. */
    boolean isConfigured();

    /**
     * Opens a hosted checkout. The returned {@code checkoutUrl} expires in 40
     * minutes and must not be reused for a later attempt.
     *
     * @throws MonnifyNotConfiguredException if credentials are absent
     * @throws MonnifyApiException on transport failure or an unsuccessful response
     */
    MonnifyInitResult initializeTransaction(MonnifyInitCommand command);

    /**
     * Asks Monnify what actually happened to a transaction. This is the ONLY
     * source a payment is ever applied from.
     *
     * @param transactionReference Monnify's reference, unencoded - it commonly
     *     contains {@code |} and the implementation URL-encodes it
     * @throws MonnifyNotConfiguredException if credentials are absent
     * @throws MonnifyApiException on transport failure or an unsuccessful response
     */
    MonnifyTransactionStatus getTransactionStatus(String transactionReference);
}
