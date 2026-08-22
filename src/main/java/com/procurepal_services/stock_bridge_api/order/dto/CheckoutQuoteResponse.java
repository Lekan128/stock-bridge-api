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
 *
 * <h2>The split has to be visible BEFORE the buyer confirms</h2>
 * {@link #sellerGroups} is the same grouping order creation will use, so checkout can
 * show "this basket will become 3 orders" with each one's own subtotal and delivery
 * fee. Publishing it is not a nicety: delivery is charged per seller, so a buyer who
 * only saw one combined figure would discover the real cost after committing, and
 * "why was I charged three delivery fees" is the single most predictable complaint
 * this feature can generate. The top-level {@link #subtotal}/{@link #deliveryFee}/
 * {@link #total} remain the basket-wide sums, which is what the buyer actually pays in
 * one Monnify transaction.
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
        List<UnavailableLine> unavailableItems,
        /**
         * One entry per seller in the basket, in the order the resulting orders will be
         * created. A single-seller basket has exactly one, so the frontend can render
         * the grouped layout unconditionally rather than branching on a count.
         */
        List<SellerGroup> sellerGroups) {

    /** A cart line that has stopped being purchasable since it was added, and why. */
    public record UnavailableLine(java.util.UUID productId, String productName, String reason) {
    }

    /**
     * What one seller's share of this basket will cost, and therefore what one of the
     * resulting orders will look like.
     *
     * <p>Carries the seller's name and logo but not their contact details, for the same
     * reason MarketplaceSellerResponse does - this is served to a buyer who has not yet
     * transacted with them. It also carries no commission rate: what the platform charges
     * a vendor is between the platform and the vendor, and publishing it on a buyer's
     * checkout screen would expose one seller's commercial terms to anyone who put their
     * product in a cart.
     */
    public record SellerGroup(
            java.util.UUID sellerId,
            String sellerName,
            String sellerSlug,
            String sellerLogoUrl,
            boolean platformOwner,
            int itemCount,
            int distinctItemCount,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total,
            boolean freeDeliveryApplied) {
    }
}
