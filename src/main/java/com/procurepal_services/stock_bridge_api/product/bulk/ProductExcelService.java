package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.io.LookupSheetWriter;
import com.procurepal_services.stock_bridge_api.imports.io.NumberValues;
import com.procurepal_services.stock_bridge_api.imports.io.SheetRow;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReadException;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Owns the .xlsx column layout shared by the template, export, and bulk-upload
 * parser - header names/order live only here so all three stay in sync. Has
 * no repository dependency: DB-backed checks (sku-already-exists, which vendor a name means) are
 * the caller's job (see ProductManagementService.bulkUpload), since this class
 * only knows about spreadsheet structure, not tenant data. Tenant/seller
 * status and the tenant's vendor list are also the caller's job - they arrive here inside a
 * {@link ProductTemplateContext} resolved by {@code ProductManagementService} (via
 * {@code SellerDirectory}/{@code Client.canSell()} and {@code CompanyVendorRepository}), so this
 * class never reaches into tenant or security context on its own.
 *
 * <h2>unit_price is the one tenant-conditional column</h2>
 * A buying company (non-seller) has no selling price at all, so its template
 * does not even show a {@code unit_price} column, and an upload from a
 * company is never required to fill one in. A seller's template keeps
 * {@code unit_price} as a required column, exactly as before. Every other
 * column - including the {@code stock_unit}/{@code pack}/{@code units_per_pack}
 * trio and the {@code vendor_name}/{@code vendor_sku}/
 * {@code is_preferred_vendor} trio after it - is the same for both tenant kinds.
 *
 * <h2>opening_stock counts PACKS - UNIT_UX_CONTRACT.md section 9.1</h2>
 * A row saying stock unit Milliliter, pack Keg, units per pack 50, opening stock 30 is thirty
 * KEGS - 1,500 ml. A row with no pack is stock units, exactly as it always was. Nothing on the
 * sheet says which, because the row already does: the pack columns beside the number ARE the
 * statement. {@code low_stock_alert_at} follows the same rule, because two quantity columns on
 * one row counting different things is the defect section 9 exists to remove.
 *
 * <p>This is the model a real user described unprompted - "30 bags of 80 kg rice = 2,400 kg" -
 * and it is NetSuite's purchase-unit / sale-unit split arrived at independently. Every other
 * entry surface in this system already defaults to the pack; this sheet was the last one still
 * making somebody think in kilograms. Decimals are accepted on both columns, because thirty kegs
 * and a half-full one is a real shelf.
 *
 * <p>{@code cost_price} deliberately does NOT follow: it stays per stock unit, per ml and not
 * per keg, and section 9.2 records why - it is the only figure comparable across suppliers whose
 * packs differ, which is the job that column does. Do not "fix" it to match its neighbours.
 *
 * <h2>Column order, and the promise that replaced the old one</h2>
 * The columns are grouped by the question they answer: who it is ({@code name}, {@code sku},
 * {@code description}), how you count it ({@code stock_unit}, {@code pack},
 * {@code units_per_pack}), how much ({@code opening_stock}, {@code low_stock_alert_at},
 * {@code cost_price}, {@code unit_price}), and who you buy it from.
 *
 * <p>The counting columns come BEFORE the quantity columns, and that ordering is now load
 * bearing rather than cosmetic: since section 9.1, what {@code opening_stock} means depends on
 * what {@code pack} and {@code units_per_pack} say, so a reader filling the sheet left to right
 * has already answered "how do you count it" by the time they are asked "how much". The previous
 * order put the pack columns three places to the RIGHT of the number they qualify, which is
 * precisely how "20 bags of rice" got typed into a column that meant kilograms.
 *
 * <p>This replaces the older stability promise that froze the first ten columns in place. That
 * promise was kept by never moving a column; it is now kept by {@link #HEADER_ALIASES} instead,
 * which is strictly stronger - a saved sheet parses by HEADER, in whatever order its columns
 * happen to be in, and every spelling this template has ever published is still accepted. Order
 * only ever mattered to a person reading two files side by side, and section 9.4 renamed four
 * headers anyway, so there was no version of this change that left the old file looking
 * identical.
 *
 * <h2>The renames - section 9.4</h2>
 * {@code quantity_on_hand} to {@code opening_stock} (section 5.1), then {@code unit_of_measure}
 * to {@code stock_unit}, {@code packaging_unit} to {@code pack}, {@code packaging_size} to
 * {@code units_per_pack} and {@code low_stock_threshold} to {@code low_stock_alert_at}. Each old
 * spelling stays accepted on read forever ({@link #HEADER_ALIASES}), and the error messages name
 * whichever header the file actually used. Contract section 7, non-negotiable 8.
 *
 * <p>An aliased {@code quantity_on_hand} is read under section 9.1's pack rule like any other
 * column, NOT under its old stock-unit meaning. Section 9.1 says so explicitly: nothing has
 * reached production, so there is no saved sheet whose bare number needs its old meaning
 * preserved - and that was the only argument for the {@code opening_stock_counted_in} column
 * this change deletes.
 *
 * <h2>Labels, not codes</h2>
 * Both unit dropdowns and both unit columns of the export now carry the human label
 * ({@code "Kilogram (kg)"}, {@code "Bag"}) rather than the internal code - contract section 7,
 * non-negotiable 4. It round-trips because {@code UnitOfMeasure.fromCodeOrLabel} derives its
 * accepted spellings from {@link UnitOfMeasure#label()} itself.
 *
 * <h2>Example-row convention</h2>
 * The two example rows written by generateTemplate() use a reserved SKU prefix
 * (EXAMPLE_SKU_MARKER_PREFIX). parse() silently skips any row whose sku starts with that marker,
 * so a user who forgets to delete the example rows before uploading doesn't get spurious errors -
 * chosen over requiring manual deletion because it's strictly more forgiving and costs nothing
 * extra to implement. The same trick carries over to the stock-in template
 * ({@code StockInExcelService}).
 *
 * <h2>Reading is streamed; writing is not</h2>
 * Parsing goes through {@link SpreadsheetReader}, which reads the sheet off a SAX event stream and
 * enforces the row and byte caps before any content is interpreted - see that class for why
 * {@code XSSFWorkbook} was the wrong tool on the read path at a 5,000-row cap, and why it is still
 * the right one for generating a three-row template. One consequence worth naming: cell values
 * arrive here as Strings, so every "is this a number" decision now lives in one place
 * ({@link NumberValues}) instead of being spread across cell-type checks, and it got considerably
 * more forgiving in the move - {@code "45,000"}, {@code "N45,000.00"} and {@code "(500)"} are all
 * understood now, where before each was an error row on a value the user had got right.
 */
@Service
@RequiredArgsConstructor
public class ProductExcelService {

    private final SpreadsheetReader spreadsheetReader;

    /**
     * Every column this class knows about, in canonical order. Template, export and parse all
     * derive their working column list FROM this one (filtering {@code unit_price} out for a
     * non-seller's template) rather than keeping a second copy, so the three can never drift
     * apart on order or naming. Identical to BULK_IMPORT_CONTRACT.md section 5's PRODUCT_CATALOG
     * field keys, in the same order, because a field key IS a column header.
     */
    static final List<String> ALL_HEADER_NAMES = List.of(
            "name", "sku", "description",
            "stock_unit", "pack", "units_per_pack",
            "opening_stock", "low_stock_alert_at", "cost_price", "unit_price",
            "vendor_name", "vendor_sku", "is_preferred_vendor");

    /**
     * Headers this parser still answers to, and always will: the old spelling on the left, the
     * column it now means on the right.
     *
     * <p>UNIT_UX_CONTRACT.md section 5.1 renamed {@code quantity_on_hand} to
     * {@code opening_stock}; section 9.4 then renamed four more, so that the sheet header, the
     * API field key and the on-screen label are the same idea spelled once (section 1's locked
     * vocabulary - "Stock unit", "Pack", "Units per pack", never "unit of measure" or "pack
     * size").
     *
     * <p>Every old name stays accepted <b>forever</b>, which is what makes a rename cost a user
     * nothing: a sheet somebody downloaded last week still parses, by header rather than by
     * position, and never reaches the column-mapping screen. Contract section 7,
     * non-negotiable 8.
     *
     * <p>An aliased {@code quantity_on_hand} is nonetheless read under section 9.1's pack rule,
     * not under its old meaning - see the class javadoc. This map maps a NAME to a name; it does
     * not carry a meaning with it.
     */
    static final Map<String, String> HEADER_ALIASES = Map.of(
            "quantity_on_hand", "opening_stock",
            "low_stock_threshold", "low_stock_alert_at",
            "unit_of_measure", "stock_unit",
            "packaging_unit", "pack",
            "packaging_size", "units_per_pack");

    private static final Map<String, Integer> COLUMN_WIDTHS_CHARS_BY_HEADER = Map.ofEntries(
            Map.entry("name", 28),
            Map.entry("sku", 18),
            Map.entry("description", 42),
            Map.entry("stock_unit", 18),
            Map.entry("pack", 16),
            Map.entry("units_per_pack", 16),
            Map.entry("opening_stock", 18),
            Map.entry("low_stock_alert_at", 22),
            Map.entry("cost_price", 14),
            Map.entry("unit_price", 14),
            Map.entry("vendor_name", 30),
            Map.entry("vendor_sku", 18),
            Map.entry("is_preferred_vendor", 20));

    /**
     * One short sentence per column, anchored on that column's own header - replacing the single
     * paragraph-long comment the previous template put on B1. See
     * {@code WorkbookBuilder.writeHeaderRow} for why that change matters more than it looks:
     * four rules in one cell is where nobody reads them.
     *
     * <p>{@code unit_price}'s text is tenant-conditional and is therefore composed in
     * {@link #headerCommentsFor}, not here.
     *
     * <h2>Rewritten as a set for UNIT_UX_CONTRACT.md section 9</h2>
     * These comments are the primary UI for most of this product's users - far more of them fill
     * the spreadsheet in than ever open the product form - so they are written as one set that
     * teaches one model, not as thirteen independently maintained sentences.
     *
     * <p>The three that carry the model are {@code opening_stock}, {@code low_stock_alert_at}
     * and {@code cost_price}. The first two say the number is in PACKS when the row has one
     * (section 9.1); the third says the money is NOT (section 9.2). That divergence is the one
     * thing a user can get wrong here, so both halves are stated in the concrete terms of the
     * example row sitting two rows below - kegs and millilitres - rather than in the abstract
     * "stock unit" the columns are named after. "Per ml, not per keg" is a sentence somebody can
     * check against their invoice; "per stock unit" is one they have to translate first.
     */
    private static final Map<String, String> HEADER_COMMENTS = Map.ofEntries(
            Map.entry("name", "What you call this product. Required."),
            Map.entry("sku", "Your own code for this product - it must be unique in your catalog. Required."),
            Map.entry("description", "Optional. Anything you want on the product page."),
            Map.entry("stock_unit", "What you count this product in - Kilogram, Milliliter, Piece. Everything we store for it is counted this way. Pick from the list."),
            Map.entry("pack", "Optional. The container you buy and sell it by - Bag, Keg, Carton. Leave it blank if you sell it loose."),
            Map.entry("units_per_pack", "How much is in one pack. 50, beside Milliliter and Keg, means a 50 ml keg. Required once you fill in Pack."),
            Map.entry("opening_stock", "How much you have right now, counted in PACKS when this row has one - 30 beside Keg means 30 kegs, not 30 ml. No pack? Then it is 30 ml. Half-packs are fine: 30.5."),
            Map.entry("low_stock_alert_at", "Optional. Tell us when to warn you that stock is running low. Counted the same way as opening stock - in kegs if this row has a pack."),
            Map.entry("cost_price", "What you pay for ONE of what the opening stock counts - one keg if this row has a pack, one ml if it does not. Optional. If the row also has an opening stock, this becomes that stock's cost."),
            Map.entry("vendor_name", "Optional. Who you buy this from. Pick from your suppliers, or type a new name and we will ask about it."),
            Map.entry("vendor_sku", "Optional. That supplier's own code for this product, if it differs from yours."),
            Map.entry("is_preferred_vendor", "Optional. Type TRUE if this is your main supplier for the product. Leave blank otherwise."));

    /**
     * Public rather than package-private because the session engine has to honour the same rule:
     * both templates write an example row carrying this prefix and tell the user, in the cell
     * itself, that leaving it in is safe. That promise has to be kept by whichever door the file
     * comes back through - this service's own {@code parse}, or {@code ImportSessionService}.
     */
    public static final String EXAMPLE_SKU_MARKER_PREFIX = "EXAMPLE-SKU-DELETE-ME";

    /** Defined names on the hidden {@code _lookups} sheet - see {@link LookupSheetWriter}. */
    private static final String BASE_UNITS_RANGE = "base_units";

    private static final String PACKAGING_UNITS_RANGE = "packaging_units";

    private static final String VENDOR_NAMES_RANGE = "vendor_names";

    private static final String YES_FLAG_RANGE = "yes_flag";

    /**
     * Everything accepted as "yes" in {@code is_preferred_vendor}. Generous on purpose: the
     * template's dropdown offers TRUE, but a user typing by hand writes {@code Yes}, an ERP export
     * writes {@code Y} or {@code 1}, and a person marking up a printed sheet writes {@code X}.
     * Every one of those unambiguously means the same thing, and rejecting them would be pedantry
     * with an error row attached.
     */
    private static final Set<String> TRUTHY = Set.of("TRUE", "T", "YES", "Y", "1", "X", "✓", "PREFERRED");

    /** Everything accepted as an explicit "no". Treated identically to leaving the cell blank. */
    private static final Set<String> FALSY = Set.of("FALSE", "F", "NO", "N", "0", "-");

    /**
     * The columns a template/upload for this tenant kind actually has. {@code unit_price} is
     * the only column that ever drops out - a non-seller (buying company) has no selling price
     * field at all, so showing it (even as optional) invites the question "should I fill this
     * in?" for a field that means nothing on a company's own private stock.
     */
    public List<String> headerNamesFor(boolean isSeller) {
        return isSeller
                ? ALL_HEADER_NAMES
                : ALL_HEADER_NAMES.stream().filter(h -> !h.equals("unit_price")).toList();
    }

    /**
     * {@code name}/{@code sku} are always required. {@code unit_price} joins them only for a
     * seller, whose product IS a listing - see {@code UnitPriceRequiredException} for the same
     * rule on the manual create/update path. Nothing else is ever in this list: the unit and
     * vendor columns are optional for every tenant kind, cross-validated by {@link #parseRow}
     * rather than by header presence.
     */
    private List<String> requiredHeadersFor(boolean isSeller) {
        return isSeller ? List.of("name", "sku", "unit_price") : List.of("name", "sku");
    }

    /**
     * The pre-existing signature, kept so every caller that only knows about seller status still
     * compiles and behaves identically - it simply gets a template with no vendor dropdown, which
     * is exactly right for a caller that has not looked up a vendor directory.
     */
    public byte[] generateTemplate(boolean isSeller) {
        return generateTemplate(ProductTemplateContext.of(isSeller));
    }

    /**
     * The per-tenant template: the tenant's own suppliers in the {@code vendor_name} dropdown,
     * unit codes in the two unit columns, number formats on everything numeric, a short comment on
     * every header, and the whole lookup machinery hidden on a sheet the user never has to see.
     */
    public byte[] generateTemplate(ProductTemplateContext context) {
        try (WorkbookBuilder builder = new WorkbookBuilder("Products")) {
            List<String> headers = headerNamesFor(context.isSeller());
            builder.writeHeaderRow(headers, COLUMN_WIDTHS_CHARS_BY_HEADER, headerCommentsFor(context.isSeller()));
            applyNumberFormats(builder, headers);

            List<String> vendorNames = context.vendorDropdownNames();
            writeExampleRow(builder, 1, headers, exampleRowOne(vendorNames));
            writeExampleRow(builder, 2, headers, exampleRowTwo());

            LookupSheetWriter lookups = new LookupSheetWriter(builder.workbook());
            addDropdown(builder, headers, "stock_unit",
                    lookups.addList(BASE_UNITS_RANGE, labelsOf(UnitOfMeasure.baseUnits())),
                    "Stock unit",
                    "Pick a unit from the list, or type one - we understand kg, kilo, bags, ctn and most other "
                            + "spellings. If we cannot work it out we will ask you after you upload.");
            // The opening_stock_counted_in dropdown is gone with its column - section 9.1. It
            // asked which unit the number beside it was in; the row now answers that by itself,
            // and a dropdown offering all ~30 units next to a number whose unit is already
            // decided would be a question with no legal wrong answer.
            addDropdown(builder, headers, "pack",
                    lookups.addList(PACKAGING_UNITS_RANGE, labelsOf(UnitOfMeasure.packagingUnits())),
                    "Pack",
                    "Pick the container this product comes in - Bag, Carton, Drum and so on. Leave blank if it is sold loose.");
            addDropdown(builder, headers, "vendor_name",
                    lookups.addList(VENDOR_NAMES_RANGE, vendorNames),
                    "Supplier",
                    "Pick one of your suppliers, or type a new name - we will ask whether to add it after you upload.");
            addDropdown(builder, headers, "is_preferred_vendor",
                    lookups.addList(YES_FLAG_RANGE, List.of("TRUE")),
                    "Main supplier",
                    "Type TRUE if this is your main supplier for this product, or leave it blank.");
            lookups.hide();

            return builder.toBytes();
        }
    }

    /**
     * Not tenant-conditional the way the template is: an export shows whatever is actually on
     * each product row, and both a company and a seller can have real values in every column.
     * Always writes the full column set - which is also what makes "export, edit, upload back" a
     * lossless round trip, since the template the user would otherwise be editing has exactly
     * these columns.
     */
    public byte[] exportProducts(List<Product> products) {
        return exportProducts(products, Map.of());
    }

    /**
     * @param preferredVendorsByProductId supplies the three vendor columns. Passed in already
     *     resolved rather than read off {@code Product.getVendors()} here, because that
     *     association is LAZY specifically so a page of products never pays to load it - see its
     *     javadoc, and {@code ProductManagementService.preferredVendorNamesFor} for the batched
     *     query that fills this map in one round trip instead of one per row. A product missing
     *     from the map simply exports with blank vendor columns.
     */
    public byte[] exportProducts(List<Product> products, Map<UUID, ProductVendorSnapshot> preferredVendorsByProductId) {
        try (WorkbookBuilder builder = new WorkbookBuilder("Products")) {
            builder.writeHeaderRow(ALL_HEADER_NAMES, COLUMN_WIDTHS_CHARS_BY_HEADER, Map.of());
            applyNumberFormats(builder, ALL_HEADER_NAMES);
            Sheet sheet = builder.sheet();

            int rowIndex = 1;
            for (Product product : products) {
                Row row = sheet.createRow(rowIndex++);
                // Indexed by header name rather than by literal position. The positions moved
                // with UNIT_UX_CONTRACT.md section 9's regrouping, and a block of hand-counted
                // integers is exactly the thing that silently writes the cost price into the
                // opening-stock column the next time they move.
                cell(row, "name").setCellValue(product.getName());
                cell(row, "sku").setCellValue(product.getSku());
                cell(row, "description")
                        .setCellValue(product.getDescription() == null ? "" : product.getDescription());
                // Labels, not the stored codes - contract section 7, non-negotiable 4. The export
                // and the template are the same file to a user who exports, edits and re-uploads,
                // so a cell that reads "KG" on one and "Kilogram (kg)" on the other would be two
                // vocabularies in one workflow. Round-trips exactly: fromCodeOrLabel resolves the
                // display label back to the same constant.
                cell(row, "stock_unit").setCellValue(unitLabelOrBlank(product.getUnitOfMeasure()));
                cell(row, "pack").setCellValue(unitLabelOrBlank(product.getPackagingUnit()));
                if (product.getPackagingSize() != null) {
                    cell(row, "units_per_pack").setCellValue(product.getPackagingSize().doubleValue());
                }

                // Both quantities are exported in the terms the column is now READ in - packs
                // when the product has one (section 9.1), stock units when it does not. This is
                // the whole of what keeps "export, edit, re-upload" honest: the stored figure is
                // 1,500 ml, the column means kegs, so the cell has to say 30. Writing 1,500 there
                // would re-import as 1,500 kegs and multiply the catalog by fifty on every round
                // trip - the same trap the deleted opening_stock_counted_in column was left blank
                // to avoid, solved now by stating the number in the column's own unit instead of
                // by leaving a second column empty.
                BigDecimal openingStock = inSheetUnits(product, BigDecimal.valueOf(product.getQuantityOnHand()));
                cell(row, "opening_stock").setCellValue(openingStock.doubleValue());
                if (product.getLowStockThreshold() != null) {
                    BigDecimal alertAt =
                            inSheetUnits(product, BigDecimal.valueOf(product.getLowStockThreshold()));
                    cell(row, "low_stock_alert_at").setCellValue(alertAt.doubleValue());
                }

                // cost_price does NOT get the same treatment - section 9.2. It is per stock unit
                // on the way out because it is per stock unit on the way in, and converting it
                // here would be the mixed-basis row the whole amendment exists to remove.
                if (product.getCostPrice() != null) {
                    cell(row, "cost_price").setCellValue(product.getCostPrice().doubleValue());
                }
                // Nullable since V17 (a buying company's product has no selling price to give)
                // - null-guarded the same way costPrice above it always has been, so an export
                // of a company's own catalog does not NPE on a row with nothing in this column.
                if (product.getUnitPrice() != null) {
                    cell(row, "unit_price").setCellValue(product.getUnitPrice().doubleValue());
                }

                ProductVendorSnapshot vendor = preferredVendorsByProductId.get(product.getId());
                if (vendor != null) {
                    cell(row, "vendor_name").setCellValue(vendor.vendorName() == null ? "" : vendor.vendorName());
                    cell(row, "vendor_sku").setCellValue(vendor.vendorSku() == null ? "" : vendor.vendorSku());
                    if (vendor.preferred()) {
                        cell(row, "is_preferred_vendor").setCellValue("TRUE");
                    }
                }
            }
            return builder.toBytes();
        }
    }

    /**
     * Throws BulkUploadValidationException with the full set of header and
     * row-level errors found (V1 all-or-nothing: see ProductManagementService
     * for why). Returns only rows that are structurally valid and unique
     * within the file - the caller still owes a DB-uniqueness check on top.
     *
     * <p>{@code isSeller} decides whether {@code unit_price} is a required column/cell (see
     * class javadoc) - resolved by the caller via {@code SellerDirectory}/{@code Client.canSell()}
     * BEFORE calling this method, since the requiredness check has to happen at header-validation
     * time, not just per-row.
     *
     * <p>Every failure a user could have caused comes back as a
     * {@link BulkUploadValidationException}, including the file-level ones the reader raises: a
     * corrupt upload still says "The uploaded file is not a valid .xlsx file" verbatim, and an
     * over-cap one says how many rows it actually has. The catch-all around the whole method is
     * kept for the same reason it was written - anything POI or the XML parser can throw on a
     * malformed file is a bad upload, not a server fault, and it must never reach the client as a
     * 500.
     */
    public List<ParsedProductRow> parse(MultipartFile file, boolean isSeller) {
        SheetTable table;
        try {
            table = spreadsheetReader.read(file);
        } catch (SpreadsheetReadException e) {
            throw new BulkUploadValidationException(List.of(new ProductRowError(0, "file", e.getMessage())));
        } catch (RuntimeException e) {
            throw notASpreadsheet();
        }
        return parse(table, isSeller);
    }

    /**
     * The already-read overload, for callers that hold a {@link SheetTable} - the import session
     * engine reads the upload once, stores the raw rows, and re-validates them repeatedly as the
     * user repairs the file, so it must be able to parse without a {@code MultipartFile} in hand.
     */
    public List<ParsedProductRow> parse(SheetTable table, boolean isSeller) {
        try {
            List<String> missing = requiredHeadersFor(isSeller).stream()
                    .filter(header -> !table.hasColumn(header))
                    .toList();
            if (!missing.isEmpty()) {
                throw new BulkUploadValidationException(missingHeaderErrors(missing));
            }

            List<ProductRowError> errors = new ArrayList<>();
            List<ParsedProductRow> rows = new ArrayList<>();
            Map<String, Integer> skuFirstSeenAtRow = new LinkedHashMap<>();

            for (SheetRow row : table.rows()) {
                int excelRow = row.excelRow();
                // A row with neither a name nor a sku is not a row - it is formatting, or a
                // stray note in the description column. Kept as the blank test (rather than
                // "every cell is empty") because that is what it has always been, and widening
                // it would start erroring on notes that are silently ignored today.
                if (value(table, row, "name") == null && value(table, row, "sku") == null) {
                    continue;
                }

                String sku = value(table, row, "sku");
                if (sku != null && sku.toUpperCase(Locale.ROOT).startsWith(EXAMPLE_SKU_MARKER_PREFIX)) {
                    continue;
                }

                ParsedProductRow parsed = parseRow(table, row, excelRow, errors, isSeller);
                if (parsed == null) {
                    continue;
                }

                Integer firstSeenAt = skuFirstSeenAtRow.putIfAbsent(parsed.sku().toUpperCase(Locale.ROOT), excelRow);
                if (firstSeenAt != null) {
                    errors.add(new ProductRowError(
                            excelRow, "sku", "Duplicate SKU within file (also appears in row " + firstSeenAt + ")"));
                } else {
                    rows.add(parsed);
                }
            }

            if (!errors.isEmpty()) {
                throw new BulkUploadValidationException(errors);
            }
            return rows;
        } catch (BulkUploadValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw notASpreadsheet();
        }
    }

    private BulkUploadValidationException notASpreadsheet() {
        return new BulkUploadValidationException(
                List.of(new ProductRowError(0, "file", "The uploaded file is not a valid .xlsx file")));
    }

    private ParsedProductRow parseRow(
            SheetTable table, SheetRow row, int excelRow, List<ProductRowError> errors, boolean isSeller) {
        int errorsBefore = errors.size();

        String name = value(table, row, "name");
        if (name == null) {
            errors.add(new ProductRowError(excelRow, "name", "is required"));
        }

        String sku = value(table, row, "sku");
        if (sku == null) {
            errors.add(new ProductRowError(excelRow, "sku", "is required"));
        }

        String description = value(table, row, "description");

        // unit_price is required only for a seller's upload - see class javadoc and
        // ProductManagementService.create's UnitPriceRequiredException for the same rule on
        // the manual path. A non-seller's blank cell is fine; a non-blank one is still parsed
        // (so a stray value doesn't error) but is discarded downstream and never stored.
        BigDecimal unitPrice = null;
        String rawUnitPrice = value(table, row, "unit_price");
        if (rawUnitPrice == null) {
            if (isSeller) {
                errors.add(new ProductRowError(excelRow, "unit_price", "is required"));
            }
        } else {
            unitPrice = nonNegativeDecimal(rawUnitPrice, excelRow, "unit_price", errors);
        }

        BigDecimal costPrice = null;
        String rawCostPrice = value(table, row, "cost_price");
        if (rawCostPrice != null) {
            costPrice = nonNegativeDecimal(rawCostPrice, excelRow, "cost_price", errors);
        }

        // unit_of_measure/packaging_unit/packaging_size: optional for every tenant kind, but
        // cross-validated - see ProductManagementService.requirePackagingUnitAndSizePaired and
        // .requirePackagingImpliesUnitOfMeasure for the same rules on the manual path. Presence
        // of each is tracked from the raw cell (before validation), not from the resolved
        // value, so an invalid code in one column doesn't ALSO trigger a misleading "requires"
        // error against another column that was in fact supplied correctly.
        String stockUnitHeader = headerFor(table, "stock_unit");
        String rawUnitOfMeasure = value(table, row, stockUnitHeader);
        boolean unitOfMeasureProvided = rawUnitOfMeasure != null;
        String unitOfMeasure = null;
        if (unitOfMeasureProvided) {
            // fromCodeOrLabel, not fromCode: the dropdown is an affordance and a large share of
            // these cells are typed, so "kg", "Kilogram (kg)", "kilos" and "KGS" all have to land
            // on KG here or they become error rows on values that were never actually wrong.
            Optional<UnitOfMeasure> resolved =
                    UnitOfMeasure.fromCodeOrLabel(rawUnitOfMeasure, UnitOfMeasureRole.BASE);
            if (resolved.isPresent()) {
                unitOfMeasure = resolved.get().code();
            } else {
                errors.add(new ProductRowError(
                        excelRow,
                        stockUnitHeader,
                        "'" + rawUnitOfMeasure + "' is not a recognized stock unit"
                                + wrongRoleHint(rawUnitOfMeasure, UnitOfMeasureRole.BASE)));
            }
        }

        // packaging_unit mirrors unit_of_measure's parse but requires the PACKAGING role - a
        // code that exists but is BASE-role (e.g. "KG" submitted here) gets the same "not
        // recognized" treatment as a code that is not on the list at all, matching
        // ProductManagementService.resolvePackagingUnit/InvalidUnitOfMeasureException on the
        // manual path - now with a hint naming the column it does belong in, since we know.
        String packHeader = headerFor(table, "pack");
        String rawPackagingUnit = value(table, row, packHeader);
        boolean packagingUnitProvided = rawPackagingUnit != null;
        String packagingUnit = null;
        if (packagingUnitProvided) {
            Optional<UnitOfMeasure> resolved =
                    UnitOfMeasure.fromCodeOrLabel(rawPackagingUnit, UnitOfMeasureRole.PACKAGING);
            if (resolved.isPresent()) {
                packagingUnit = resolved.get().code();
            } else {
                errors.add(new ProductRowError(
                        excelRow,
                        packHeader,
                        "'" + rawPackagingUnit + "' is not a recognized pack"
                                + wrongRoleHint(rawPackagingUnit, UnitOfMeasureRole.PACKAGING)));
            }
        }

        String unitsPerPackHeader = headerFor(table, "units_per_pack");
        String rawPackagingSize = value(table, row, unitsPerPackHeader);
        boolean packagingSizeProvided = rawPackagingSize != null;
        BigDecimal packagingSize = null;
        if (packagingSizeProvided) {
            packagingSize = nonNegativeDecimal(rawPackagingSize, excelRow, unitsPerPackHeader, errors);
        }

        // packaging_unit/packaging_size travel together - either both present or neither - same
        // symmetric pairing rule as ProductManagementService.requirePackagingUnitAndSizePaired.
        if (packagingUnitProvided != packagingSizeProvided) {
            if (packagingUnitProvided) {
                errors.add(new ProductRowError(
                        excelRow, packHeader, packHeader + " requires " + unitsPerPackHeader));
            } else {
                errors.add(new ProductRowError(
                        excelRow, unitsPerPackHeader, unitsPerPackHeader + " requires " + packHeader));
            }
        }

        // Whenever packaging is present, unit_of_measure must also be present - one-directional,
        // same as ProductManagementService.requirePackagingImpliesUnitOfMeasure /
        // PackagingRequiresUnitOfMeasureException on the manual path. unit_of_measure alone (no
        // packaging) stays fully valid.
        if (!unitOfMeasureProvided) {
            if (packagingUnitProvided) {
                errors.add(new ProductRowError(
                        excelRow, packHeader, packHeader + " requires " + stockUnitHeader));
            }
            if (packagingSizeProvided) {
                errors.add(new ProductRowError(
                        excelRow, unitsPerPackHeader, unitsPerPackHeader + " requires " + stockUnitHeader));
            }
        }

        // ------------------------------------------------------------------ the two quantities
        // Parsed AFTER the three counting columns above, and that order is the whole of section
        // 9.1 in code: what these numbers COUNT depends on whether this row declared a pack, so
        // the pack has to be known before either of them can be read. Both are resolved against
        // the row's own unit set - the same UnitOptions the product page, the stock modals and
        // the stock-in sheet resolve against - so there is exactly one implementation of "how
        // many stock units is that", and it is not this class's.
        //
        // The header the errors name is the header the FILE used, not the one we would have
        // written, so a user reading "row 4: quantity_on_hand must be a number" can find that
        // column in their own sheet (HEADER_ALIASES).
        String openingStockHeader = headerFor(table, "opening_stock");
        Integer openingStock = packAwareQuantity(
                value(table, row, openingStockHeader), excelRow, openingStockHeader,
                unitOfMeasure, packagingUnit, packagingSize, errors);
        int quantityOnHand = openingStock == null ? 0 : openingStock;

        String alertAtHeader = headerFor(table, "low_stock_alert_at");
        Integer lowStockThreshold = packAwareQuantity(
                value(table, row, alertAtHeader), excelRow, alertAtHeader,
                unitOfMeasure, packagingUnit, packagingSize, errors);

        // The vendor trio. vendor_name is carried through verbatim - resolving a name to a
        // CompanyVendor is a tenant-data lookup and, when it misses, a question for the user
        // rather than an answer this class can invent (see ParsedProductRow). The other two hang
        // off it: a vendor SKU or a "this is my main supplier" flag with no supplier named is not
        // a partially-filled row, it is a row whose author meant something we cannot guess.
        String vendorName = value(table, row, "vendor_name");
        String vendorSku = value(table, row, "vendor_sku");
        boolean preferredVendor = parseFlag(value(table, row, "is_preferred_vendor"), excelRow, errors);
        if (vendorName == null) {
            if (vendorSku != null) {
                errors.add(new ProductRowError(excelRow, "vendor_sku", "vendor_sku requires vendor_name"));
            }
            if (preferredVendor) {
                errors.add(new ProductRowError(
                        excelRow, "is_preferred_vendor", "is_preferred_vendor requires vendor_name"));
            }
        }

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new ParsedProductRow(
                excelRow, name, sku, description, unitPrice, costPrice, quantityOnHand, lowStockThreshold,
                unitOfMeasure, packagingUnit, packagingSize, vendorName, vendorSku, preferredVendor);
    }

    /**
     * "Bag belongs in the pack column" appended to the not-recognized message when the value IS a
     * real unit, just for the other column. Costs one extra lookup and turns "we don't recognise
     * this" - which is not even true - into an instruction.
     *
     * <p>Names the columns by their current headers (section 9.4's {@code pack} and
     * {@code stock_unit}). Pointing somebody at a "packaging_unit" column their sheet does not
     * have would be an instruction they cannot follow.
     */
    private String wrongRoleHint(String rawValue, UnitOfMeasureRole expectedRole) {
        UnitOfMeasureRole otherRole =
                expectedRole == UnitOfMeasureRole.BASE ? UnitOfMeasureRole.PACKAGING : UnitOfMeasureRole.BASE;
        return UnitOfMeasure.fromCodeOrLabel(rawValue, otherRole)
                .map(unit -> " - " + unit.label() + " belongs in the "
                        + (otherRole == UnitOfMeasureRole.PACKAGING ? "pack" : "stock_unit")
                        + " column")
                .orElse("");
    }

    /**
     * TRUE/blank, generously read - see {@link #TRUTHY}. A value that is neither recognisably yes
     * nor recognisably no is an error rather than a silent false: someone who typed "maybe" or a
     * date into this column meant something, and quietly reading it as "no" would drop a
     * preference they thought they had set.
     */
    private boolean parseFlag(String raw, int excelRow, List<ProductRowError> errors) {
        if (raw == null) {
            return false;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (TRUTHY.contains(normalized)) {
            return true;
        }
        if (FALSY.contains(normalized)) {
            return false;
        }
        errors.add(new ProductRowError(
                excelRow, "is_preferred_vendor", "'" + raw + "' is not TRUE or blank"));
        return false;
    }

    /**
     * One of the two quantity columns, read under UNIT_UX_CONTRACT.md section 9.1: <b>packs when
     * this row declares one, stock units when it does not</b>, converted and returned in stock
     * units, which is what everything downstream of this record counts in.
     *
     * <h2>Why the row needs no column to say which</h2>
     * The row already says it. {@code pack} + {@code units_per_pack} sitting beside the number
     * ARE the statement, and the deleted {@code opening_stock_counted_in} column was asking a
     * question the sheet had already answered two cells to the left. Section 9.1 is explicit
     * that nothing has reached production, so no saved sheet's bare number needs its old
     * stock-unit meaning preserved - which was that column's only remaining justification.
     *
     * <h2>Decimals</h2>
     * Accepted, and this is the reason {@code nonNegativeInteger} is no longer the right parser
     * here: thirty kegs and a half-full one is a real shelf, and an integer count of packs cannot
     * say it. The conversion rounds HALF_UP at scale 0 - section 3.1's rounding, the same one
     * every other quantity in this system goes through - because the columns it feeds
     * ({@code stock_movements.quantity}, {@code products.quantity_on_hand}) are integers.
     *
     * <p>A conversion that rounds to <b>zero is refused</b>, never stored as nothing: 0.4 of a
     * 1-piece pack is not "no stock", it is a number we cannot record, and section 3.1 requires
     * saying so. Silently writing zero for stock somebody typed is the same class of defect as
     * writing the wrong number.
     *
     * @param raw the cell as the file spelled it, or null when the column is absent or blank.
     * @param column the header this file actually used, for the error message.
     * @return the amount in STOCK units, or null when the cell was blank or unreadable (in which
     *     case an error has already been recorded).
     */
    private Integer packAwareQuantity(
            String raw,
            int excelRow,
            String column,
            String stockUnitCode,
            String packagingUnit,
            BigDecimal unitsPerPack,
            List<ProductRowError> errors) {
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = NumberValues.parseDecimal(raw);
        if (parsed.isEmpty()) {
            errors.add(new ProductRowError(excelRow, column, "must be a number"));
            return null;
        }
        BigDecimal entered = parsed.get();
        if (entered.signum() < 0) {
            errors.add(new ProductRowError(excelRow, column, "must be a positive number"));
            return null;
        }
        UnitOption option = countedIn(stockUnitCode, packagingUnit, unitsPerPack);
        BigDecimal stockUnits = option.factorToStockUnit().multiply(entered);
        // Bounded before conversion rather than letting intValueExact throw. The multiply makes
        // this reachable from two individually sane cells - two million bags of fifty - and a
        // mistyped cell has to come back as a row error like every other bad number.
        if (stockUnits.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            errors.add(new ProductRowError(excelRow, column, "is too large"));
            return null;
        }
        int rounded = stockUnits.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        if (rounded == 0 && entered.signum() > 0) {
            errors.add(new ProductRowError(excelRow, column,
                    // symbolOf returns "" for a product with no stock unit at all (the pre-V17
                    // row), and "less than one whole  - enter..." reads as a broken string rather
                    // than a message. Section 2.1's placeholder word fills the hole.
                    "is less than one whole " + stockUnitWord(stockUnitCode)
                            + " - enter a larger amount, or change this product's stock unit"));
            return null;
        }
        return rounded;
    }

    /** The stock unit's short symbol, or section 2.1's "units" for a product that has none. */
    private String stockUnitWord(String stockUnitCode) {
        String symbol = UnitOptions.symbolOf(stockUnitCode);
        return symbol.isEmpty() ? UnitOptions.NO_STOCK_UNIT_LABEL : symbol;
    }

    /**
     * Which unit the two quantity columns are counted in for a row declaring these three
     * counting values - section 9.1's rule, expressed as section 2.1's set rather than as a
     * fresh {@code if}.
     *
     * <p>"The pack when the row has one, the stock unit when it does not" is precisely
     * {@code UnitOptions.defaultOption}: section 2.1 defines {@code isDefault} as the product's
     * own pack if it has one, else the stock unit. Asking the set instead of re-deriving the
     * rule is what stops this sheet and the product form from ever disagreeing about what a
     * number means - the single failure this whole remediation exists to undo.
     *
     * <p>Never empty: a row with no stock unit at all still yields the one-entry "units" set
     * (section 2.1's last paragraph, the pre-V17 product), whose factor is 1.
     */
    private UnitOption countedIn(String stockUnitCode, String packagingUnit, BigDecimal unitsPerPack) {
        List<UnitOption> options = UnitOptions.forProduct(stockUnitCode, packagingUnit, unitsPerPack);
        return UnitOptions.defaultOption(options)
                .or(() -> UnitOptions.stockUnitOption(options))
                .orElseGet(() -> new UnitOption(
                        UnitOptions.NO_STOCK_UNIT_CODE, UnitOptions.NO_STOCK_UNIT_LABEL,
                        BigDecimal.ONE, true, true, false));
    }

    /**
     * A stored stock-unit figure expressed in the terms the sheet's quantity columns are read in
     * - the inverse of {@link #packAwareQuantity}, for the export.
     *
     * <p>Dividing rather than multiplying is the one place this class does arithmetic that
     * {@code UnitOption} does not already own, and the reason is that no ledger value is ever
     * computed from it: the result is written into a spreadsheet cell and read back through
     * {@link #packAwareQuantity}, which multiplies by the same factor. Six decimal places is
     * enough for that to round-trip exactly - 1,501 ml in 50 ml kegs exports as 30.02 and
     * re-imports as 1,501 - and a non-terminating quotient comes back within half a stock unit,
     * which HALF_UP then resolves to the original figure.
     */
    private BigDecimal inSheetUnits(Product product, BigDecimal stockUnits) {
        UnitOption option =
                countedIn(product.getUnitOfMeasure(), product.getPackagingUnit(), product.getPackagingSize());
        if (option.factorToStockUnit().compareTo(BigDecimal.ONE) == 0) {
            return stockUnits;
        }
        return stockUnits.divide(option.factorToStockUnit(), 6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /** One export cell, addressed by header name rather than by a hand-counted index. */
    private Cell cell(Row row, String header) {
        return row.createCell(ALL_HEADER_NAMES.indexOf(header));
    }

    private BigDecimal nonNegativeDecimal(String raw, int excelRow, String column, List<ProductRowError> errors) {
        Optional<BigDecimal> value = NumberValues.parseDecimal(raw);
        if (value.isEmpty()) {
            errors.add(new ProductRowError(excelRow, column, "must be a number"));
            return null;
        }
        if (value.get().signum() < 0) {
            errors.add(new ProductRowError(excelRow, column, "must be a positive number"));
            return null;
        }
        return value.get();
    }

    private String value(SheetTable table, SheetRow row, String header) {
        return table.value(row, header);
    }

    /**
     * Which header to read one column from: its current name when the file has it, otherwise the
     * old name it was renamed from ({@link #HEADER_ALIASES}, reversed).
     *
     * <p>Current name wins, deliberately. A file carrying both {@code opening_stock} and
     * {@code quantity_on_hand} is a hand-merged sheet or an export from a tool that kept the old
     * column; in both cases the newer column is the one whose header the user was looking at when
     * they typed under it.
     */
    private String headerFor(SheetTable table, String field) {
        if (table.hasColumn(field)) {
            return field;
        }
        return HEADER_ALIASES.entrySet().stream()
                .filter(entry -> entry.getValue().equals(field) && table.hasColumn(entry.getKey()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(field);
    }


    private List<ProductRowError> missingHeaderErrors(List<String> missingHeaders) {
        return missingHeaders.stream()
                .map(header -> new ProductRowError(
                        1, header, "Required column '" + header + "' is missing from the uploaded file"))
                .toList();
    }

    /**
     * The headline case, and the one the whole sheet turns on: a product counted in millilitres,
     * bought and sold by the 50 ml keg, with <b>30</b> in {@code opening_stock} - thirty KEGS,
     * 1,500 ml (UNIT_UX_CONTRACT.md section 9.1).
     *
     * <h2>Why the numbers are these numbers</h2>
     * The example row is where people learn what to type; nobody reads a header comment twice,
     * but everybody looks at the row above the one they are filling in. So this row is written
     * to be unreadable in any other way. {@code opening_stock} is 30 beside a pack of 50, which
     * is impossible to mistake for a stock-unit figure - a shelf holding 30 ml of anything is
     * not a shelf. {@code low_stock_alert_at} is 5, five kegs, which shows the second column
     * following the same rule. {@code cost_price} is 9.50 and the header says "for ONE ml, not
     * for a whole keg", which shows the one column that deliberately does not (section 9.2).
     *
     * <p>The previous version of this row did the opposite on purpose: it carried 1,000 beside a
     * 50 kg bag "so the example itself demonstrates the rule the column comment states - the
     * number is in the stock unit, not in packs". That rule is the one section 9 reversed, after
     * a user described the opposite model in their own words and turned out to be right. The old
     * reasoning is recorded here rather than deleted, because it was sound about its own rule.
     *
     * <p>See {@link #exampleRowTwo} for the complementary no-pack shape. Between the two, both
     * readings of {@code opening_stock} are visible from the template alone.
     *
     * <p>When the tenant has suppliers on file, the first one is used to demonstrate the vendor
     * columns with a name the user will actually recognise. When they have none, those columns are
     * left blank rather than filled with an invented supplier - an example row naming a company
     * that does not exist would be the one example that teaches the wrong thing.
     */
    private Map<String, String> exampleRowOne(List<String> vendorNames) {
        Map<String, String> values = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("name", "Sample Widget"),
                Map.entry("sku", "EXAMPLE-SKU-DELETE-ME-1"),
                Map.entry("description", "Delete this row, or leave it - example rows are skipped automatically"),
                Map.entry("stock_unit", "Milliliter (ml)"),
                Map.entry("pack", "Keg"),
                Map.entry("units_per_pack", "50"),
                Map.entry("opening_stock", "30"),
                Map.entry("low_stock_alert_at", "5"),
                Map.entry("cost_price", "9.50"),
                Map.entry("unit_price", "19.99")));
        if (!vendorNames.isEmpty()) {
            values.put("vendor_name", vendorNames.get(0));
            values.put("vendor_sku", "THEIR-CODE-1");
            values.put("is_preferred_vendor", "TRUE");
        }
        return values;
    }

    /**
     * The complementary shape: <b>no pack at all</b>, so its {@code opening_stock} of 600 is 600
     * pieces - the other half of section 9.1's rule, and the case that must keep behaving exactly
     * as it always has.
     *
     * <p>Leaving {@code pack} and {@code units_per_pack} empty is also what shows that they are
     * optional: only {@code stock_unit} is ever required to stand alone. Its vendor columns are
     * left blank on purpose, which demonstrates the other half of the vendor rule - they are all
     * optional too.
     */
    private Map<String, String> exampleRowTwo() {
        return Map.ofEntries(
                Map.entry("name", "Sample Gadget"),
                Map.entry("sku", "EXAMPLE-SKU-DELETE-ME-2"),
                Map.entry("description", "Delete this row, or leave it - example rows are skipped automatically"),
                Map.entry("stock_unit", "Piece"),
                Map.entry("opening_stock", "600"),
                Map.entry("low_stock_alert_at", "50"),
                Map.entry("cost_price", "20.00"),
                Map.entry("unit_price", "49.99"));
    }

    /**
     * Example values are written as text, not as numbers, and that is deliberate: they are
     * illustrations, and a number in a formatted column would be indistinguishable from a real
     * value the user had entered. The italic-grey style says the same thing visually.
     */
    private void writeExampleRow(
            WorkbookBuilder builder, int rowIndex, List<String> headers, Map<String, String> valuesByHeader) {
        Row row = builder.sheet().createRow(rowIndex);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            String value = valuesByHeader.get(headers.get(i));
            if (value != null) {
                cell.setCellValue(value);
            }
            cell.setCellStyle(builder.exampleStyle());
        }
    }

    /**
     * Money columns get thousands-and-two-decimals, counted columns get whole thousands, and
     * both quantity columns and units_per_pack get an optional-decimals format, because half a
     * pack is a real thing on all three since section 9.1. See
     * {@code WorkbookBuilder}'s javadoc for why this is a parsing feature rather than decoration.
     */
    private void applyNumberFormats(WorkbookBuilder builder, List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            switch (headers.get(i)) {
                case "unit_price", "cost_price" -> builder.formatColumn(i, builder.moneyStyle());
                // opening_stock and low_stock_alert_at moved from the whole-number format to the
                // optional-decimals one with UNIT_UX_CONTRACT.md section 9.1: they count PACKS
                // now, and "30.5 kegs" is an ordinary shelf that the thousands-integer format
                // would have displayed as 31 while storing 30.5 - a cell that disagrees with
                // itself is worse than no formatting at all.
                case "opening_stock", "low_stock_alert_at", "units_per_pack" ->
                        builder.formatColumn(i, builder.decimalStyle());
                case "is_preferred_vendor" -> builder.formatColumn(i, builder.centeredStyle());
                default -> {
                    // Text columns keep the General format. Deliberately including sku: forcing a
                    // format on it would turn a numeric-looking SKU like 00123 into 123.
                }
            }
        }
    }

    private void addDropdown(
            WorkbookBuilder builder, List<String> headers, String header, String definedName, String title, String text) {
        int columnIndex = headers.indexOf(header);
        if (columnIndex >= 0) {
            builder.addDropdown(columnIndex, definedName, title, text);
        }
    }

    /**
     * Dropdown entries and exported cells are human labels - {@code "Kilogram (kg)"},
     * {@code "Bag"} - never the internal codes {@code KG} and {@code BAG}. Contract section 7,
     * non-negotiable 4: no spreadsheet cell contains an internal code. This rule was already
     * honoured in the web UI and broken in the artifact users spend their time in
     * (UNIT_UX_REMEDIATION_PLAN.md P3-4).
     *
     * <p>Safe in both directions because {@code UnitOfMeasure.fromCodeOrLabel} has always accepted
     * the display label verbatim - {@code UnitOfMeasureAliases} derives it from
     * {@link UnitOfMeasure#label()} rather than listing it, so the two can never drift.
     */
    private List<String> labelsOf(List<UnitOfMeasure> units) {
        return units.stream().map(UnitOfMeasure::label).toList();
    }

    /**
     * A stored unit code as the label a person reads, or blank when the product has no unit set -
     * the pre-V17 row that never got one. Falls back to the code itself for a value that is somehow
     * not on the fixed list, because dropping it would silently empty a column on a round trip.
     */
    private String unitLabelOrBlank(String code) {
        if (code == null) {
            return "";
        }
        return UnitOfMeasure.fromCode(code).map(UnitOfMeasure::label).orElse(code);
    }

    /**
     * Per-column comments, with {@code unit_price}'s composed for the tenant kind. A seller is told
     * it is their marketplace selling price and required; a company never sees the column at all,
     * so the entry is simply absent from their map.
     */
    private Map<String, String> headerCommentsFor(boolean isSeller) {
        Map<String, String> comments = new LinkedHashMap<>(HEADER_COMMENTS);
        if (isSeller) {
            comments.put("unit_price", "Your marketplace selling price. Required for every product you list.");
        }
        comments.put(
                "name",
                HEADER_COMMENTS.get("name") + " Rows written in grey are examples - delete them, or leave them, "
                        + "any row whose sku starts with '" + EXAMPLE_SKU_MARKER_PREFIX + "' is skipped automatically.");
        return comments;
    }
}
