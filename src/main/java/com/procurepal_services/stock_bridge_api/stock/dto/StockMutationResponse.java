package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;

/**
 * Bundles the updated product (isLowStock included) with the movement that
 * caused it, so the frontend can refresh its low-stock banner from this
 * response alone instead of issuing a follow-up GET.
 */
public record StockMutationResponse(ProductResponse product, StockMovementResponse movement) {

    public static StockMutationResponse of(Product product, StockMovement movement) {
        return new StockMutationResponse(
                ProductResponse.from(product), movement == null ? null : StockMovementResponse.from(movement));
    }
}
