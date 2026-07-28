package com.procurepal_services.stock_bridge_api.payment;

/**
 * Card payment was attempted while the Monnify credentials are absent or
 * incomplete. Distinct from {@link MonnifyApiException}, which means Monnify is
 * configured but unhappy: this one is a deployment problem, not a provider
 * problem, and it is the caller-visible half of the graceful degradation
 * described on {@link MonnifyProperties}.
 *
 * Maps to 503 - the feature is temporarily unavailable, the request was not
 * wrong. Pay-on-delivery remains available throughout.
 */
public class MonnifyNotConfiguredException extends RuntimeException {

    public MonnifyNotConfiguredException() {
        super("Card payment is temporarily unavailable. Please choose pay on delivery, or try again later.");
    }
}
