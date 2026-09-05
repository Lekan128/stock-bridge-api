package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.io.NumberValues;
import com.procurepal_services.stock_bridge_api.imports.io.SheetRow;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReadException;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * pre-filled - sku, name, how the product can be counted, their preferred supplier, the way they
 * usually buy it, what they last paid, today's date - and they fill <b>one</b> column:
 *
 * <pre>
 * sku       product_name       how_you_count_it       vendor_name       quantity  counted_in     cost_per_unit
 * RICE-50   Rice 50kg          kg · or Bag of 50 kg   Dangote Nig Plc      ___    Bag of 50 kg       42000
 * GARRI-25  Garri 25kg         kg · or Bag of 25 kg   Dangote Nig Plc      ___    Bag of 25 kg       18500
 * OIL-5L    Groundnut Oil 5L   L · or Carton of 4 L   Ade Foods Ltd        ___    Carton of 4 L      26000
 * </pre>
 *
 * <h2>{@code how_you_count_it} - the column that fixes the reported complaint</h2>
 * The sheet used to have one {@code unit} column pre-filled with the product's packaging unit if it
 * had one and its base unit otherwise, so the same column meant "pack" on one row and "stock unit"
 * on the next, with nothing on the sheet saying which - and a {@code packaging_size} column defined
 * as "how many base units are in one pack" on a sheet that never showed the base unit
 * (UNIT_UX_REMEDIATION_PLAN.md P3-2 and P3-3). It asked a question whose valid answers it did not
 * show, which is exactly what UNIT_UX_CONTRACT.md section 7's non-negotiable 4 forbids.
 *
 * <p>Section 5.2's answer is one read-only column, on the row, immediately left of the two cells it
 * is about: <b>"kg · or Bag of 50 kg"</b>. It is not help text and it is not a comment - it is a
 * value, on the same line, that a person reads in the same glance as the cell they are filling.
 * {@code counted_in} then pre-fills with one of those exact strings, so in the common case the
 * answer is already there and in the rare case it is copyable from two cells away.
 *
 * <h2>Labels, never codes, in both directions</h2>
 * Every pre-filled cell and every dropdown entry is a human label - {@code "Bag of 50 kg"},
 * {@code "kg"}, never {@code "BAG"} or {@code "KG"} (contract section 5, non-negotiable 4). Reading
 * is correspondingly generous: {@link SheetUnitOptions#resolve} takes the composed pack label, the
 * plain symbol, the display label, the raw code and every trade alias
 * {@code UnitOfMeasure.fromCodeOrLabel} already understood.
 *
 * <h2>Old saved templates keep working, forever</h2>
 * Contract section 7, non-negotiable 8, and the same stability promise {@link ProductExcelService}'s
 * javadoc has always made. {@code unit} and {@code unit_cost} are permanent read aliases for
 * {@code counted_in} and {@code cost_per_unit} - see {@link #HEADER_ALIASES}. {@code packaging_size}
 * is read, ignored, and warned about, never errored. A sheet downloaded a year ago and filled in
 * today still imports, and the only thing the user hears about it is one sentence explaining where
 * the pack now comes from.
 *
 * <h2>Blank quantity is a silent skip, and that is what makes it usable</h2>
 * A tenant with four hundred products gets four hundred rows, and a delivery is twelve of them.
 * If a blank quantity were an error, the sheet would be unusable for its own primary case, and the
 * user's first act would be deleting 388 rows by hand. So: blank (or zero) quantity, row skipped,
 * nothing said about it - BULK_IMPORT_CONTRACT.md section 8, non-negotiable 11. The skipped rows
 * are still reported ({@link ParsedStockInSheet}) so the confirm screen and the result report can
 * account for the whole file; they are simply never a problem the user is asked to solve.
 *
 * <h2>No dropdown at all on {@code counted_in} or {@code vendor_name}</h2>
 * Each row's {@code counted_in} could, in Excel alone, be a dropdown filtered to that product's own
 * unit set, via {@code INDIRECT} over a named range per product. BULK_IMPORT_DESIGN.md section 8.3
 * says don't: {@code INDIRECT} breaks in Google Sheets, Numbers and LibreOffice - so the feature
 * would work for some users and produce an empty dropdown for others, which is worse than not
 * having it. A flat, product-agnostic dropdown was tried next, and failed the same test in a
 * subtler way: those same apps commonly enforce any list-validated cell as a hard picker regardless
 * of the dismissible {@code WARNING} error style {@code WorkbookBuilder.addDropdown} deliberately
 * sets, so a user on LibreOffice, Numbers or WPS could not type a value the list did not already
 * contain - which is exactly the escape hatch MULTI_PACK_PER_VENDOR_DESIGN.md section 6a's new-pack
 * flow and the {@code vendor_name} "or type a new name" comment both depend on. Both columns
 * therefore carry no data validation at all: the right answer is already in the cell, the row's
 * other answers are in {@code how_you_count_it} beside it, and the server checks the value against
 * that specific product's set and names the valid ones when it does not match (see
 * {@link #unitNotStockedMessage}).
 */
@Service
@RequiredArgsConstructor
public class StockInExcelService {

    private final SpreadsheetReader spreadsheetReader;

    /**
     * UNIT_UX_CONTRACT.md section 5.2's STOCK_IN column set, in order - and, as before, the field
     * key and the column header are the same string.
     *
     * <p>Four changes from the set BULK_IMPORT_CONTRACT.md section 5 froze: {@code how_you_count_it}
     * is new, {@code unit}/{@code unit_cost} are renamed to {@code counted_in}/{@code cost_per_unit},
     * {@code packaging_size} is gone, and {@code reference} is renamed to
     * {@code waybill_or_invoice_no} - a column asked "what kind of note is this" and answered
     * "optional", which told nobody what to type without opening its comment. The first three
     * predate this feature's production release and so each carries a permanent read alias
     * ({@link #HEADER_ALIASES}) so no saved copy of the old sheet stops working; the last does not,
     * because nothing has shipped against {@code reference} yet.
     */
    static final List<String> HEADER_NAMES = List.of(
            "sku", "product_name", "how_you_count_it", "vendor_name", "quantity", "counted_in",
            "cost_per_unit", "received_date", "waybill_or_invoice_no");

    /**
     * Headers this parser still answers to, and always will: the old spelling on the left, the
     * column it now means on the right.
     *
     * <p>Contract section 7, non-negotiable 8. Tenants hold saved copies of every template we have
     * ever published, and a rename that breaks them is a rename that costs a customer a morning.
     * These entries are not deprecated and are not scheduled for removal - the cost of keeping them
     * is two map lookups, and the cost of dropping them lands entirely on the user.
     *
     * <p>{@code packaging_size} is deliberately NOT here. It is not a renamed column, it is a
     * removed one, and mapping it onto something would be inventing a meaning for a number the
     * contract says to ignore. It is handled separately, in {@link #warnOnIgnoredPackagingSize}.
     */
    static final Map<String, String> HEADER_ALIASES = Map.of(
            "unit", "counted_in",
            "unit_cost", "cost_per_unit");

    /** The removed column, still read so that it can be explicitly ignored rather than silently. */
    static final String IGNORED_PACKAGING_SIZE_HEADER = "packaging_size";

    private static final Map<String, Integer> COLUMN_WIDTHS_CHARS_BY_HEADER = Map.ofEntries(
            Map.entry("sku", 18),
            Map.entry("product_name", 32),
            Map.entry("how_you_count_it", 28),
            Map.entry("vendor_name", 30),
            Map.entry("quantity", 12),
            Map.entry("counted_in", 18),
            Map.entry("cost_per_unit", 16),
            Map.entry("received_date", 16),
            Map.entry("waybill_or_invoice_no", 26));

    /**
     * One sentence per column, in the user's words, at the column it is about - the same rule the
     * catalog sheet follows and the same rule Odoo's, Zoho's and NetSuite's import templates
     * follow.
     *
     * <p>Two of them carry the weight. {@code quantity}'s says the two things the whole sheet turns
     * on - it is the only column you have to fill, and leaving a row blank is how you say "not this
     * one". {@code cost_per_unit}'s states what the number is <em>per</em>, in the row's own terms,
     * which is contract section 7's non-negotiable 2 and the half of the price story that did not
     * exist before (UNIT_UX_REMEDIATION_PLAN.md P0-1).
     *
     * <p>No column here is described in terms of a value the sheet does not show. That was the
     * defect in the old {@code packaging_size} comment - "how many base units are in one pack", on
     * a sheet with no base unit column anywhere - and it is why {@code counted_in} and
     * {@code cost_per_unit} both point at {@code how_you_count_it}, which is right there.
     */
    private static final Map<String, String> HEADER_COMMENTS = Map.ofEntries(
            Map.entry("sku", "Your product code. Do not change it - it is how we match the row to your product."),
            Map.entry("product_name", "For your reference only. We ignore whatever is in this column."),
            Map.entry("how_you_count_it", "For your reference: the ways you can count this product. Copy one of them into 'counted in'. Other sizes of the same measure work too - g or t for a product counted in kg."),
            Map.entry("vendor_name", "Who this delivery came from. Already filled with your usual supplier - change it if it was someone else, or type a new supplier's name."),
            Map.entry("quantity", "The only column you have to fill. How much of this product arrived. Leave it blank for products you did not receive - those rows are simply ignored."),
            Map.entry("counted_in", "Which of the ways in 'how you count it' the number beside it is in. Already set to how you usually buy this product. If this delivery came in a pack you don't see there, type its size instead - e.g. \"100 kg\" or \"Jumbo bag of 100 kg\"."),
            Map.entry("cost_per_unit", "Per whatever this row's 'counted in' says - per bag if it says Bag, per kg if it says kg. Already filled with what you last paid."),
            Map.entry("received_date", "When the delivery actually arrived. Change it if you are recording an older delivery - we use this to work out which stock was sold first."),
            Map.entry("waybill_or_invoice_no", "Optional. So you can find this delivery again later."));

    /**
     * The one sentence a row filled in on an old saved template hears about its {@code
     * packaging_size} column - contract section 5.2, worded there and copied here because the copy
     * belongs next to the column it is about.
     *
     * <p>It says three things in two clauses: where the pack comes from now, that the number was
     * not used, and what to do if this delivery genuinely was packed differently. The last clause
     * matters - "we ignored your column" with no way forward is the kind of message that makes
     * somebody re-upload the same file.
     */
    public static final String PACKAGING_SIZE_IGNORED_WARNING =
            "We now take the pack from your product setup, so this column is ignored. Tell us on the "
                    + "review screen if this delivery came in a different pack.";

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

    /** Money is written to the sheet at the scale money is read at, so a round trip does not drift. */
    private static final int MONEY_SCALE = 2;

    static final String EXAMPLE_SKU_MARKER_PREFIX = ProductExcelService.EXAMPLE_SKU_MARKER_PREFIX;

    /**
     * Generates the pre-filled sheet.
     *
     * @param catalogRows the tenant's products, already resolved and in the order they should
     *     appear. Empty is legitimate - a tenant with no products yet gets the headers and the
     *     example row, which is exactly the blank template they need.
     * @param today the date every row is pre-filled with. Passed in rather than read from the clock
     *     here so the generated file is a pure function of its inputs and a test can assert on it.
     */
    public byte[] generateTemplate(List<StockInTemplateRow> catalogRows, LocalDate today) {
        try (WorkbookBuilder builder = new WorkbookBuilder("Stock in")) {
            builder.writeHeaderRow(HEADER_NAMES, COLUMN_WIDTHS_CHARS_BY_HEADER, HEADER_COMMENTS);
            applyNumberFormats(builder);

            int rowIndex = 1;
            writeExampleRow(builder, rowIndex++, today);
            for (StockInTemplateRow catalogRow : catalogRows) {
                writeCatalogRow(builder, rowIndex++, catalogRow, today);
            }

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
            List<ProductRowError> warnings = new ArrayList<>();
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
                // they left alone is their problem - and that includes the ignored-column warning,
                // which would otherwise fire on all four hundred rows of an old saved template.
                Integer quantity = quantityOf(table.value(row, "quantity"), excelRow, errors);
                if (quantity == null) {
                    skipped.add(excelRow);
                    continue;
                }

                warnOnIgnoredPackagingSize(table, row, excelRow, warnings);
                ParsedStockInRow parsed = parseRow(table, row, excelRow, sku, quantity, errors);
                if (parsed != null) {
                    rows.add(parsed);
                }
            }

            if (!errors.isEmpty()) {
                throw new BulkUploadValidationException(errors);
            }
            return new ParsedStockInSheet(rows, skipped, warnings);
        } catch (BulkUploadValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw notASpreadsheet();
        }
    }

    /**
     * The sentence a row handler should use when a row's {@code counted_in} is a real unit but not
     * one THIS product can be counted in - UNIT_UX_CONTRACT.md section 3.1, worded there as
     * <em>"Rice 50kg is counted in kg or Bag of 50 kg - we don't know how to count it in
     * cartons."</em> The copy lives here, next to the column and the dropdown it is about, so the
     * file that decides what the column may contain is also the file that explains it.
     *
     * <p>Names the product and the valid answers, never the column: "counted_in is invalid" is the
     * kind of message BULK_IMPORT_DESIGN.md section 9.6 forbids, because the user is looking at a
     * row about Rice, not at a database field. It also names what they actually typed, because an
     * error that does not quote the input leaves the user hunting for which of four hundred rows it
     * meant.
     *
     * @param optionLabels the product's unit set as labels, from {@link SheetUnitOptions}. The
     *     product's own ways of counting come first, which is the order they are worth reading in.
     * @param enteredLabel what the cell said, echoed back. Null or blank drops the second clause
     *     rather than printing "we don't know how to count it in null".
     */
    public static String unitNotStockedMessage(String productName, List<String> optionLabels, String enteredLabel) {
        String valid = ImportCopy.orList(optionLabels);
        String sentence = productName + " is counted in " + valid;
        if (enteredLabel == null || enteredLabel.isBlank()) {
            return sentence + ".";
        }
        return sentence + " - we don't know how to count it in " + enteredLabel.trim() + ".";
    }

    /**
     * The pre-contract two-unit form, kept so that callers written against it keep compiling and
     * saying the same thing. Prefer {@link #unitNotStockedMessage(String, List, String)}: it names
     * every valid option rather than two, and it echoes what the user typed.
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

    /**
     * Contract section 5.2: {@code packaging_size} is accepted, ignored, and warned about - one
     * warning per affected row, and only when the cell actually has something in it. A column
     * present but empty is a saved template nobody filled in, and telling somebody we ignored a
     * blank cell would be noise.
     */
    private void warnOnIgnoredPackagingSize(
            SheetTable table, SheetRow row, int excelRow, List<ProductRowError> warnings) {
        if (table.value(row, IGNORED_PACKAGING_SIZE_HEADER) != null) {
            warnings.add(
                    new ProductRowError(excelRow, IGNORED_PACKAGING_SIZE_HEADER, PACKAGING_SIZE_IGNORED_WARNING));
        }
    }

    private ParsedStockInRow parseRow(
            SheetTable table, SheetRow row, int excelRow, String sku, int quantity, List<ProductRowError> errors) {
        int errorsBefore = errors.size();

        String vendorName = table.value(row, "vendor_name");

        // Forgiving on the way in, and role-agnostic: a delivery may be counted in the product's
        // stock unit ("150 kg") or a pack ("3 bags"), so both roles are valid here and narrowing to
        // the product's OWN set is the row handler's job - it needs the product. The error names
        // the column the file used, not the one we would have written, so a user reading it can
        // find the column in their own sheet.
        Column countedInColumn = column(table, "counted_in");
        String rawCountedIn = table.value(row, countedInColumn.header());
        String countedIn = null;
        if (rawCountedIn != null) {
            Optional<UnitOfMeasure> resolved = SheetUnitOptions.resolve(rawCountedIn);
            if (resolved.isPresent()) {
                countedIn = resolved.get().code();
            } else {
                errors.add(new ProductRowError(
                        excelRow, countedInColumn.header(), "'" + rawCountedIn + "' is not a unit we recognise"));
            }
        }

        Column costColumn = column(table, "cost_per_unit");
        BigDecimal costPerUnit =
                nonNegativeDecimal(table.value(row, costColumn.header()), excelRow, costColumn.header(), errors);
        LocalDate receivedDate = receivedDateOf(table.value(row, "received_date"), excelRow, errors);
        String waybillOrInvoiceNo = table.value(row, "waybill_or_invoice_no");

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new ParsedStockInRow(
                excelRow, sku, vendorName, quantity, countedIn, costPerUnit, receivedDate, waybillOrInvoiceNo);
    }

    /** A column and the header the file actually spelled it with - see {@link #column}. */
    private record Column(String field, String header) {
    }

    /**
     * Which header to read one field from: the current name when the file has it, otherwise the old
     * name it was renamed from ({@link #HEADER_ALIASES}, reversed).
     *
     * <p>Current name wins, deliberately. A file that somehow carries both {@code counted_in} and
     * {@code unit} is either a hand-merged sheet or an export from a tool that kept the old column
     * around; in both cases the newer column is the one whose header the user was looking at when
     * they typed. Returning the header rather than just the value is what lets an error name the
     * column the user can actually see in their own file.
     */
    private Column column(SheetTable table, String field) {
        if (table.hasColumn(field)) {
            return new Column(field, field);
        }
        return HEADER_ALIASES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(field) && table.hasColumn(entry.getKey()))
                .findFirst()
                .map(entry -> new Column(field, entry.getKey()))
                .orElse(new Column(field, field));
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
     *
     * <p>It is also the one row on the sheet that can demonstrate the relationship the whole layout
     * is built around: {@code how_you_count_it} offers two answers, {@code counted_in} holds one of
     * them verbatim, and {@code cost_per_unit} is stated per that one. A user who reads only this
     * row has read the model.
     */
    private void writeExampleRow(WorkbookBuilder builder, int rowIndex, LocalDate today) {
        Row row = builder.sheet().createRow(rowIndex);
        Map<String, String> values = Map.of(
                "sku", EXAMPLE_SKU_MARKER_PREFIX + "-1",
                "product_name", "Example row - delete it or leave it, we skip it either way",
                "how_you_count_it", "kg · or Bag of 50 kg",
                "vendor_name", "",
                "quantity", "3",
                "counted_in", "Bag of 50 kg",
                "cost_per_unit", "42000",
                "received_date", today.toString(),
                "waybill_or_invoice_no", "WB-00123");
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
     * One product's pre-filled row. {@code sku}, {@code product_name} and {@code how_you_count_it}
     * carry the locked-reference styling - greyed, visibly not for editing. Styling is the only lock
     * applied: real sheet protection would also stop the user deleting rows they do not need, which
     * is a thing they legitimately want to do on a four-hundred-row sheet.
     */
    private void writeCatalogRow(WorkbookBuilder builder, int rowIndex, StockInTemplateRow source, LocalDate today) {
        Sheet sheet = builder.sheet();
        Row row = sheet.createRow(rowIndex);
        CellStyle reference = builder.referenceStyle();
        UnitOption preselected = UnitOptions.defaultOption(source.unitOptions())
                .orElseThrow(() -> new IllegalStateException("A unit set always has exactly one default"));

        Cell skuCell = row.createCell(HEADER_NAMES.indexOf("sku"));
        skuCell.setCellValue(source.sku());
        skuCell.setCellStyle(reference);

        Cell nameCell = row.createCell(HEADER_NAMES.indexOf("product_name"));
        nameCell.setCellValue(source.productName() == null ? "" : source.productName());
        nameCell.setCellStyle(reference);

        Cell countsCell = row.createCell(HEADER_NAMES.indexOf("how_you_count_it"));
        countsCell.setCellValue(SheetUnitOptions.howYouCountIt(source.unitOptions()));
        countsCell.setCellStyle(reference);

        if (source.vendorName() != null) {
            row.createCell(HEADER_NAMES.indexOf("vendor_name")).setCellValue(source.vendorName());
        }
        // A blank code is contract section 2.1's unitless product - the pre-V17 row that never got a
        // stock unit. The cell is left empty rather than filled with the word "units", because a
        // blank counted_in already means "the product's own stock unit" to every reader we have,
        // and writing a word there would send back a unit the product was never given.
        if (!preselected.code().isEmpty()) {
            row.createCell(HEADER_NAMES.indexOf("counted_in")).setCellValue(preselected.label());
        }
        if (source.costPerStockUnit() != null) {
            Cell costCell = row.createCell(HEADER_NAMES.indexOf("cost_per_unit"));
            costCell.setCellValue(costPerPreselectedUnit(source.costPerStockUnit(), preselected).doubleValue());
            costCell.setCellStyle(builder.moneyStyle());
        }
        Cell dateCell = row.createCell(HEADER_NAMES.indexOf("received_date"));
        dateCell.setCellValue(today);
        dateCell.setCellStyle(builder.dateStyle());

        // quantity is left with no cell at all, not an empty one: the column's default style
        // already gives a typed value its number format and its highlight, and a pre-created blank
        // cell would be one more thing for the user's cursor to land in.
    }

    /**
     * The mirror of the price bug, closed at the one place that could reintroduce it.
     *
     * <p>Every stored price is per stock unit (contract section 3.2): {@code ProductVendor
     * .lastCostPrice} is per kg, not per bag. The sheet's {@code cost_per_unit} is per whatever the
     * row's {@code counted_in} says - so a row pre-filled "Bag of 50 kg" must be pre-filled with
     * fifty times the stored figure, or the sheet would state ₦900 against a bag that cost ₦45,000
     * and a user who accepted the pre-fill would import a fiftieth of what they paid.
     * UNIT_UX_REMEDIATION_PLAN.md P0-1 is the same arithmetic in the other direction; this is the
     * round trip that made it self-perpetuating.
     *
     * <p>Rounded to money's own scale here rather than left at full precision: the cell is a money
     * cell, the user may accept it unchanged, and a value the sheet displays as 45,000.00 must be
     * the value the parser reads back.
     */
    private BigDecimal costPerPreselectedUnit(BigDecimal costPerStockUnit, UnitOption preselected) {
        return costPerStockUnit.multiply(preselected.factorToStockUnit()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void applyNumberFormats(WorkbookBuilder builder) {
        for (int i = 0; i < HEADER_NAMES.size(); i++) {
            switch (HEADER_NAMES.get(i)) {
                case "quantity" -> builder.formatColumn(i, builder.primaryInputStyle());
                case "cost_per_unit" -> builder.formatColumn(i, builder.moneyStyle());
                case "received_date" -> builder.formatColumn(i, builder.dateStyle());
                default -> {
                    // sku, product_name, how_you_count_it, vendor_name, counted_in and
                    // waybill_or_invoice_no are text. sku especially: a number format would turn
                    // 00123 into 123.
                }
            }
        }
    }
}
