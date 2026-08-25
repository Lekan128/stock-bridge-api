package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;

/**
 * A single spreadsheet row that passed column-level validation. excelRow is 1-based, matching
 * what the user sees.
 *
 * <p>{@code unitOfMeasure}, {@code packagingUnit} and {@code packagingSize} have already been
 * validated and normalized by {@link ProductExcelService#parse} by the time a row reaches here -
 * {@code unitOfMeasure}/{@code packagingUnit} are the fixed catalog's CODEs (never a display
 * label, never the casing the user typed), role-checked ({@code unitOfMeasure} against
 * {@code UnitOfMeasureRole.BASE}, {@code packagingUnit} against {@code PACKAGING}). Three
 * guarantees hold by the time a row reaches here: {@code packagingUnit}/{@code packagingSize}
 * are both-null or both-non-null; and if either packaging field is non-null, {@code
 * unitOfMeasure} is guaranteed non-null too - {@code unitOfMeasure} may still stand alone with
 * both packaging fields null (a product sold loose). {@code unitPrice} is parsed whenever the
 * cell is present regardless of tenant kind, even though a non-seller tenant's value is
 * discarded downstream (see {@code ProductManagementService.toNewProduct}) rather than stored -
 * mirroring how the manual create/update path accepts and then discards a stale unitPrice from
 * a non-seller caller.
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
        BigDecimal packagingSize) {
}
