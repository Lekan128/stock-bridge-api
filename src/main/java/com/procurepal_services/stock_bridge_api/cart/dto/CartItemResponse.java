package com.procurepal_services.stock_bridge_api.cart.dto;

import com.procurepal_services.stock_bridge_api.entity.CartItem;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.User;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One cart line, enriched with everything the cart page and the checkout summary
 * need so neither has to make a second catalog call per line.
 *
 * <h2>Prices are read live, never snapshotted</h2>
 * unitPrice and lineTotal are computed from the CATALOG row on every read, not from
 * anything stored on cart_items - there is deliberately no price column there. A
 * cart can sit for a week; if ProcurePal reprices a bag of rice, the buyer must see
 * the new price before they agree to it, and checkout recomputes from the same
 * source so the two can never disagree. (Snapshots start existing at order_items,
 * which is the point where a price becomes a commitment.)
 *
 * <h2>quantityOnHand is what is left to SELL, not the raw column</h2>
 * ProcurePal's own {@code quantity_on_hand} only falls when goods are dispatched, so
 * it still counts everything sold this morning and not yet on a van. Reporting that
 * number here would let the cart say "12 left" about twelve bags that are already
 * somebody else's. The figure carried is on-hand minus committed-but-undispatched -
 * see CatalogStockService - which is the number both "Only N left" and the MOQ stepper
 * have to respect.
 *
 * Field names mirror {@code stock-bridge-ui/src/features/cart/types.ts} exactly.
 */
public record CartItemResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        String slug,
        String imageUrl,
        BigDecimal unitPrice,
        String unitOfMeasure,
        int minOrderQuantity,
        int quantityOnHand,
        boolean available,
        int quantity,
        BigDecimal lineTotal,
        UUID addedByUserId,
        String addedByUsername) {

    public static CartItemResponse of(CartItem item, Product catalogProduct, int availableToSell) {
        User addedBy = item.getAddedBy();
        boolean available = catalogProduct != null
                && catalogProduct.isMarketplaceListed()
                && catalogProduct.isActive()
                && availableToSell >= item.getQuantity();

        if (catalogProduct == null) {
            // The catalog row vanished (hard-deleted, or ownership changed). The line
            // stays visible rather than disappearing without explanation, priced at
            // zero and flagged unavailable so it blocks checkout.
            return new CartItemResponse(
                    item.getId(),
                    item.getProductId(),
                    "Unavailable product",
                    "",
                    null,
                    null,
                    BigDecimal.ZERO,
                    null,
                    1,
                    0,
                    false,
                    item.getQuantity(),
                    BigDecimal.ZERO,
                    addedBy == null ? null : addedBy.getId(),
                    addedBy == null ? null : addedBy.getUsername());
        }

        return new CartItemResponse(
                item.getId(),
                catalogProduct.getId(),
                catalogProduct.getName(),
                catalogProduct.getSku(),
                catalogProduct.getSlug(),
                catalogProduct.getImageUrl(),
                catalogProduct.getUnitPrice(),
                catalogProduct.getUnitOfMeasure(),
                catalogProduct.getMinOrderQuantity(),
                availableToSell,
                available,
                item.getQuantity(),
                catalogProduct.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())),
                addedBy == null ? null : addedBy.getId(),
                addedBy == null ? null : addedBy.getUsername());
    }
}
