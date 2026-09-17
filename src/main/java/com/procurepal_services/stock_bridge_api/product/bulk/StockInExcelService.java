package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Service;

/**
 * Writes the stock sheet - the tenant's own catalog, pre-filled, so a delivery is recorded by
 * typing one number per line (BULK_IMPORT_CX_PLAN.md task 1.4).
 *
 * <pre>
 * Product            Comes in          How many arrived  Price paid for one (₦)  Last price paid (₦)  Supplier       Your code  Date (if different)  Ref
 * ⓘ Your products…   One row per way…  THE ONLY COLUMN…  For ONE of…             For reference.       Your usual…    …          …                    …
 * Rice (Mama Gold)   Bag · 50 kg                                                 42,000.00            Tony Stores    RICE-50                         (hidden)
 * Rice (Mama Gold)   Loose · kg                                                  840.00               Tony Stores    RICE-50                         (hidden)
 * </pre>
 *
 * <h2>What the sheet no longer asks</h2>
 * <ul>
 *   <li><b>Which unit.</b> Each way of buying has its own row ({@link StockInTemplateRow}), so
 *       "Comes in" is already right; nobody copies a phrase from one cell into another.</li>
 *   <li><b>Which product, by code.</b> The hidden Ref column is the key ({@link ProductRefs}); the
 *       name and code are there for people and can be edited without breaking anything.</li>
 *   <li><b>The price, pre-filled.</b> "Price paid for one" starts blank, with the last price
 *       beside it. A blank price uses the last price, and the review screen says how many rows
 *       did - a stale figure is no longer accepted silently just because it was already there.</li>
 *   <li><b>The date and invoice on every row.</b> A delivery has one of each; they are asked once,
 *       on the upload screen. "Date (if different)" covers a sheet holding several days.</li>
 * </ul>
 *
 * <h2>Instructions people can see</h2>
 * A help tab opens first, and a grey guidance row sits under the headers. Both are skipped on
 * read ({@code TemplateConventions}). Cell comments are gone: phone spreadsheet apps never show
 * them.
 */
@Service
public class StockInExcelService {

    static final String SHEET_NAME = "Delivery";

    static final String PRODUCT = "Product";
    static final String COMES_IN = "Comes in";
    static final String QUANTITY = "How many arrived";
    static final String PRICE_PAID = "Price paid for one (₦)";
    static final String LAST_PRICE = "Last price paid (₦)";
    static final String SUPPLIER = "Supplier";
    static final String YOUR_CODE = "Your code";
    static final String DATE_IF_DIFFERENT = "Date (if different)";
    static final String REF = "Ref";

    static final List<String> HEADERS = List.of(
            PRODUCT, COMES_IN, QUANTITY, PRICE_PAID, LAST_PRICE, SUPPLIER, YOUR_CODE, DATE_IF_DIFFERENT, REF);

    private static final Map<String, String> GUIDANCE = Map.of(
            PRODUCT, "Your products, already listed. Something arrived that isn't here? Add a row at the bottom.",
            COMES_IN, "One row per way you buy it. Loose = weighed or measured.",
            QUANTITY, "THE ONLY COLUMN YOU NEED. Leave blank if none came. 2.5 is fine.",
            PRICE_PAID, "For ONE of \"Comes in\" - per bag on a bag row. Blank = same as last price.",
            LAST_PRICE, "For reference.",
            SUPPLIER, "Your usual supplier. Change it if this came from someone else.",
            YOUR_CODE, "For reference.",
            DATE_IF_DIFFERENT, "Only if this line arrived on a different day from the rest.",
            REF, "Leave alone - it's how we recognise the product.");

    private static final Map<String, Integer> WIDTHS = Map.of(
            PRODUCT, 32, COMES_IN, 20, QUANTITY, 16, PRICE_PAID, 18, LAST_PRICE, 16,
            SUPPLIER, 22, YOUR_CODE, 16, DATE_IF_DIFFERENT, 16, REF, 12);

    static final String HELP_TITLE = "Record a delivery";

