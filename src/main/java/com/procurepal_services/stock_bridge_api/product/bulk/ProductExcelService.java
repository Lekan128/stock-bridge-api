package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.imports.io.LookupSheetWriter;
import com.procurepal_services.stock_bridge_api.imports.io.NumberValues;
import com.procurepal_services.stock_bridge_api.imports.io.SheetRow;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReadException;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
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
 * column - including the newer {@code unit_of_measure}/{@code packaging_unit}/
 * {@code packaging_size} trio and the {@code vendor_name}/{@code vendor_sku}/
 * {@code is_preferred_vendor} trio after it - is the same for both tenant kinds.
 *
 * <h2>The column-order stability promise, and what it survived</h2>
 * The first ten columns are in the order they have always been in, and new columns are appended
 * rather than slotted in next to the fields they relate to. Tenants have downloaded and saved
 * copies of this template; a vendor comparing last quarter's saved spreadsheet against a freshly
 * downloaded one has to find their columns in the same places. That is why {@code vendor_name}
 * sits at position eleven rather than next to {@code cost_price}, where it would arguably read
 * better - the same reasoning that put the unit-of-measure trio at the end instead of beside the
 * other stock columns. BULK_IMPORT_CONTRACT.md section 5 freezes this order for both sides of the
 * wire, because a field key and its column header are the same string.
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
            "name", "sku", "description", "unit_price", "cost_price", "quantity_on_hand",
            "low_stock_threshold", "unit_of_measure", "packaging_unit", "packaging_size",
            "vendor_name", "vendor_sku", "is_preferred_vendor");

    private static final Map<String, Integer> COLUMN_WIDTHS_CHARS_BY_HEADER = Map.ofEntries(
            Map.entry("name", 28),
            Map.entry("sku", 18),
            Map.entry("description", 42),
            Map.entry("unit_price", 14),
            Map.entry("cost_price", 14),
            Map.entry("quantity_on_hand", 18),
            Map.entry("low_stock_threshold", 20),
            Map.entry("unit_of_measure", 18),
            Map.entry("packaging_unit", 18),
            Map.entry("packaging_size", 16),
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
     */
    private static final Map<String, String> HEADER_COMMENTS = Map.ofEntries(
            Map.entry("name", "What you call this product. Required."),
            Map.entry("sku", "Your own code for this product - it must be unique in your catalog. Required."),
            Map.entry("description", "Optional. Anything you want on the product page."),
            Map.entry("cost_price", "Optional. What you pay for one unit. If the row also has a quantity, this becomes that stock's cost."),
            Map.entry("quantity_on_hand", "Optional. How much you have right now. Leave blank if none yet - it is recorded as an opening stock entry."),
            Map.entry("low_stock_threshold", "Optional. We warn you when stock falls to this number."),
            Map.entry("unit_of_measure", "What one unit is measured in - Kg, Liter, Piece. Pick from the list. Required if you fill in packaging."),
            Map.entry("packaging_unit", "Optional. How it is packaged - Bag, Carton, Drum. Pick from the list."),
            Map.entry("packaging_size", "Optional. How many units are in one package. 'KG + BAG + 50' means a 50kg bag."),
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
            addDropdown(builder, headers, "unit_of_measure",
                    lookups.addList(BASE_UNITS_RANGE, codesOf(UnitOfMeasure.baseUnits())),
                    "Unit of measure",
                    "Pick a unit from the list, or type one - we understand kg, kilo, bags, ctn and most other "
                            + "spellings. If we cannot work it out we will ask you after you upload.");
            addDropdown(builder, headers, "packaging_unit",
                    lookups.addList(PACKAGING_UNITS_RANGE, codesOf(UnitOfMeasure.packagingUnits())),
                    "Packaging unit",
                    "Pick how this product is packaged - Bag, Carton, Drum and so on. Leave blank if it is sold loose.");
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
                row.createCell(0).setCellValue(product.getName());
                row.createCell(1).setCellValue(product.getSku());
                row.createCell(2).setCellValue(product.getDescription() == null ? "" : product.getDescription());
                // Nullable since V17 (a buying company's product has no selling price to give)
                // - null-guarded the same way costPrice below it always has been, so an export
                // of a company's own catalog does not NPE on a row with nothing in this column.
                if (product.getUnitPrice() != null) {
                    row.createCell(3).setCellValue(product.getUnitPrice().doubleValue());
                }
                if (product.getCostPrice() != null) {
                    row.createCell(4).setCellValue(product.getCostPrice().doubleValue());
                }
                row.createCell(5).setCellValue(product.getQuantityOnHand());
                if (product.getLowStockThreshold() != null) {
                    row.createCell(6).setCellValue(product.getLowStockThreshold());
                }
                row.createCell(7).setCellValue(product.getUnitOfMeasure() == null ? "" : product.getUnitOfMeasure());
                row.createCell(8).setCellValue(product.getPackagingUnit() == null ? "" : product.getPackagingUnit());
                if (product.getPackagingSize() != null) {
                    row.createCell(9).setCellValue(product.getPackagingSize().doubleValue());
                }
                ProductVendorSnapshot vendor = preferredVendorsByProductId.get(product.getId());
                if (vendor != null) {
                    row.createCell(10).setCellValue(vendor.vendorName() == null ? "" : vendor.vendorName());
                    row.createCell(11).setCellValue(vendor.vendorSku() == null ? "" : vendor.vendorSku());
                    if (vendor.preferred()) {
                        row.createCell(12).setCellValue("TRUE");
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

        int quantityOnHand = 0;
        String rawQuantity = value(table, row, "quantity_on_hand");
        if (rawQuantity != null) {
            Integer parsed = nonNegativeInteger(rawQuantity, excelRow, "quantity_on_hand", errors);
            quantityOnHand = parsed == null ? 0 : parsed;
        }

        Integer lowStockThreshold = null;
        String rawThreshold = value(table, row, "low_stock_threshold");
        if (rawThreshold != null) {
            lowStockThreshold = nonNegativeInteger(rawThreshold, excelRow, "low_stock_threshold", errors);
        }

        // unit_of_measure/packaging_unit/packaging_size: optional for every tenant kind, but
        // cross-validated - see ProductManagementService.requirePackagingUnitAndSizePaired and
        // .requirePackagingImpliesUnitOfMeasure for the same rules on the manual path. Presence
        // of each is tracked from the raw cell (before validation), not from the resolved
        // value, so an invalid code in one column doesn't ALSO trigger a misleading "requires"
        // error against another column that was in fact supplied correctly.
        String rawUnitOfMeasure = value(table, row, "unit_of_measure");
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
                        "unit_of_measure",
                        "'" + rawUnitOfMeasure + "' is not a recognized unit of measure"
                                + wrongRoleHint(rawUnitOfMeasure, UnitOfMeasureRole.BASE)));
            }
        }

        // packaging_unit mirrors unit_of_measure's parse but requires the PACKAGING role - a
        // code that exists but is BASE-role (e.g. "KG" submitted here) gets the same "not
        // recognized" treatment as a code that is not on the list at all, matching
        // ProductManagementService.resolvePackagingUnit/InvalidUnitOfMeasureException on the
        // manual path - now with a hint naming the column it does belong in, since we know.
        String rawPackagingUnit = value(table, row, "packaging_unit");
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
                        "packaging_unit",
                        "'" + rawPackagingUnit + "' is not a recognized packaging unit"
                                + wrongRoleHint(rawPackagingUnit, UnitOfMeasureRole.PACKAGING)));
            }
        }

        String rawPackagingSize = value(table, row, "packaging_size");
        boolean packagingSizeProvided = rawPackagingSize != null;
        BigDecimal packagingSize = null;
        if (packagingSizeProvided) {
            packagingSize = nonNegativeDecimal(rawPackagingSize, excelRow, "packaging_size", errors);
        }

        // packaging_unit/packaging_size travel together - either both present or neither - same
        // symmetric pairing rule as ProductManagementService.requirePackagingUnitAndSizePaired.
        if (packagingUnitProvided != packagingSizeProvided) {
            if (packagingUnitProvided) {
                errors.add(new ProductRowError(excelRow, "packaging_unit", "packaging_unit requires packaging_size"));
            } else {
                errors.add(new ProductRowError(excelRow, "packaging_size", "packaging_size requires packaging_unit"));
            }
        }

        // Whenever packaging is present, unit_of_measure must also be present - one-directional,
        // same as ProductManagementService.requirePackagingImpliesUnitOfMeasure /
        // PackagingRequiresUnitOfMeasureException on the manual path. unit_of_measure alone (no
        // packaging) stays fully valid.
        if (!unitOfMeasureProvided) {
            if (packagingUnitProvided) {
                errors.add(new ProductRowError(excelRow, "packaging_unit", "packaging_unit requires unit_of_measure"));
            }
            if (packagingSizeProvided) {
                errors.add(new ProductRowError(excelRow, "packaging_size", "packaging_size requires unit_of_measure"));
            }
        }

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
     * "BAG is a packaging unit - put it in the packaging_unit column" appended to the not-recognized
     * message when the value IS a real unit, just for the other column. Costs one extra lookup and
     * turns "we don't recognise this" - which is not even true - into an instruction.
     */
    private String wrongRoleHint(String rawValue, UnitOfMeasureRole expectedRole) {
        UnitOfMeasureRole otherRole =
                expectedRole == UnitOfMeasureRole.BASE ? UnitOfMeasureRole.PACKAGING : UnitOfMeasureRole.BASE;
        return UnitOfMeasure.fromCodeOrLabel(rawValue, otherRole)
                .map(unit -> " - " + unit.label() + " belongs in the "
                        + (otherRole == UnitOfMeasureRole.PACKAGING ? "packaging_unit" : "unit_of_measure")
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

    private Integer nonNegativeInteger(String raw, int excelRow, String column, List<ProductRowError> errors) {
        Optional<BigDecimal> value = NumberValues.parseDecimal(raw);
        if (value.isEmpty()) {
            errors.add(new ProductRowError(excelRow, column, "must be a whole number"));
            return null;
        }
        BigDecimal number = value.get();
        if (number.signum() < 0 || number.stripTrailingZeros().scale() > 0) {
            errors.add(new ProductRowError(excelRow, column, "must be a non-negative whole number"));
            return null;
        }
        // Bounded before conversion rather than letting intValueExact throw: a cell holding a
        // twenty-digit number is a mistyped value, not a server fault, and it has to come back as
        // a row error like every other bad number rather than as a parse failure for the file.
        if (number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            errors.add(new ProductRowError(excelRow, column, "is too large"));
            return null;
        }
        return number.intValue();
    }

    private String value(SheetTable table, SheetRow row, String header) {
        return table.value(row, header);
    }

    private List<ProductRowError> missingHeaderErrors(List<String> missingHeaders) {
        return missingHeaders.stream()
                .map(header -> new ProductRowError(
                        1, header, "Required column '" + header + "' is missing from the uploaded file"))
                .toList();
    }

    /**
     * Demonstrates the full three-field "packaged good" case: measured in Kg, packaged as a
     * Bag, 50 Kg per Bag - i.e. a 50kg bag. See {@link #exampleRowTwo} for the complementary
     * "carton of loose-counted pieces" pattern - between the two, both realistic shapes of the
     * model (a weight/volume/length packaged in a container, and a piece-count packaged in a
     * container) are visible from the template alone.
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
                Map.entry("unit_price", "19.99"),
                Map.entry("cost_price", "9.50"),
                Map.entry("quantity_on_hand", "100"),
                Map.entry("low_stock_threshold", "10"),
                Map.entry("unit_of_measure", "KG"),
                Map.entry("packaging_unit", "BAG"),
                Map.entry("packaging_size", "50")));
        if (!vendorNames.isEmpty()) {
            values.put("vendor_name", vendorNames.get(0));
            values.put("vendor_sku", "THEIR-CODE-1");
            values.put("is_preferred_vendor", "TRUE");
        }
        return values;
    }

    /**
     * Demonstrates "a carton of 24 pieces": measured in the generic Piece unit, packaged as a
     * Carton, 24 pieces per Carton. Also shows the equally valid "no packaging at all" shape is
     * reachable by simply leaving packaging_unit/packaging_size blank - only unit_of_measure is
     * ever required to stand alone. Its vendor columns are left blank on purpose, which
     * demonstrates the other half of the vendor rule: they are all optional.
     */
    private Map<String, String> exampleRowTwo() {
        return Map.ofEntries(
                Map.entry("name", "Sample Gadget"),
                Map.entry("sku", "EXAMPLE-SKU-DELETE-ME-2"),
                Map.entry("description", "Delete this row, or leave it - example rows are skipped automatically"),
                Map.entry("unit_price", "49.99"),
                Map.entry("cost_price", "20.00"),
                Map.entry("quantity_on_hand", "25"),
                Map.entry("low_stock_threshold", "5"),
                Map.entry("unit_of_measure", "PIECE"),
                Map.entry("packaging_unit", "CARTON"),
                Map.entry("packaging_size", "24"));
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
     * packaging_size gets an optional-decimals format because half a bag is a real thing. See
     * {@code WorkbookBuilder}'s javadoc for why this is a parsing feature rather than decoration.
     */
    private void applyNumberFormats(WorkbookBuilder builder, List<String> headers) {
        for (int i = 0; i < headers.size(); i++) {
            switch (headers.get(i)) {
                case "unit_price", "cost_price" -> builder.formatColumn(i, builder.moneyStyle());
                case "quantity_on_hand", "low_stock_threshold" -> builder.formatColumn(i, builder.quantityStyle());
                case "packaging_size" -> builder.formatColumn(i, builder.decimalStyle());
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

    private List<String> codesOf(List<UnitOfMeasure> units) {
        return units.stream().map(UnitOfMeasure::code).toList();
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
