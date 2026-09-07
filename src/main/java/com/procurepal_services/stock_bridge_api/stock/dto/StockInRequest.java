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
 * <h2>unit - which of the product's units this quantity and this price were entered in</h2>
 * Optional. Must be the {@code code} of one of the options in this product's unit set - its
 * stock unit, its own pack, or a same-category base unit with a static factor - as derived by
 * {@code UnitOptions} and published on {@code ProductResponse.unitOptions} /
 * {@code ProductVendorResponse.unitOptions} (UNIT_UX_CONTRACT.md section 2). Anything else is a
 * 400 naming every option that WOULD have worked; see {@code InvalidStockUnitException}. Null or
 * blank means the stock unit, factor 1 - which is what every caller that predates this field
 * already said implicitly, and their behaviour is unchanged in every particular (non-negotiable
 * 8). Resolved by {@code StockManagementService}, not Bean Validation, because the valid set
 * depends on the product's own configuration and on this request's own pack override, neither of
 * which an annotation can see.
 *
 * <h2>unitPrice - per {@code unit}, and this is the field that used to lie</h2>
 * {@code unitPrice} is the price of ONE {@code unit}: per bag when {@code unit} is BAG, per kg
 * when it is KG or absent. The service divides by the same factor it multiplies {@code quantity}
 * by, so what reaches {@code Product.costPrice}, {@code ProductVendor.lastCostPrice} and
 * {@code StockMovement.unitPriceAtTime} is always per STOCK unit (contract section 3.2).
 *
 * <p>Before this, the quantity half of an entry was converted and the price half was not:
 * "20 bags at &#8358;45,000 per bag" on a 50 kg-bag product recorded 1,000 kg at &#8358;45,000
 * per kg - a fifty-fold error, written silently and compounded into every later weighted average
 * (UNIT_UX_REMEDIATION_PLAN.md section 3, P0-1). A request that omits {@code unit} has factor 1
 * and so divides by nothing, which is why closing that hole changed no existing caller.
 *
 * <h2>companyVendorId - conditionally required, and not by an annotation</h2>
 * Which supplier this delivery came from. {@code @NotNull} does not appear here deliberately:
 * it is required once the product already has at least one {@code ProductVendor} row (see
 * {@code CompanyVendorRequiredException}), and optional - the product simply has no vendors yet
 * - otherwise. That is a database lookup, not something the shape of this request alone can
 * express, the same reasoning {@code CreateProductRequest.unitPrice}'s javadoc gives for why
 * its own conditional requirement is not a static annotation either.
 *
 * <h2>packagingUnit / packagingSize - this DELIVERY's pack, snapshotted</h2>
 * Both optional. Frozen onto the resulting {@code StockMovement} row rather than only updating
 * the supplier's default - a later change to that default must not rewrite what this specific
 * delivery said.
 *
 * <p>They also EXTEND this product's unit set for the duration of this request, adding one
 * option that {@code unit} may then name (contract section 3.1). They do not bypass matching:
 * "this delivery came in a 25 kg bag" adds "Bag of 25 kg" to the list and resolution then
 * happens against the list exactly as it does for every other request. That is the fix for
 * P1-1 - the old code hand-rolled a separate two-branch comparison here, so the answer to
 * "which units does this product accept" depended on which code path you asked, and picking
 * anything else from the modal's thirty-code list was a guaranteed 400.
 *
 * <h2>saveAsSupplierDefault - the opt-in that makes the help text true</h2>
 * Nullable, and <b>false is the default in every sense</b>: absent means false. When false, the
 * pack above applies to THIS delivery only and {@code ProductVendor.defaultPackagingUnit}/
 * {@code defaultPackagingSize} are left exactly as they were. When true, this delivery's pack
 * also becomes that supplier's standing default going forward.
 *
 * <p>Contract non-negotiable 7: "a per-delivery override never mutates stored configuration
 * without an explicit opt-in on the same screen." Until now the opposite was true and the UI
 * said so out loud - the stock-in modal promised "the vendor's default stays unchanged" while
 * {@code ProductVendorService.findOrCreateForReceipt} overwrote it from these very fields
 * (P0-5). {@code lastCostPrice} is deliberately NOT behind this flag: a price paid is a running
 * fact about the relationship, not a configuration choice somebody makes.
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
 *
 * <h2>vendorSku - the SUPPLIER's own code for this product, added for marketplace receipts</h2>
 * Optional, and completely independent of {@code Product.sku} - that field is this tenant's OWN
 * identifier and, once SKU generation is enabled, is never something a caller like a marketplace
 * receipt should be choosing. This is the other half: {@code ProductVendorPack.vendorSku},
 * "Supplier's code" in the locked vocabulary (MULTI_VENDOR_INVENTORY_DESIGN.md section 4a) -
 * written onto the pack this receipt resolves to (see {@code ProductVendorService
 * .applyReceiptToPack}), same as {@code lastCostPrice}. Null leaves whatever the pack already
 * has untouched; non-null overwrites it every time, on the same reasoning {@code lastCostPrice}
 * does - it is a running fact about the relationship, and the supplier's most recent delivery
 * note is more likely correct than whatever an earlier one said.
 */