    static final List<String> HELP_LINES = List.of(
            "Type how many arrived next to each product. That's it.",
            "",
            "1.  Find the product - use the filter arrows on 'Product' or 'Supplier'.",
            "2.  Type the number in the yellow column, on the row that matches how it came: Bag, Basket, Loose...",
            "3.  Paid a different price this time? Put it in 'Price paid for one'. Otherwise leave it - we use "
                    + "the last price and show you before anything is saved.",
            "4.  Upload it. You tell us the delivery date and invoice number on the upload screen, once - not on "
                    + "every row.",
            "",
            "Rows you leave blank are ignored.",
            "Something arrived that isn't listed? Add a row at the bottom with its name - we'll help you set it up.",
            "Only a few items? You don't need a spreadsheet: record the delivery straight in the app.");

    public byte[] generateTemplate(List<StockInTemplateRow> rows) {
        try (WorkbookBuilder builder = new WorkbookBuilder(SHEET_NAME)) {
            builder.writeHeaderRow(HEADERS, WIDTHS, Map.of());
            builder.writeGuidanceRow(HEADERS.stream().map(GUIDANCE::get).toList());
            builder.formatColumn(HEADERS.indexOf(QUANTITY), builder.primaryInputStyle());
            builder.formatColumn(HEADERS.indexOf(PRICE_PAID), builder.moneyStyle());
            builder.formatColumn(HEADERS.indexOf(DATE_IF_DIFFERENT), builder.dateStyle());

            int rowIndex = 2;
            for (StockInTemplateRow row : rows) {
                writeRow(builder, rowIndex++, row);
            }
            builder.hideColumn(HEADERS.indexOf(REF));
            builder.addHelpSheet(HELP_TITLE, HELP_LINES);
            return builder.toBytes();
        }
    }

    private void writeRow(WorkbookBuilder builder, int rowIndex, StockInTemplateRow source) {
        Row row = builder.sheet().createRow(rowIndex);
        CellStyle reference = builder.referenceStyle();

        row.createCell(HEADERS.indexOf(PRODUCT)).setCellValue(source.productName() == null ? "" : source.productName());
        Cell comesIn = row.createCell(HEADERS.indexOf(COMES_IN));
        comesIn.setCellValue(SheetUnitOptions.comesInLabel(source.option()));
        comesIn.setCellStyle(reference);
        // The quantity and price cells are left uncreated: the column style already gives a typed
        // value its format and highlight.
        if (source.lastPricePerOption() != null) {
            Cell last = row.createCell(HEADERS.indexOf(LAST_PRICE));
            last.setCellValue(source.lastPricePerOption().doubleValue());
            last.setCellStyle(builder.moneyStyle());
        }
        if (source.vendorName() != null) {
            row.createCell(HEADERS.indexOf(SUPPLIER)).setCellValue(source.vendorName());
        }
        if (source.sku() != null) {
            Cell code = row.createCell(HEADERS.indexOf(YOUR_CODE));
            code.setCellValue(source.sku());
            code.setCellStyle(reference);
        }
        row.createCell(HEADERS.indexOf(REF)).setCellValue(ProductRefs.encode(source.productId()));
    }

    /**
     * The sentence a row handler uses when a "Comes in" cell names a real unit this product is
     * not bought in - "Rice 50kg is counted in kg or bags of 50 kg - we don't know how to count it
     * in cartons." Names the product and the valid answers, never the column, and echoes what was
     * typed so the person can find the row.
     *
     * @param optionLabels the product's ways of counting, mid-sentence, own ways first.
     * @param enteredLabel what the cell said. Null or blank drops the second clause.
     */
    public static String unitNotStockedMessage(String productName, List<String> optionLabels, String enteredLabel) {
        String valid = ImportCopy.orList(optionLabels);
        String sentence = productName + " is counted in " + valid;
        if (enteredLabel == null || enteredLabel.isBlank()) {
            return sentence + ".";
        }
        return sentence + " - we don't know how to count it in " + enteredLabel.trim() + ".";
    }
}
