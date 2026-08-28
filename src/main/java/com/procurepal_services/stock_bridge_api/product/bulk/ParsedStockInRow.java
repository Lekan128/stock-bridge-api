package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One delivery, as the stock-in sheet described it and after column-level validation.
 *
 * <p>Everything here is still spreadsheet-level truth: {@code sku} has not been looked up,
 * {@code vendorName} has not been resolved to a {@code CompanyVendor}, and {@code unit} is a valid
 * code from the fixed catalog but has NOT been checked against the specific product's own
 * configured units - that check needs the product, so it belongs to the row handler that has one
 * (see {@link StockInExcelService#unitNotStockedMessage} for the sentence it should say when it
 * fails). Keeping the two apart is what lets this class be a pure function of the file.
 *
 * <p>{@code productName} is deliberately absent. The column exists on the sheet so a human can
 * read the row, and is ignored on the way back in: honouring it would mean a user who tidied up a
 * product's name in the spreadsheet had silently renamed it in the catalog, or - worse - that a
 * mismatch between name and SKU became an error on a column they were told not to touch.
 *
 * @param quantity always positive. A blank or zero quantity never produces one of these rows at
 *     all; it is a silent skip (BULK_IMPORT_CONTRACT.md section 8, non-negotiable 11), which is
 *     what makes a 400-row pre-filled sheet usable for a 12-row delivery.
 * @param unit null means "the product's own base unit", which is what an empty cell has always
 *     meant to {@code StockInRequest}.
 * @param receivedDate null means today, resolved downstream rather than here so that a file
 *     parsed at 23:59 and committed at 00:01 does not disagree with itself.
 */
public record ParsedStockInRow(
        int excelRow,
        String sku,
        String vendorName,
        int quantity,
        String unit,
        BigDecimal unitCost,
        BigDecimal packagingSize,
        LocalDate receivedDate,
        String reference) {
}
