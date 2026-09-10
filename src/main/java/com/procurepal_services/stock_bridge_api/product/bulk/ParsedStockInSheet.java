package com.procurepal_services.stock_bridge_api.product.bulk;

import java.util.List;

/**
 * The whole result of reading a stock-in sheet: the deliveries to record, the rows deliberately
 * passed over, and the things worth saying that are not worth stopping for.
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
 * <h2>Why warnings exist at all now</h2>
 * UNIT_UX_CONTRACT.md section 5.2 removed {@code packaging_size} from the sheet, and every tenant
 * holding a saved copy of the old template still has that column filled in. Erroring would break
 * non-negotiable 8 (an old saved template must still parse); saying nothing would be worse, because
 * the user typed a number into a column and we ignored it. A warning is the only honest third
 * answer: the file imports, and the review screen tells them what happened to the column they
 * filled in.
 *
 * @param skippedExcelRows 1-based row numbers, in file order, of rows whose quantity was blank or
 *     zero.
 * @param warnings row-level notes that do NOT stop the import, reusing {@link ProductRowError}'s
 *     shape because a warning and an error address the same cell in the same words - the only
 *     difference is what the caller does about it. Empty for a sheet downloaded today.
 */
public record ParsedStockInSheet(
        List<ParsedStockInRow> rows, List<Integer> skippedExcelRows, List<ProductRowError> warnings) {

    public ParsedStockInSheet {
        rows = List.copyOf(rows);
        skippedExcelRows = List.copyOf(skippedExcelRows);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
