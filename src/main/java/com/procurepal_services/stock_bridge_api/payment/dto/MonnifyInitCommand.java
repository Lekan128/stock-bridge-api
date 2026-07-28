package com.procurepal_services.stock_bridge_api.payment.dto;

import java.math.BigDecimal;

/**
 * What we ask Monnify to open a checkout for. Field names here are ours;
 * {@code MonnifyRestClient} maps them onto the provider's documented
 * init-transaction body (amount / customerName / customerEmail /
 * paymentReference / paymentDescription / currencyCode / contractCode /
 * redirectUrl). contractCode and redirectUrl are not carried - they come from
 * configuration, not from a caller.
 */
public record MonnifyInitCommand(
        String paymentReference,
        BigDecimal amount,
        String currencyCode,
        String customerName,
        String customerEmail,
        String paymentDescription) {
}
