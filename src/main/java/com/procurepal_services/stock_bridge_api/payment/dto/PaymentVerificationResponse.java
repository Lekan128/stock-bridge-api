package com.procurepal_services.stock_bridge_api.payment.dto;

import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a server-side re-verification, for the {@code /checkout/return} page.
 *
 * {@code status} is the ATTEMPT's state, and it is the server's own conclusion
 * after asking Monnify - never an echo of a query parameter the buyer arrived
 * with. {@code orderStatus}/{@code orderPaymentStatus} are included so the page
 * can route straight to {@code /order-confirmation/:orderId} without a second
 * request.
 *
 * A PENDING status is a legitimate, expected answer, not an error: the webhook
 * and the browser return race, and the page is expected to retry briefly rather
 * than declare failure on the first one.
 */
public record PaymentVerificationResponse(
        String paymentReference,
        String transactionReference,
        PaymentProviderStatus status,
        UUID orderId,
        String orderNumber,
        OrderStatus orderStatus,
        PaymentStatus orderPaymentStatus,
        BigDecimal amount,
        BigDecimal amountPaid,
        OffsetDateTime paidAt,
        String message) {
}
