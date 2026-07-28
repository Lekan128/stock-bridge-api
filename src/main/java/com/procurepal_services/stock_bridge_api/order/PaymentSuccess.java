package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.entity.PaymentVerificationSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A payment the server has verified against the provider and checked against the
 * order total. Nothing in here came from a browser.
 *
 * @param amountPaid what the provider says was actually paid, not what we asked
 *     for. It is carried rather than assumed equal to the order total so the
 *     order module records the real figure - an overpayment is settled at the
 *     amount received, and an underpayment never reaches this record at all
 *     (it goes to {@link OrderPaymentApplication#applyPaymentFailure}).
 * @param paymentMethodUsed the provider's channel string (CARD, ACCOUNT_TRANSFER,
 *     USSD, ...), not one of our PaymentMethod values - we learn it after the fact.
 * @param verifiedVia which of the three independent paths produced this. A spike
 *     in RECONCILIATION means webhook delivery is broken, which is otherwise
 *     invisible because the orders still end up paid.
 */
public record PaymentSuccess(
        String paymentReference,
        String providerTransactionReference,
        BigDecimal amountPaid,
        OffsetDateTime paidAt,
        String paymentMethodUsed,
        PaymentVerificationSource verifiedVia) {
}
