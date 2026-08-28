package com.procurepal_services.stock_bridge_api.entity;

/**
 * What an import does when a row's SKU already exists in the tenant's catalog - NetSuite's
 * Add / Update / Add-or-Update, chosen at upload. Stored as its name in {@code
 * import_sessions.mode} under a CHECK on exactly these three spellings
 * (BULK_IMPORT_CONTRACT.md section 1).
 *
 * <h2>Why this is asked BEFORE the file is validated, not after</h2>
 * It changes what the word "error" MEANS during validation, so it cannot be a question posed
 * afterwards (BULK_IMPORT_DESIGN.md section 6.3/9.2). The same row - an existing SKU - is a
 * hard error under {@link #CREATE_ONLY}, an ordinary update under {@link #CREATE_OR_UPDATE},
 * and the only valid kind of row under {@link #UPDATE_ONLY}. Asking after the parse would mean
 * re-validating the whole file against a different rulebook and showing the user a different
 * set of red cells than the ones they were just looking at.
 *
 * <p>This is also the fix for the workflow the pre-existing bulk upload made impossible.
 * {@code ProductManagementService.bulkUpload} treats an existing SKU as a hard error, full
 * stop, which means "re-upload my supplier's updated price list" - the single most natural
 * recurring use of a product import - could not be done at all.
 *
 * <h2>Three rules every update path inherits, none of them from this enum</h2>
 * <ul>
 *   <li>{@code unitOfMeasure} is immutable once the product has any {@link StockMovement}
 *       (MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3, and see {@code Product.unitOfMeasure}).
 *       An update row trying to change it is an error on that cell with the reason spelled
 *       out - never silently ignored.</li>
 *   <li>An update never touches {@code quantity_on_hand}. Quantity moves through the ledger or
 *       not at all (design doc section 3); on an update row the column is ignored and the
 *       review grid says so, linking to stock-in.</li>
 *   <li>{@code costPrice} is not directly settable on an update either - it is the weighted
 *       average, owned by {@code StockManagementService.stockIn}. A {@code cost_price} column
 *       on an update row updates the VENDOR line's {@code lastCostPrice}, which is the thing
 *       the user actually meant.</li>
 * </ul>
 *
 * <p>Meaningless for {@link ImportKind#STOCK_IN}: persist {@link #CREATE_ONLY} and ignore it.
 */
public enum ImportMode {

    /**
     * Existing SKU is an error on that row; a new SKU is created. The default, and deliberately
     * so (design doc section 13, decision 1): it matches what the pre-existing bulk upload
     * already did, and it is the safe answer for somebody who has not thought about the
     * question. The radio button is right there for anybody who has.
     */
    CREATE_ONLY,

    /** Existing SKU updates the changed fields; a new SKU is created. The price-list re-upload. */
    CREATE_OR_UPDATE,

    /** Existing SKU updates; a new SKU is an error on that row. */
    UPDATE_ONLY
}
