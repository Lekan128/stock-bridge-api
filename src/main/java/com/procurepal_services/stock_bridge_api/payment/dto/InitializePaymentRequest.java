package com.procurepal_services.stock_bridge_api.payment.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Body of {@code POST /api/payments/monnify/initialize}.
 *
 * The order id is the ONLY thing the client gets to choose. Amount, currency and
 * customer details are read server-side from the order - a client-supplied amount
 * is a client-chosen price.
 */
public record InitializePaymentRequest(@NotNull(message = "is required") UUID orderId) {
}
