package com.procurepal_services.stock_bridge_api.cart.dto;

import com.procurepal_services.stock_bridge_api.entity.CartItem;
import com.procurepal_services.stock_bridge_api.entity.Client;
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
 * <h2>The seller is carried per line, because a cart can hold several</h2>
 * A basket may mix sellers, and at checkout it splits into one order per seller. The
 * cart page therefore has to group its lines by seller and show each group's subtotal,
 * which it cannot do without knowing whose each line is. Name and logo only, matching
 * every other buyer-facing seller projection - see MarketplaceSellerResponse for the
 * argument about contact details.
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
        String addedByUsername,
        /** Who sells this line. Null only when the catalog row itself has vanished. */
        UUID sellerId,
        String sellerName,
        String sellerLogoUrl,
        boolean sellerIsPlatformOwner) {

    public static CartItemResponse of(
            CartItem item, Product catalogProduct, int availableToSell, Client seller) {
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
                    addedBy == null ? null : addedBy.getUsername(),
                    null,
                    null,
                    null,
                    false);
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
                addedBy == null ? null : addedBy.getUsername(),
                seller == null ? catalogProduct.getClientId() : seller.getId(),
                seller == null ? null : seller.getName(),
                seller == null ? null : seller.getLogoUrl(),
                seller != null && seller.isPlatformOwner());
    }
}