public record StockInRequest(
        @NotNull @Positive Integer quantity,
        @DecimalMin(value = "0", inclusive = true) BigDecimal unitPrice,
        @Size(max = 1000) String note,
        String unit,
        UUID companyVendorId,
        String packagingUnit,
        @DecimalMin(value = "0", inclusive = true) BigDecimal packagingSize,
        OffsetDateTime occurredAt,
        Boolean saveAsSupplierDefault,
        String vendorSku) {

    /** Convenience for callers that only ever supplied the pre-V19 three fields. */
    public StockInRequest(Integer quantity, BigDecimal unitPrice, String note) {
        this(quantity, unitPrice, note, null, null, null, null, null, null, null);
    }

    /**
     * Convenience for the pre-{@code vendorSku} nine-field shape - additive, same reasoning as
     * every other constructor here: a diff that rewrote every existing {@code new
     * StockInRequest(...)} to trail a null would have obscured that nothing about their meaning
     * changed.
     */
    public StockInRequest(
            Integer quantity,
            BigDecimal unitPrice,
            String note,
            String unit,
            UUID companyVendorId,
            String packagingUnit,
            BigDecimal packagingSize,
            OffsetDateTime occurredAt,
            Boolean saveAsSupplierDefault) {
        this(quantity, unitPrice, note, unit, companyVendorId, packagingUnit, packagingSize, occurredAt,
                saveAsSupplierDefault, null);
    }

    /**
     * Convenience for the pre-V21 eight-field shape - every caller that has nothing to say about
     * whether this delivery's pack should become the supplier's standing default, which is every
     * caller that predates contract section 3.4. Kept as its own constructor for the same reason
     * the {@code occurredAt} one below was: the field is additive, and a diff that rewrote every
     * existing {@code new StockInRequest(...)} to trail a {@code null} would have obscured that.
     * Absent means false, and false means "touch nothing" - see the class javadoc.
     */
    public StockInRequest(
            Integer quantity,
            BigDecimal unitPrice,
            String note,
            String unit,
            UUID companyVendorId,
            String packagingUnit,
            BigDecimal packagingSize,
            OffsetDateTime occurredAt) {
        this(quantity, unitPrice, note, unit, companyVendorId, packagingUnit, packagingSize, occurredAt, null);
    }

    /**
     * Whether this delivery's pack should also become the supplier's standing default. Null -
     * the wire's ordinary state for a field a client never mentions - reads as false, so the
     * safe answer is the one a caller gets by saying nothing (contract section 3.4).
     */
    public boolean savesAsSupplierDefault() {
        return Boolean.TRUE.equals(saveAsSupplierDefault);
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
        this(quantity, unitPrice, note, unit, companyVendorId, packagingUnit, packagingSize, null, null);
    }
}
