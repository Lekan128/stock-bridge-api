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
 *
 * <h2>V28: productName/productSku/unitOfMeasure/lineValue, for the stock in/out report</h2>
 * The tenant-wide report at {@code GET /api/stock/movements} lists movements across every
 * product, so a row identified only by {@code productId} would send the frontend to a second
 * endpoint per row just to render a name. The three product fields are read from the association
 * {@code StockMovementSpecifications.forTenant} now fetch-joins, so they cost no extra query -
 * see that method for why the joins are there.
 *
 * <p>{@code lineValue} is {@code quantity * unitPriceAtTime}, and is NULL - never zero - when the
 * movement recorded no price. Every ADJUSTMENT is such a row, as is a free sample or a delivery
 * entered before anyone knew what it cost, and "we do not know what this was worth" is a
 * different claim from "this was worth nothing". It is computed here rather than in the browser
 * so that the report's rows and the totals beside them, which the database sums, are the same
 * arithmetic on the same two columns.
 */
public record StockMovementResponse(
        UUID id,
        UUID productId,
        String productName,
        String productSku,
        /** The product's stock unit - the unit {@code quantity} is counted in. */
        String unitOfMeasure,
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
        /** {@code quantity * unitPriceAtTime}, or null when no price was recorded. See the class doc. */
        BigDecimal lineValue,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt) {

    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                // .getId() on a lazy association never triggers a DB fetch, even
                // for an uninitialized proxy - Hibernate already knows the FK value.
                movement.getProduct().getId(),
                // These three DO dereference the association. Free on the report, which
                // fetch-joins it; one query per row on any caller that does not, the same
                // bounded N+1 companyVendorName below has always carried.
                movement.getProduct().getName(),
                movement.getProduct().getSku(),
                movement.getProduct().getUnitOfMeasure(),
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
                lineValue(movement),
                movement.getOccurredAt(),
                movement.getCreatedAt());
    }

    /** Null, not zero, when the movement recorded no price - see the class doc. */
    private static BigDecimal lineValue(StockMovement movement) {
        BigDecimal unitPrice = movement.getUnitPriceAtTime();
        return unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(movement.getQuantity()));
    }
}
