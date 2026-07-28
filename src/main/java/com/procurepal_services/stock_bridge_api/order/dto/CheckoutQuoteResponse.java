package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.address.dto.DeliveryAddressResponse;
import java.math.BigDecimal;
import java.util.List;

/**
 * What checkout will cost and what (if anything) is stopping it.
 *
 * <h2>Why the blockers are strings the UI can render, not just booleans</h2>
 * Contract §10 forbids a silently inert submit button. Every reason an order cannot
 * be placed is therefore returned as a sentence, so the checkout page can disable
 * the button AND say why - without re-deriving the commercial rules (minimum order
 * value, pay-on-delivery caps, per-line availability) that only the server knows.
 *
 * The same computation backs {@code POST /api/orders}, so a quote can never promise
 * a total the order then refuses.
 */
public record CheckoutQuoteResponse(
        String currency,
        BigDecimal subtotal,
        BigDecimal deliveryFee,
        BigDecimal total,
        int itemCount,
        int distinctItemCount,
        BigDecimal freeDeliveryThreshold,
        BigDecimal amountToFreeDelivery,
        boolean freeDeliveryApplied,
        BigDecimal minimumOrderValue,
        boolean meetsMinimumOrderValue,
        boolean canCheckout,
        List<String> blockers,
        boolean payOnDeliveryEligible,
        List<String> payOnDeliveryReasons,
        BigDecimal payOnDeliveryMaxOrderValue,
        DeliveryAddressResponse deliveryAddress,
        List<UnavailableLine> unavailableItems) {

    /** A cart line that has stopped being purchasable since it was added, and why. */
    public record UnavailableLine(java.util.UUID productId, String productName, String reason) {
    }
}
