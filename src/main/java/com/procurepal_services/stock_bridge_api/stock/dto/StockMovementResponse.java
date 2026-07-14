package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record StockMovementResponse(
        UUID id,
        UUID productId,
        MovementType movementType,
        int quantity,
        BigDecimal unitPriceAtTime,
        String note,
        UUID createdByUserId,
        OffsetDateTime createdAt) {

    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                // .getId() on a lazy association never triggers a DB fetch, even
                // for an uninitialized proxy - Hibernate already knows the FK value.
                movement.getProduct().getId(),
                movement.getMovementType(),
                movement.getQuantity(),
                movement.getUnitPriceAtTime(),
                movement.getNote(),
                movement.getCreatedBy() == null ? null : movement.getCreatedBy().getId(),
                movement.getCreatedAt());
    }
}
