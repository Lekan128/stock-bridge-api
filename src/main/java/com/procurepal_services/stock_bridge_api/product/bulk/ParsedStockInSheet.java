package com.procurepal_services.stock_bridge_api.product.bulk;

import java.util.List;

/**
 * The whole result of reading a stock-in sheet: the deliveries to record, and the rows deliberately
 * passed over.
 *
 * <h2>Why the skipped rows are returned rather than simply dropped</h2>
 * A blank {@code quantity} is a silent skip, never an error - that is the rule that makes a
 * pre-filled catalog of four hundred products usable for a delivery of twelve, and it is
 * non-negotiable 11 of BULK_IMPORT_CONTRACT.md section 8. "Silent" means the user is never asked
 * to fix anything. It does not mean the rows vanish without trace: the confirm screen says "12
 * deliveries, 388 rows left blank", the committed session stores them as {@code SKIPPED} so the
 * result report can account for every row in the file, and a user who expected 13 can see that
 * one of them never had a number in it. Returning the row numbers is what makes all of that
 * possible without a second pass over the file.
 *
 * @param skippedExcelRows 1-based row numbers, in file order, of rows whose quantity was blank or
 *     zero.
 */
public record ParsedStockInSheet(List<ParsedStockInRow> rows, List<Integer> skippedExcelRows) {

    public ParsedStockInSheet {
        rows = List.copyOf(rows);
        skippedExcelRows = List.copyOf(skippedExcelRows);
    }
}
