package com.procurepal_services.stock_bridge_api.order.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.util.List;
import java.util.UUID;

/**
 * MULTI_VENDOR_INVENTORY_DESIGN.md section 7.2's duplicate nudge: for an order line whose
 * receiving service could not match an existing product at PLACED time (no source-product or
 * SKU hit), other products already in the buyer's own inventory whose name looks like the same
 * real-world item. Only lines with at least one candidate above {@code NameSimilarity
 * .SUGGESTION_FLOOR} appear at all - a line that matched cleanly, or has nothing close enough to
 * suggest, has nothing to ask and is simply absent from the list.
 */
public record OrderItemMatchSuggestionResponse(UUID orderItemId, List<ProductMatchCandidateResponse> candidates) {

    public record ProductMatchCandidateResponse(
            UUID id, String name, int quantityOnHand, String imageUrl, String unitOfMeasure) {

        public static ProductMatchCandidateResponse from(Product product) {
            return new ProductMatchCandidateResponse(
                    product.getId(),
                    product.getName(),
                    product.getQuantityOnHand(),
                    product.getImageUrl(),
                    product.getUnitOfMeasure());
        }
    }
}
