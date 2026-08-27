package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * <h2>V19: the four new fields are IN-movement-only</h2>
 * {@code companyVendorId}/{@code companyVendorName}/{@code packagingUnit}/{@code packagingSize}
 * are null on every OUT/ADJUSTMENT row - see {@code StockMovement.companyVendor}'s own javadoc
 * for why OUT deliberately carries no single vendor. {@code companyVendorName} triggers this
 * lazy association's one lookup per row that has a vendor at all; pages here are small (default
 * size 20) so this is an accepted, bounded N+1 rather than a fetch-joined query.
 */
public record StockMovementResponse(
        UUID id,
        UUID productId,
        MovementType movementType,
        int quantity,
        BigDecimal unitPriceAtTime,
        String note,
        UUID createdByUserId,
        UUID companyVendorId,
        String companyVendorName,
        String packagingUnit,
        BigDecimal packagingSize,
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
                movement.getCompanyVendor() == null ? null : movement.getCompanyVendor().getId(),
                movement.getCompanyVendor() == null ? null : movement.getCompanyVendor().getName(),
                movement.getPackagingUnit(),
                movement.getPackagingSize(),
                movement.getCreatedAt());
    }
}
