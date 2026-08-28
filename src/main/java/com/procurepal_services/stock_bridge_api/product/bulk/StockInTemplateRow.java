package com.procurepal_services.stock_bridge_api.product.bulk;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product's worth of pre-filled stock-in row - everything the sheet can answer on the user's
 * behalf, resolved by the caller so {@link StockInExcelService} stays free of repositories.
 *
 * <h2>Why every field but quantity is filled in</h2>
 * BULK_IMPORT_DESIGN.md section 5.3: the template is not a blank sheet with a SKU column, it is
 * <em>their catalog</em>, and the user fills exactly one column. Each pre-filled value is one
 * fewer decision on a screen that may have four hundred rows on it, and - more importantly - one
 * fewer chance to enter something the server then has to reject. A pre-filled {@code unit} is
 * also what makes section 8.3's decision affordable: no per-row dependent dropdowns, because the
 * right answer is already sitting in the cell.
 *
 * @param productId not written to the sheet - a product UUID must never appear in a spreadsheet a
 *     person reads (section 5.3), and SKU is the key. Carried only so a caller assembling these
 *     rows can key its own lookups without a second pass.
 * @param sku the row's identity, and a locked reference column on the sheet.
 * @param productName reference only, ignored on parse. It exists so the row is readable - a sheet
 *     of four hundred bare SKUs is not something a human can check.
 * @param vendorName the product's preferred supplier, or null when it has none yet.
 * @param unit the product's packaging unit if it has one, else its base unit - the unit a delivery
 *     of this product is most likely to be counted in ("three bags", not "150 kg").
 * @param unitCost the preferred vendor line's {@code lastCostPrice}, which is what this delivery
 *     will most likely have cost too.
 * @param packagingSize the vendor line's default, snapshotted onto the movement so a later change
 *     to the vendor's default cannot rewrite what this delivery said.
 */
public record StockInTemplateRow(
        UUID productId,
        String sku,
        String productName,
        String vendorName,
        String unit,
        BigDecimal unitCost,
        BigDecimal packagingSize) {
}
