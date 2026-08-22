package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One line of "what we buy from this supplier, and what we last paid for it" -
 * the single highest-value field on a buyer's vendor record according to
 * VENDOR_RESEARCH.md Section B, and Odoo's reason for auto-filling a price on the
 * next purchase order.
 *
 * <h2>The product is the BUYER's own inventory row</h2>
 * Not the seller's catalogue product. A buying company reasons about "50kg bags of
 * rice" as the row in their own store, and that is what the link on
 * {@code products.company_vendor_id} points from. The seller's catalogue row is
 * reachable through {@code Product.sourceProductId} where anybody needs it.
 *
 * <h2>Why the price is nullable, and what null means</h2>
 * Null is "we have this product filed under this supplier but have never bought it
 * from them through the platform". Two ordinary ways that happens: an EXTERNAL
 * supplier, where there are no platform orders at all and null is the permanent
 * and correct answer; and a product a buyer linked to a VERIFIED vendor by hand
 * before ever ordering it. Null is not an error and must not render as zero -
 * "we last paid nothing" is a different and false claim.
 */
public record VendorProductPriceResponse(
        UUID productId,
        String name,
        String sku,
        String unitOfMeasure,
        String imageUrl,
        int quantityOnHand,
        int incomingQuantity,
        /* What was actually paid per unit on the most recent qualifying order line. Null - see above. */
        BigDecimal lastPurchaseUnitPrice,
        Integer lastPurchaseQuantity,
        OffsetDateTime lastPurchasedAt,
        /* The order that price came from, so the buyer can open it rather than take our word for it. */
        UUID lastPurchaseOrderId,
        String lastPurchaseOrderNumber) {

    /** A linked product with no purchase behind it yet - see the class comment on null. */
    public static VendorProductPriceResponse withoutPurchase(Product product) {
        return new VendorProductPriceResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getUnitOfMeasure(),
                product.getImageUrl(),
                product.getQuantityOnHand(),
                product.getIncomingQuantity(),
                null,
                null,
                null,
                null,
                null);
    }
}
