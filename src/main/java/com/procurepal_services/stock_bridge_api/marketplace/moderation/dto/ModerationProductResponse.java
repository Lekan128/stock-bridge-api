package com.procurepal_services.stock_bridge_api.marketplace.moderation.dto;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One listing awaiting - or having had - a moderation decision, as the super admin's
 * queue shows it.
 *
 * <h2>Why this carries more than the public DTO, and still not everything</h2>
 * A reviewer is deciding whether a real buying company should be allowed to turn this
 * into a purchase order, so they need what the buyer would see (name, image, price,
 * unit, MOQ, description) plus the things a buyer never sees but a reviewer must: who
 * is selling it, whether it is already flagged for listing, and the full history of
 * previous decisions.
 *
 * <p>It deliberately does NOT carry {@code costPrice}. That is the vendor's own margin
 * and has no bearing on whether a listing is honest; a moderation screen is not a
 * reason to pipe one business's buying prices to the operator's browser. It also
 * carries no stock figures - a listing is approved on what it claims to be, not on how
 * many are in the warehouse today, and moderating on stock would send listings back to
 * the queue every time a vendor sold something.
 */
public record ModerationProductResponse(
        UUID id,
        String name,
        String sku,
        String slug,
        String description,
        String brand,
        BigDecimal unitPrice,
        String imageUrl,
        String unitOfMeasure,
        int minOrderQuantity,
        ProductApprovalStatus approvalStatus,
        String rejectionReason,
        OffsetDateTime reviewedAt,
        UUID reviewedBy,
        /**
         * Whether the seller has asked for this to be public. A PENDING product with
         * this false is a draft the vendor has not submitted; with it true, the vendor
         * is waiting on the operator. The queue shows both and sorts the waiting ones
         * first, because only one of the two is blocking somebody.
         */
        boolean marketplaceListed,
        boolean active,
        UUID sellerId,
        String sellerName,
        String sellerSlug,
        String sellerLogoUrl,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ModerationProductResponse from(Product product, Client seller) {
        return new ModerationProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getSlug(),
                product.getDescription(),
                product.getBrand(),
                product.getUnitPrice(),
                product.getImageUrl(),
                product.getUnitOfMeasure(),
                product.getMinOrderQuantity(),
                product.getApprovalStatus(),
                product.getRejectionReason(),
                product.getReviewedAt(),
                product.getReviewedBy(),
                product.isMarketplaceListed(),
                product.isActive(),
                seller == null ? null : seller.getId(),
                seller == null ? null : seller.getName(),
                seller == null ? null : seller.getSlug(),
                seller == null ? null : seller.getLogoUrl(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
