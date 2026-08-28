package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;

/**
 * A single spreadsheet row that passed column-level validation. excelRow is 1-based, matching
 * what the user sees.
 *
 * <p>{@code unitOfMeasure}, {@code packagingUnit} and {@code packagingSize} have already been
 * validated and normalized by {@link ProductExcelService#parse} by the time a row reaches here -
 * {@code unitOfMeasure}/{@code packagingUnit} are the fixed catalog's CODEs (never a display
 * label, never the casing the user typed, never one of the trade aliases
 * {@code UnitOfMeasure.fromCodeOrLabel} accepts), role-checked ({@code unitOfMeasure} against
 * {@code UnitOfMeasureRole.BASE}, {@code packagingUnit} against {@code PACKAGING}). Three
 * guarantees hold by the time a row reaches here: {@code packagingUnit}/{@code packagingSize}
 * are both-null or both-non-null; and if either packaging field is non-null, {@code
 * unitOfMeasure} is guaranteed non-null too - {@code unitOfMeasure} may still stand alone with
 * both packaging fields null (a product sold loose). {@code unitPrice} is parsed whenever the
 * cell is present regardless of tenant kind, even though a non-seller tenant's value is
 * discarded downstream (see {@code ProductManagementService.toNewProduct}) rather than stored -
 * mirroring how the manual create/update path accepts and then discards a stale unitPrice from
 * a non-seller caller.
 *
 * <h2>The three vendor fields, added with the per-tenant template</h2>
 * {@code vendorName} is exactly what the file said - the tenant's own supplier name, NOT resolved
 * to a {@code CompanyVendor} id. Resolution is deliberately not this record's job: an unmatched
 * name is a question for the user (BULK_IMPORT_DESIGN.md section 6.4's value mapper -
 * "Dangote Ltd" could be "Dangote Nigeria Plc", or could be a supplier they have never entered),
 * and answering it silently here would either invent a directory entry or drop the attribution.
 * The compatibility path resolves it by exact name and settles for null; the session engine puts
 * the question on screen once per distinct name.
 *
 * <p>{@code vendorSku} is that vendor's own code for the product ({@code ProductVendor.vendorSku}).
 * {@code preferredVendor} is the {@code TRUE}/blank flag; both are guaranteed to be absent/false
 * unless {@code vendorName} is present, because a vendor SKU or a preference with no vendor
 * attached is meaningless and is rejected at parse time rather than dropped.
 */
public record ParsedProductRow(
        int excelRow,
        String name,
        String sku,
        String description,
        BigDecimal unitPrice,
        BigDecimal costPrice,
        int quantityOnHand,
        Integer lowStockThreshold,
        String unitOfMeasure,
        String packagingUnit,
        BigDecimal packagingSize,
        String vendorName,
        String vendorSku,
        boolean preferredVendor) {
}
