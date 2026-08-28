package com.procurepal_services.stock_bridge_api.stock.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code quantity} is always in whichever unit {@code unit} names - see below - and is
 * converted to the product's base {@code unitOfMeasure} before anything is written to the
 * ledger; every stored quantity (this movement, {@code Product.quantityOnHand}, the vendor's
 * cached rollups) stays in base units, unchanged since before V19.
 *
 * <h2>unit - which of the product's configured units this quantity was entered in</h2>
 * Optional. Either the product's base {@code unitOfMeasure} or its (resolved) packaging unit
 * for this delivery, e.g. {@code "KG"} or {@code "BAG"}. Null or equal to the base unit means
 * no conversion is needed. Equal to the packaging unit means {@code quantity} is multiplied by
 * {@code packagingSize} (this request's, if supplied, else the {@code ProductVendor}'s {@code
 * defaultPackagingSize}) to get the base-unit quantity actually stored. Validated and resolved
 * by {@code StockManagementService}, not Bean Validation - it depends on the product's own
 * configured units, which is not something an annotation can see.
 *
 * <h2>companyVendorId - conditionally required, and not by an annotation</h2>
 * Which supplier this delivery came from. {@code @NotNull} does not appear here deliberately:
 * it is required once the product already has at least one {@code ProductVendor} row (see
 * {@code CompanyVendorRequiredException}), and optional - the product simply has no vendors yet
 * - otherwise. That is a database lookup, not something the shape of this request alone can
 * express, the same reasoning {@code CreateProductRequest.unitPrice}'s javadoc gives for why
 * its own conditional requirement is not a static annotation either.
 *
 * <h2>packagingUnit / packagingSize - this DELIVERY's packaging, snapshotted</h2>
 * Both optional, and pair both-or-neither the same way {@code Product.packagingUnit}/
 * {@code packagingSize} do. Frozen onto the resulting {@code StockMovement} row rather than
 * only updating the vendor's default - a later change to the vendor's default packaging must
 * not rewrite what this specific delivery said. If omitted, the vendor's own {@code
 * defaultPackagingUnit}/{@code defaultPackagingSize} are used as the packaging snapshot instead
 * (and, if the vendor line is brand new, become its default going forward).
 *
 * <h2>occurredAt - when the delivery HAPPENED, added V20</h2>
 * Optional; null means now, which is what every pre-V20 caller effectively said and so nothing
 * about their behaviour changes. Supplied, it backdates the resulting {@code StockMovement}'s
 * {@code occurredAt} - the {@code received_date} column of the bulk stock-in sheet, and the
 * reason that column exists at all (BULK_IMPORT_DESIGN.md section 8.4). FIFO orders lots by
 * {@code (occurredAt, createdAt)}, so a delivery entered today but received last month is drawn
 * from before stock that arrived after it, which is the correct answer and the one a
 * {@code createdAt}-only ordering could not give.
 *
 * <p>Validated not-in-the-future by {@code StockManagementService}, not by an annotation - the
 * rule carries a day of clock/timezone-skew grace (see {@code StockMovement.occurredAt}), and a
 * {@code @PastOrPresent} would refuse a delivery entered as "today" from a device an hour ahead
 * with a message that is simply false from where the user is sitting. Same reasoning
 * {@code companyVendorId} above gives for why its own conditional requirement is not a static
 * annotation either.
 */
public record StockInRequest(
        @NotNull @Positive Integer quantity,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @Size(max = 1000) String note,
        String unit,
        UUID companyVendorId,
        String packagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize,
        OffsetDateTime occurredAt) {

    /** Convenience for callers that only ever supplied the pre-V19 three fields. */
    public StockInRequest(Integer quantity, BigDecimal unitPrice, String note) {
        this(quantity, unitPrice, note, null, null, null, null, null);
    }

    /**
     * Convenience for the pre-V20 seven-field shape - every caller that has a vendor and
     * packaging to state but nothing to say about WHEN, which is every caller recording a
     * delivery as it happens. Kept as its own constructor rather than making callers pass a
     * trailing null, so that adding {@code occurredAt} did not touch a single existing call
     * site: the field is genuinely additive, and a diff that rewrote every {@code
     * new StockInRequest(...)} in the codebase to say "null" would have obscured that.
     */
    public StockInRequest(
            Integer quantity,
            BigDecimal unitPrice,
            String note,
            String unit,
            UUID companyVendorId,
            String packagingUnit,
            BigDecimal packagingSize) {
        this(quantity, unitPrice, note, unit, companyVendorId, packagingUnit, packagingSize, null);
    }
}
