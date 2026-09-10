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
 *
 * <h2>V20: createdAt and occurredAt are both published, and they are different questions</h2>
 * {@code createdAt} is when this row was WRITTEN; {@code occurredAt} is when the delivery,
 * sale or adjustment actually HAPPENED. They are equal for anything recorded as it happens,
 * which is why one field sufficed until bulk stock-in made backdating ordinary - see
 * {@code StockMovement.occurredAt} and BULK_IMPORT_DESIGN.md section 8.4. Any screen showing a
 * user "when did this arrive" wants {@code occurredAt}; anything auditing "when did this get
 * entered" wants {@code createdAt}, and the two must not be used interchangeably now that they
 * can genuinely differ.
 *
 * <h2>V21: enteredUnit/enteredQuantity/enteredUnitPrice were captured but never published</h2>
 * {@code StockMovement} has stored these since V21 specifically so a delivery entered in a
 * one-off pack (the stock-in modal's "this delivery came in a different pack", left unticked so
 * it never becomes a persistent {@code ProductVendorPack}) stays legible in the ledger afterward.
 * Until now they stopped at this DTO's boundary, so the only place that fact could ever resurface
 * was empty. Null on every OUT/ADJUSTMENT row and on any IN entered directly in the stock unit,
 * same as {@code packagingUnit}/{@code packagingSize}.
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
        String enteredUnit,
        BigDecimal enteredQuantity,
        BigDecimal enteredUnitPrice,
        OffsetDateTime occurredAt,
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
                movement.getEnteredUnit(),
                movement.getEnteredQuantity(),
                movement.getEnteredUnitPrice(),
                movement.getOccurredAt(),
                movement.getCreatedAt());
    }
}
