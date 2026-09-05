package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One delivery, as the stock-in sheet described it and after column-level validation.
 *
 * <p>Everything here is still spreadsheet-level truth: {@code sku} has not been looked up,
 * {@code vendorName} has not been resolved to a {@code CompanyVendor}, and {@code countedIn} is a
 * valid code from the fixed catalog but has NOT been checked against the specific product's own
 * unit set - that check needs the product, so it belongs to the row handler that has one (see
 * {@link StockInExcelService#unitNotStockedMessage} for the sentence it should say when it fails).
 * Keeping the two apart is what lets this class be a pure function of the file.
 *
 * <p>{@code productName} is deliberately absent. The column exists on the sheet so a human can
 * read the row, and is ignored on the way back in: honouring it would mean a user who tidied up a
 * product's name in the spreadsheet had silently renamed it in the catalog, or - worse - that a
 * mismatch between name and SKU became an error on a column they were told not to touch.
 * {@code how_you_count_it} is absent for the same reason and one more: it is a statement the
 * server made to the user, not an answer the user gave back.
 *
 * <h2>No packagingSize, on purpose</h2>
 * UNIT_UX_CONTRACT.md section 5.2 removes {@code packaging_size} from this sheet, and
 * UNIT_UX_REMEDIATION_PLAN.md P3-3 gives the reason: a delivery row must not be able to redefine a
 * stored product or supplier attribute. Odoo does not let a receipt line redefine the product's
 * unit of measure for exactly this reason. An old saved template still has the column, and it is
 * still read - accepted, ignored, and turned into one warning per affected row (see
 * {@link ParsedStockInSheet#warnings}), never an error.
 *
 * @param quantity always positive. A blank or zero quantity never produces one of these rows at
 *     all; it is a silent skip (BULK_IMPORT_CONTRACT.md section 8, non-negotiable 11), which is
 *     what makes a 400-row pre-filled sheet usable for a 12-row delivery.
 * @param countedIn the {@code counted_in} column, resolved to a {@code UnitOfMeasure} code. Null
 *     means "the product's own stock unit", which is what an empty cell has always meant to
 *     {@code StockInRequest} and what contract section 3.1 keeps it meaning.
 * @param costPerUnit the {@code cost_per_unit} column, <b>per whatever {@code countedIn} says</b> -
 *     contract section 3.2. It is the caller's job to divide by the row's factor before anything
 *     downstream sees it; nothing here may assume it is already per stock unit.
 * @param receivedDate null means today, resolved downstream rather than here so that a file
 *     parsed at 23:59 and committed at 00:01 does not disagree with itself.
 */
public record ParsedStockInRow(
        int excelRow,
        String sku,
        String vendorName,
        int quantity,
        String countedIn,
        BigDecimal costPerUnit,
        LocalDate receivedDate,
        String waybillOrInvoiceNo) {
}
