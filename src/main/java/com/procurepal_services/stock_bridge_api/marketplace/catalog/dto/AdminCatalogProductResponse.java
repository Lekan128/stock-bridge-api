package com.procurepal_services.stock_bridge_api.marketplace.catalog.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductApprovalStatus;
import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A product as ProcurePal's own catalog-admin screen sees it: the public fields plus
 * the ones the merchandiser is deciding about (is it listed, is it active, what is it
 * costing us, what is already on its way to a buyer).
 *
 * A superset of MarketplaceProductResponse rather than a wrapper around it, so the
 * admin table can be rendered from one flat object; the two are separate types
 * precisely so adding costPrice here can never publish it.
 *
 * <h2>Three stock numbers, not one - and why</h2>
 * The storefront answers one question ("how many can I buy") and so publishes one number.
 * ProcurePal's warehouse staff have three different jobs and need all three:
 *
 * <ul>
 *   <li>{@code quantityOnHand} - what is physically on the shelf. This is the RAW column,
 *       unlike the public DTO's field of the same name. Someone counting stock needs the
 *       count, not the count minus paperwork.</li>
 *   <li>{@code committedQuantity} - sold, paid for, not yet dispatched. This is the pick
 *       list; it is also the number that explains why a product reads out of stock on the
 *       storefront while pallets of it are visibly in the building.</li>
 *   <li>{@code availableToSell} - what the storefront is currently advertising, so an
 *       operator can see the customer's view without opening the public site.</li>
 * </ul>
 *
 * Collapsing these to one would force a choice between an admin screen that lies about
 * the warehouse and one that lies about the shop - and the gap between them is exactly
 * the thing the operator is being paid to notice.
 *
 * <h2>Moderation state, and why it is on the same object</h2>
 * {@code approvalStatus} and {@code rejectionReason} were added when vendors got a
 * catalogue screen of their own. Two flags decide whether a product is on the public
 * catalogue and only one of them is the seller's: {@code listed} is "I want this sold",
 * {@code approvalStatus} is "the platform says you may" (see
 * MarketplaceProductSpecifications). A screen showing the first without the second
 * cannot explain the single most common vendor question there is - "my product is
 * listed, why can nobody see it" - and a REJECTED listing whose reason is not on screen
 * produces a support ticket instead of a fix.
 *
 * <p>For ProcurePal these are always APPROVED and null respectively, because the
 * platform owner is stamped APPROVED at write time and never queues behind itself
 * (ProductModerationRules). Its own screen can therefore ignore both fields, which is
 * exactly what it does; nothing about ProcurePal's view changes.
 */
public record AdminCatalogProductResponse(
        UUID id,
        String name,
        String sku,
        String slug,
        String description,
        String brand,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        String imageUrl,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize,
        int minOrderQuantity,
        int quantityOnHand,
        int committedQuantity,
        int availableToSell,
        int incomingQuantity,
        Integer lowStockThreshold,
        boolean active,
        boolean listed,
        UUID categoryId,
        String categoryName,
        ProductApprovalStatus approvalStatus,
        // Why a reviewer refused this listing. Deliberately still present after a later
        // approval - the column keeps it (see ProductModerationService.approve) because
        // the history of a contested listing is what anybody asks for when it is
        // disputed. A client showing it must therefore key off approvalStatus, not off
        // the reason being non-null.
        String rejectionReason,
        OffsetDateTime reviewedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /**
     * @param committedQuantity units owed to orders that have not left the warehouse, from
     *     CatalogStockService's batch lookup. Passed in rather than looked up here so a
     *     page of products costs one query, not one per row.
     */
    public static AdminCatalogProductResponse from(Product product, int committedQuantity) {
        ProductCategory category = product.getCategory();
        return new AdminCatalogProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getSlug(),
                product.getDescription(),
                product.getBrand(),
                product.getUnitPrice(),
                product.getCostPrice(),
                product.getImageUrl(),
                product.getUnitOfMeasure(),
                product.getPackagingUnit(),
                product.getPackagingSize(),
                product.getMinOrderQuantity(),
                product.getQuantityOnHand(),
                committedQuantity,
                // Floored at zero for the same reason CatalogStockService floors it: an
                // oversold product is at zero available, and a negative here would read
                // as a number the operator could act on.
                Math.max(0, product.getQuantityOnHand() - committedQuantity),
                product.getIncomingQuantity(),
                product.getLowStockThreshold(),
                product.isActive(),
                // Named "listed", not "marketplaceListed": inside the marketplace-admin
                // API the qualifier is noise, and the request bodies that flip it
                // ({listed: boolean}) are named this way in the contract.
                product.isMarketplaceListed(),
                category == null ? null : category.getId(),
                category == null ? null : category.getName(),
                product.getApprovalStatus(),
                product.getRejectionReason(),
                product.getReviewedAt(),
                product.getCreatedAt(),
                product.getUpdatedAt());
    }
}
