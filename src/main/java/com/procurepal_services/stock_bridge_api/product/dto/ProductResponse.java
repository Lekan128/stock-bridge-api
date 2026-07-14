package com.procurepal_services.stock_bridge_api.product.dto;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String sku,
        String description,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        int quantityOnHand,
        Integer lowStockThreshold,
        String imageUrl,
        boolean active,
        boolean isLowStock,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> warnings) {

    public static ProductResponse from(Product product) {
        return from(product, null);
    }

    public static ProductResponse from(Product product, List<String> warnings) {
        boolean lowStock = product.getLowStockThreshold() != null
                && product.getQuantityOnHand() <= product.getLowStockThreshold();
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSku(),
                product.getDescription(),
                product.getUnitPrice(),
                product.getCostPrice(),
                product.getQuantityOnHand(),
                product.getLowStockThreshold(),
                product.getImageUrl(),
                product.isActive(),
                lowStock,
                product.getCreatedAt(),
                product.getUpdatedAt(),
                warnings);
    }
}
