package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.imports.io.LookupSheetWriter;
import com.procurepal_services.stock_bridge_api.imports.io.NumberValues;
import com.procurepal_services.stock_bridge_api.imports.io.SheetRow;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReadException;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * The bulk stock-in sheet: generating it pre-filled from the tenant's catalog, and reading it back.
 * The stock-in counterpart to {@link ProductExcelService}, and it owns the STOCK_IN column layout
 * for the same reason that class owns the PRODUCT_CATALOG one - template, pre-fill and parse in one
 * place cannot drift apart.
 *
 * <h2>The template IS their catalog - the central design decision</h2>
 * BULK_IMPORT_DESIGN.md section 5.3 argues this at length and it is worth restating, because it is
 * what separates this feature from every "download a blank template" import: a blank sheet with a
 * SKU column asks the user to retype identifiers they already gave us, in a system that already
 * knows them, and every retyped SKU is a chance to mistype one. So instead we ship their products,
 * pre-filled - sku, name, their preferred supplier, the unit they buy it in, what they last paid,
 * the pack size, today's date - and they fill <b>one</b> column:
 *
 * <pre>
 * sku       product_name       vendor_name           quantity  unit   unit_cost
 * RICE-50   Rice 50kg          Dangote Nigeria Plc      ___    BAG      42000
 * GARRI-25  Garri 25kg         Dangote Nigeria Plc      ___    BAG      18500
 * OIL-5L    Groundnut Oil 5L   Ade Foods Ltd            ___    CARTON   26000
 * </pre>
 *
 * <h2>Blank quantity is a silent skip, and that is what makes it usable</h2>
 * A tenant with four hundred products gets four hundred rows, and a delivery is twelve of them.
 * If a blank quantity were an error, the sheet would be unusable for its own primary case, and the
 * user's first act would be deleting 388 rows by hand. So: blank (or zero) quantity, row skipped,
 * nothing said about it - BULK_IMPORT_CONTRACT.md section 8, non-negotiable 11. The skipped rows
 * are still reported ({@link ParsedStockInSheet}) so the confirm screen and the result report can
 * account for the whole file; they are simply never a problem the user is asked to solve.
 *
 * <h2>No per-row dependent dropdowns</h2>
 * Each row's {@code unit} could, in Excel alone, be a dropdown filtered to that product's own two
 * configured units, via {@code INDIRECT} over a named range per product. Section 8.3 says don't:
 * {@code INDIRECT} breaks in Google Sheets, Numbers and LibreOffice - so the feature would work for
 * some users and produce an empty dropdown for others, which is worse than not having it. Instead:
 * the right unit is already in the cell, the column carries a flat dropdown of every valid code for
 * the rare row that needs changing, and the server checks the value against that specific product's
 * units and says which two are valid when it does not match (see {@link #unitNotStockedMessage}).
 */
@Service
@RequiredArgsConstructor
public class StockInExcelService {

    private final SpreadsheetReader spreadsheetReader;

    /**
     * BULK_IMPORT_CONTRACT.md section 5's STOCK_IN field keys, in order - and, as there, the field
     * key and the column header are the same string.
     */
    static final List<String> HEADER_NAMES = List.of(
            "sku", "product_name", "vendor_name", "quantity", "unit", "unit_cost",
            "packaging_size", "received_date", "reference");

    private static final Map<String, Integer> COLUMN_WIDTHS_CHARS_BY_HEADER = Map.ofEntries(
            Map.entry("sku", 18),
            Map.entry("product_name", 32),
            Map.entry("vendor_name", 30),
            Map.entry("quantity", 12),
            Map.entry("unit", 12),
            Map.entry("unit_cost", 14),
            Map.entry("packaging_size", 16),
            Map.entry("received_date", 16),
            Map.entry("reference", 24));

    /**
     * One sentence per column. The quantity comment carries the two facts the whole sheet turns on
     * - it is the only column you have to fill, and leaving a row blank is how you say "not this
     * one".
     */
    private static final Map<String, String> HEADER_COMMENTS = Map.ofEntries(
            Map.entry("sku", "Your product code. Do not change it - it is how we match the row to your product."),
            Map.entry("product_name", "For your reference only. We ignore whatever is in this column."),
            Map.entry("vendor_name", "Who this delivery came from. Already filled with your usual supplier - change it if it was someone else."),
            Map.entry("quantity", "The only column you have to fill. How much of this product arrived. Leave it blank for products you did not receive - those rows are simply ignored."),
            Map.entry("unit", "What the quantity above is counted in. Already set to how you usually buy this product."),
            Map.entry("unit_cost", "What one of the above cost you. Already filled with what you last paid - change it if the price moved."),
            Map.entry("packaging_size", "How many base units are in one pack, if you entered the quantity in packs."),
            Map.entry("received_date", "When the delivery actually arrived. Change it if you are recording an older delivery - we use this to work out which stock was sold first."),
            Map.entry("reference", "Optional. The waybill or invoice number, so you can find this delivery again."));

    private static final String UNITS_RANGE = "stock_in_units";

    private static final String VENDOR_NAMES_RANGE = "vendor_names";

    /**
     * Date spellings accepted on the way in, tried in order. ISO first because that is what the
     * reader emits for any cell Excel stored as a real date - which, since this template formats
     * the column as a date, is the overwhelming majority. The rest are for a column somebody
     * retyped as text.
     *
     * <h2>Day-first, not month-first</h2>
     * {@code 03/04/2026} is 3 April in Nigeria and 4 March in the United States, and no amount of
     * inspection can tell them apart. Day-first wins because that is how this product's users write
     * dates, on invoices, waybills and everything else. The alternative - rejecting every ambiguous
     * date and asking - would put a question on screen for the single most-filled column on the
     * sheet.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ENGLISH));

    /**
     * The plausible range for an Excel date serial typed into a cell whose date format was cleared -
     * 1 Jan 2000 to 31 Dec 2049. Narrow on purpose: it must not swallow a number somebody meant as a
     * year, and no real quantity or reference lands in it either, because this is the date column.
     */
    private static final int EARLIEST_SERIAL = 36526;

    private static final int LATEST_SERIAL = 54788;

    static final String EXAMPLE_SKU_MARKER_PREFIX = ProductExcelService.EXAMPLE_SKU_MARKER_PREFIX;

    /**
     * Generates the pre-filled sheet.
     *
     * @param catalogRows the tenant's products, already resolved and in the order they should
     *     appear. Empty is legitimate - a tenant with no products yet gets the headers, the example
     *     row and the dropdowns, which is exactly the blank template they need.
     * @param vendorNames the tenant's active suppliers for the {@code vendor_name} dropdown, capped
     *     by the caller (see {@code ProductTemplateContext.vendorDropdownNames}). Empty leaves the
     *     column as free text.
     * @param today the date every row is pre-filled with. Passed in rather than read from the clock
     *     here so the generated file is a pure function of its inputs and a test can assert on it.
     */
    public byte[] generateTemplate(List<StockInTemplateRow> catalogRows, List<String> vendorNames, LocalDate today) {
        try (WorkbookBuilder builder = new WorkbookBuilder("Stock in")) {
            builder.writeHeaderRow(HEADER_NAMES, COLUMN_WIDTHS_CHARS_BY_HEADER, HEADER_COMMENTS);
            applyNumberFormats(builder);

            int rowIndex = 1;
            writeExampleRow(builder, rowIndex++, today);
            for (StockInTemplateRow catalogRow : catalogRows) {
                writeCatalogRow(builder, rowIndex++, catalogRow, today);
            }

            LookupSheetWriter lookups = new LookupSheetWriter(builder.workbook());
            // One flat list of every code, base and packaging together - section 8.3. A delivery is
            // legitimately counted in either ("150 kg" or "3 bags"), so filtering this list by role
            // would remove the correct answer for half the rows.
            builder.addDropdown(
                    HEADER_NAMES.indexOf("unit"),
                    lookups.addList(UNITS_RANGE, UnitOfMeasure.all().stream().map(UnitOfMeasure::code).toList()),
                    "Unit",
                    "This is already set to how you usually buy this product. Change it only if this delivery was "
                            + "counted differently - we check it against the units this product is stocked in.");
            builder.addDropdown(
                    HEADER_NAMES.indexOf("vendor_name"),
                    lookups.addList(VENDOR_NAMES_RANGE, vendorNames),
                    "Supplier",
                    "Pick one of your suppliers, or type a new name - we will ask whether to add it after you upload.");
            lookups.hide();

            return builder.toBytes();
        }
    }

    /**
     * Reads a stock-in sheet. Errors come back as a {@link BulkUploadValidationException} carrying
     * every row-level problem at once, the same all-at-once contract the product parser has, so a
     * user fixing a file sees the whole list rather than discovering one problem per upload.
     */
    public ParsedStockInSheet parse(MultipartFile file) {
        SheetTable table;
        try {
            table = spreadsheetReader.read(file);
        } catch (SpreadsheetReadException e) {
            throw new BulkUploadValidationException(List.of(new ProductRowError(0, "file", e.getMessage())));
        } catch (RuntimeException e) {
            throw notASpreadsheet();
        }
        return parse(table);
    }

    /** The already-read overload, for the session engine re-validating stored rows. */
    public ParsedStockInSheet parse(SheetTable table) {
        try {
            List<String> missing =
                    List.of("sku", "quantity").stream().filter(h -> !table.hasColumn(h)).toList();
            if (!missing.isEmpty()) {
                throw new BulkUploadValidationException(missing.stream()
                        .map(header -> new ProductRowError(
                                1, header, "Required column '" + header + "' is missing from the uploaded file"))
                        .toList());
            }

            List<ProductRowError> errors = new ArrayList<>();
            List<ParsedStockInRow> rows = new ArrayList<>();
            List<Integer> skipped = new ArrayList<>();

            for (SheetRow row : table.rows()) {
                int excelRow = row.excelRow();
                String sku = table.value(row, "sku");
                if (sku == null) {
                    // No SKU and no quantity is a leftover formatting row, not a mistake. No SKU
                    // WITH a quantity is a real problem: somebody typed a delivery against nothing.
                    if (table.value(row, "quantity") != null) {
                        errors.add(new ProductRowError(excelRow, "sku", "is required"));
                    }
                    continue;
                }
                if (sku.toUpperCase(Locale.ROOT).startsWith(EXAMPLE_SKU_MARKER_PREFIX)) {
                    continue;
                }

                // The silent skip, and it comes BEFORE every other check on purpose: a row the user
                // did not fill in must not be able to produce an error from any other column, even
                // if the pre-filled unit or date in it is somehow unreadable. Nothing about a row
                // they left alone is their problem.
                Integer quantity = quantityOf(table.value(row, "quantity"), excelRow, errors);
                if (quantity == null) {
                    skipped.add(excelRow);
                    continue;
                }

                ParsedStockInRow parsed = parseRow(table, row, excelRow, sku, quantity, errors);
                if (parsed != null) {
                    rows.add(parsed);
                }
            }

            if (!errors.isEmpty()) {
                throw new BulkUploadValidationException(errors);
            }
            return new ParsedStockInSheet(rows, skipped);
        } catch (BulkUploadValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw notASpreadsheet();
        }
    }

    /**
     * The sentence a row handler should use when a row's unit is a real unit but not one THIS
     * product is stocked in - "Rice 50kg is stocked in KG or BAG", exactly as
     * BULK_IMPORT_DESIGN.md section 8.3 words it. The copy lives here, next to the column and the
     * dropdown it is about, so the file that decides what the column may contain is also the file
     * that explains it.
     *
     * <p>Names the product, never the column: "unit is invalid" is the kind of message section 9.6
     * forbids, because the user is looking at a row about Rice, not at a database field.
     *
     * @param packagingUnit may be null, for a product sold loose - the message then names one unit
     *     instead of two, rather than saying "KG or null".
     */
    public static String unitNotStockedMessage(String productName, String baseUnit, String packagingUnit) {
        String units = packagingUnit == null ? baseUnit : baseUnit + " or " + packagingUnit;
        return productName + " is stocked in " + units + ".";
    }

    private BulkUploadValidationException notASpreadsheet() {
        return new BulkUploadValidationException(
                List.of(new ProductRowError(0, "file", "The uploaded file is not a valid .xlsx file")));
    }

    private ParsedStockInRow parseRow(
            SheetTable table, SheetRow row, int excelRow, String sku, int quantity, List<ProductRowError> errors) {
        int errorsBefore = errors.size();

        String vendorName = table.value(row, "vendor_name");

        // Forgiving on the way in, and role-agnostic: a delivery may be counted in the product's
        // base unit ("150 kg") or its packaging unit ("3 bags"), so both roles are valid here and
        // narrowing to the product's OWN two is the row handler's job - it needs the product.
        String rawUnit = table.value(row, "unit");
        String unit = null;
        if (rawUnit != null) {
            Optional<UnitOfMeasure> resolved = UnitOfMeasure.fromCodeOrLabel(rawUnit);
            if (resolved.isPresent()) {
                unit = resolved.get().code();
            } else {
                errors.add(new ProductRowError(excelRow, "unit", "'" + rawUnit + "' is not a unit we recognise"));
            }
        }

        BigDecimal unitCost = nonNegativeDecimal(table.value(row, "unit_cost"), excelRow, "unit_cost", errors);
        BigDecimal packagingSize =
                nonNegativeDecimal(table.value(row, "packaging_size"), excelRow, "packaging_size", errors);
        LocalDate receivedDate = receivedDateOf(table.value(row, "received_date"), excelRow, errors);
        String reference = table.value(row, "reference");

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new ParsedStockInRow(
                excelRow, sku, vendorName, quantity, unit, unitCost, packagingSize, receivedDate, reference);
    }

    /**
     * The quantity, or null meaning "skip this row silently".
     *
     * <p>Zero is treated as blank, not as an error. Somebody who typed 0 meant "we did not receive
     * any of this", which is the same statement as leaving it empty, and there is no such thing as
     * recording a delivery of nothing. Erroring on it would be the software being right about a
     * technicality and wrong about the person. A NEGATIVE quantity is different and does error: a
     * delivery cannot be negative, and the user probably meant a stock adjustment, which is a
     * different screen.
     */
    private Integer quantityOf(String raw, int excelRow, List<ProductRowError> errors) {
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = NumberValues.parseDecimal(raw);
        if (parsed.isEmpty()) {
            errors.add(new ProductRowError(excelRow, "quantity", "must be a number"));
            return null;
        }
        BigDecimal quantity = parsed.get();
        if (quantity.signum() == 0) {
            return null;
        }
        if (quantity.signum() < 0) {
            errors.add(new ProductRowError(excelRow, "quantity", "cannot be negative - a delivery only adds stock"));
            return null;
        }
        if (quantity.stripTrailingZeros().scale() > 0) {
            errors.add(new ProductRowError(excelRow, "quantity", "must be a whole number"));
            return null;
        }
        if (quantity.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            errors.add(new ProductRowError(excelRow, "quantity", "is too large"));
            return null;
        }
        return quantity.intValue();
    }

    private BigDecimal nonNegativeDecimal(String raw, int excelRow, String column, List<ProductRowError> errors) {
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = NumberValues.parseDecimal(raw);
        if (parsed.isEmpty()) {
            errors.add(new ProductRowError(excelRow, column, "must be a number"));
            return null;
        }
        if (parsed.get().signum() < 0) {
            errors.add(new ProductRowError(excelRow, column, "must be a positive number"));
            return null;
        }
        return parsed.get();
    }

    /** Null means "today", decided downstream - see {@link ParsedStockInRow}. */
    private LocalDate receivedDateOf(String raw, int excelRow, List<ProductRowError> errors) {
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, format);
            } catch (DateTimeParseException ignored) {
                // Next spelling. Falling through every one of them is what "not a date" means.
            }
        }
        // An Excel date serial that lost its formatting - the classic result of someone clearing
        // formats on the column - is still a date, and rescuing it costs four lines.
        Optional<BigDecimal> serial = NumberValues.parseDecimal(raw);
        if (serial.isPresent()
                && serial.get().stripTrailingZeros().scale() <= 0
                && serial.get().intValue() >= EARLIEST_SERIAL
                && serial.get().intValue() <= LATEST_SERIAL) {
            return org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(serial.get().doubleValue())
                    .toLocalDate();
        }
        errors.add(new ProductRowError(
                excelRow, "received_date", "'" + raw + "' is not a date we recognise - try 2026-01-31 or 31/01/2026"));
        return null;
    }

    /**
     * One greyed example row above the catalog, using the same reserved-SKU marker the product
     * template uses so it is auto-skipped whether or not the user deletes it. On a pre-filled sheet
     * its job is different from the product template's: it is not showing the format (the four
     * hundred rows below it do that), it is showing what a FILLED row looks like - which is the one
     * thing the pre-filled rows cannot demonstrate, because their quantity is empty by definition.
     */
    private void writeExampleRow(WorkbookBuilder builder, int rowIndex, LocalDate today) {
        Row row = builder.sheet().createRow(rowIndex);
        Map<String, String> values = Map.of(
                "sku", EXAMPLE_SKU_MARKER_PREFIX + "-1",
                "product_name", "Example row - delete it or leave it, we skip it either way",
                "vendor_name", "",
                "quantity", "3",
                "unit", "BAG",
                "unit_cost", "42000",
                "received_date", today.toString(),
                "reference", "WB-00123");
        for (int i = 0; i < HEADER_NAMES.size(); i++) {
            Cell cell = row.createCell(i);
            String value = values.get(HEADER_NAMES.get(i));
            if (value != null && !value.isEmpty()) {
                cell.setCellValue(value);
            }
            cell.setCellStyle(builder.exampleStyle());
        }
    }

    /**
     * One product's pre-filled row. {@code sku} and {@code product_name} carry the locked-reference
     * styling - greyed, visibly not for editing. Styling is the only lock applied: real sheet
     * protection would also stop the user deleting rows they do not need, which is a thing they
     * legitimately want to do on a four-hundred-row sheet.
     */
    private void writeCatalogRow(WorkbookBuilder builder, int rowIndex, StockInTemplateRow source, LocalDate today) {
        Sheet sheet = builder.sheet();
        Row row = sheet.createRow(rowIndex);
        CellStyle reference = builder.referenceStyle();

        Cell skuCell = row.createCell(HEADER_NAMES.indexOf("sku"));
        skuCell.setCellValue(source.sku());
        skuCell.setCellStyle(reference);

        Cell nameCell = row.createCell(HEADER_NAMES.indexOf("product_name"));
        nameCell.setCellValue(source.productName() == null ? "" : source.productName());
        nameCell.setCellStyle(reference);

        if (source.vendorName() != null) {
            row.createCell(HEADER_NAMES.indexOf("vendor_name")).setCellValue(source.vendorName());
        }
        if (source.unit() != null) {
            row.createCell(HEADER_NAMES.indexOf("unit")).setCellValue(source.unit());
        }
        if (source.unitCost() != null) {
            Cell costCell = row.createCell(HEADER_NAMES.indexOf("unit_cost"));
            costCell.setCellValue(source.unitCost().doubleValue());
            costCell.setCellStyle(builder.moneyStyle());
        }
        if (source.packagingSize() != null) {
            Cell sizeCell = row.createCell(HEADER_NAMES.indexOf("packaging_size"));
            sizeCell.setCellValue(source.packagingSize().doubleValue());
            sizeCell.setCellStyle(builder.decimalStyle());
        }
        Cell dateCell = row.createCell(HEADER_NAMES.indexOf("received_date"));
        dateCell.setCellValue(today);
        dateCell.setCellStyle(builder.dateStyle());

        // quantity is left with no cell at all, not an empty one: the column's default style
        // already gives a typed value its number format and its highlight, and a pre-created blank
        // cell would be one more thing for the user's cursor to land in.
    }

    private void applyNumberFormats(WorkbookBuilder builder) {
        for (int i = 0; i < HEADER_NAMES.size(); i++) {
            switch (HEADER_NAMES.get(i)) {
                case "quantity" -> builder.formatColumn(i, builder.primaryInputStyle());
                case "unit_cost" -> builder.formatColumn(i, builder.moneyStyle());
                case "packaging_size" -> builder.formatColumn(i, builder.decimalStyle());
                case "received_date" -> builder.formatColumn(i, builder.dateStyle());
                default -> {
                    // sku, product_name, vendor_name, unit and reference are text. sku especially:
                    // a number format would turn 00123 into 123.
                }
            }
        }
    }
}
