package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One product's worth of pre-filled stock-in row - everything the sheet can answer on the user's
 * behalf, resolved by the caller so {@link StockInExcelService} stays free of repositories.
 *
 * <h2>Why every field but quantity is filled in</h2>
 * BULK_IMPORT_DESIGN.md section 5.3: the template is not a blank sheet with a SKU column, it is
 * <em>their catalog</em>, and the user fills exactly one column. Each pre-filled value is one
 * fewer decision on a screen that may have four hundred rows on it, and - more importantly - one
 * fewer chance to enter something the server then has to reject. A pre-filled {@code counted_in}
 * is also what makes BULK_IMPORT_DESIGN.md section 8.3's decision affordable: no per-row dependent
 * dropdowns, because the right answer is already sitting in the cell.
 *
 * <h2>The row carries a unit SET, not a unit</h2>
 * This is the change UNIT_UX_CONTRACT.md section 5.2 turns on, and it is the fix for the reported
 * complaint. The row used to carry one {@code unit} string, pre-filled with
 * {@code packagingUnit ?? unitOfMeasure} - so the same column meant "pack" on one row and "stock
 * unit" on the next, with nothing on the sheet saying which (UNIT_UX_REMEDIATION_PLAN.md P3-2).
 * Now it carries the whole of section 2.1's set, which lets the sheet write two cells instead of
 * one: {@code how_you_count_it} states every valid answer, and {@code counted_in} pre-fills the
 * likeliest of them. One of those two is the question, and the other is now visible beside it.
 *
 * @param productId not written to the sheet - a product UUID must never appear in a spreadsheet a
 *     person reads (BULK_IMPORT_DESIGN.md section 5.3), and SKU is the key. Carried only so a
 *     caller assembling these rows can key its own lookups without a second pass.
 * @param sku the row's identity, and a locked reference column on the sheet.
 * @param productName reference only, ignored on parse. It exists so the row is readable - a sheet
 *     of four hundred bare SKUs is not something a human can check.
 * @param vendorName the product's preferred supplier, or null when it has none yet.
 * @param unitOptions the product's unit set, built by {@link SheetUnitOptions#forRow} - which
 *     delegates to {@code UnitOptions}, the one implementation of contract section 2.1's
 *     algorithm. Never empty: a product with no stock unit configured still gets the
 *     single-entry "units" set section 2.1's last paragraph describes.
 * @param costPerStockUnit the preferred vendor line's {@code lastCostPrice}, falling back to the
 *     product's own cost price - <b>per stock unit</b>, per contract section 3.2, and converted
 *     into the pre-filled {@code counted_in}'s terms by {@link StockInExcelService} at the moment
 *     it writes the cell. It is passed in unconverted on purpose: the conversion depends on which
 *     option the sheet decides to pre-fill, and that decision belongs to the class that writes the
 *     sheet, next to the parser that reads it back.
 */
public record StockInTemplateRow(
        UUID productId,
        String sku,
        String productName,
        String vendorName,
        List<UnitOption> unitOptions,
        BigDecimal costPerStockUnit) {

    public StockInTemplateRow {
        unitOptions = unitOptions == null ? List.of() : List.copyOf(unitOptions);
    }
}
