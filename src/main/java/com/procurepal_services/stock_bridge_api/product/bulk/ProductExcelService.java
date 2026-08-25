package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Owns the .xlsx column layout shared by the template, export, and bulk-upload
 * parser - header names/order live only here so all three stay in sync. Has
 * no repository dependency: DB-backed checks (sku-already-exists) are the
 * caller's job (see ProductManagementService.bulkUpload), since this class
 * only knows about spreadsheet structure, not tenant data. Tenant/seller
 * status itself is also the caller's job - {@code isSeller} arrives here as a
 * plain boolean resolved by {@code ProductManagementService} (via
 * {@code SellerDirectory}/{@code Client.canSell()}), so this class never
 * reaches into tenant or security context on its own.
 *
 * <h2>unit_price is the one tenant-conditional column</h2>
 * A buying company (non-seller) has no selling price at all, so its template
 * does not even show a {@code unit_price} column, and an upload from a
 * company is never required to fill one in. A seller's template keeps
 * {@code unit_price} as a required column, exactly as before. Every other
 * column - including the newer {@code unit_of_measure}/{@code packaging_unit}/
 * {@code packaging_size} trio - is the same for both tenant kinds.
 *
 * Example-row convention: the two example rows written by generateTemplate()
 * use a reserved SKU prefix (EXAMPLE_SKU_MARKER_PREFIX). parse() silently
 * skips any row whose sku starts with that marker, so a user who forgets to
 * delete the example rows before uploading doesn't get spurious errors -
 * chosen over requiring manual deletion because it's strictly more forgiving
 * and costs nothing extra to implement.
 */
@Service
public class ProductExcelService {

    /**
     * Every column this class knows about, in canonical order. Template and parse both
     * derive their working column list FROM this one (filtering {@code unit_price} out for a
     * non-seller's template) rather than keeping a second copy, so the two can never drift
     * apart on order or naming. {@code unit_of_measure}/{@code packaging_unit}/
     * {@code packaging_size} are appended at the end rather than inserted next to the other
     * pricing/stock columns, so a vendor's existing saved spreadsheet - built against the
     * pre-existing column order - still lines up its other columns the same way if compared
     * side by side with a freshly downloaded template.
     */
    private static final List<String> ALL_HEADER_NAMES = List.of(
            "name", "sku", "description", "unit_price", "cost_price", "quantity_on_hand",
            "low_stock_threshold", "unit_of_measure", "packaging_unit", "packaging_size");

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
            Map.entry("packaging_size", 16));

    static final String EXAMPLE_SKU_MARKER_PREFIX = "EXAMPLE-SKU-DELETE-ME";

    /**
     * Zero-width and non-breaking space characters that spreadsheet apps
     * (Excel/Numbers/Sheets) commonly leave behind in "empty-looking" cells
     * when formatting or a fill handle is dragged past the last real row.
     * Java's String.isBlank()/trim() don't treat these as whitespace, so
     * without stripping them such cells read as non-blank and get parsed as
     * real data.
     */
    private static final Pattern INVISIBLE_CHARACTERS = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00A0]");

    /**
     * The columns a template/upload for this tenant kind actually has. {@code unit_price} is
     * the only column that ever drops out - a non-seller (buying company) has no selling price
     * field at all, so showing it (even as optional) invites the question "should I fill this
     * in?" for a field that means nothing on a company's own private stock.
     */
    private List<String> headerNamesFor(boolean isSeller) {
        return isSeller
                ? ALL_HEADER_NAMES
                : ALL_HEADER_NAMES.stream().filter(h -> !h.equals("unit_price")).toList();
    }

    /**
     * {@code name}/{@code sku} are always required. {@code unit_price} joins them only for a
     * seller, whose product IS a listing - see {@code UnitPriceRequiredException} for the same
     * rule on the manual create/update path. {@code unit_of_measure}/{@code packaging_unit}/
     * {@code packaging_size} are never in this list: all three are optional for every tenant
     * kind, cross-validated by {@link #parseRow} rather than by header presence.
     */
    private List<String> requiredHeadersFor(boolean isSeller) {
        return isSeller ? List.of("name", "sku", "unit_price") : List.of("name", "sku");
    }

    private List<Integer> columnWidthsFor(List<String> headers) {
        return headers.stream().map(COLUMN_WIDTHS_CHARS_BY_HEADER::get).toList();
    }

    public byte[] generateTemplate(boolean isSeller) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            List<String> headers = headerNamesFor(isSeller);
            writeHeaderRow(workbook, sheet, headers);
            writeExampleRow(workbook, sheet, 1, headers, exampleRowOne());
            writeExampleRow(workbook, sheet, 2, headers, exampleRowTwo());
            sheet.createFreezePane(0, 1);
            addExplanatoryComment(workbook, sheet, isSeller);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate product import template", e);
        }
    }

    /**
     * Demonstrates the full three-field "packaged good" case: measured in Kg, packaged as a
     * Bag, 50 Kg per Bag - i.e. a 50kg bag. See {@link #exampleRowTwo} for the complementary
     * "carton of loose-counted pieces" pattern - between the two, both realistic shapes of the
     * model (a weight/volume/length packaged in a container, and a piece-count packaged in a
     * container) are visible from the template alone.
     */
    private Map<String, String> exampleRowOne() {
        return Map.ofEntries(
                Map.entry("name", "Sample Widget"),
                Map.entry("sku", "EXAMPLE-SKU-DELETE-ME-1"),
                Map.entry("description", "Delete this row, or leave it - example rows are skipped automatically"),
                Map.entry("unit_price", "19.99"),
                Map.entry("cost_price", "9.50"),
                Map.entry("quantity_on_hand", "100"),
                Map.entry("low_stock_threshold", "10"),
                Map.entry("unit_of_measure", "KG"),
                Map.entry("packaging_unit", "BAG"),
                Map.entry("packaging_size", "50"));
    }

    /**
     * Demonstrates "a carton of 24 pieces": measured in the generic Piece unit, packaged as a
     * Carton, 24 pieces per Carton. Also shows the equally valid "no packaging at all" shape is
     * reachable by simply leaving packaging_unit/packaging_size blank - only unit_of_measure is
     * ever required to stand alone.
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
     * Not tenant-conditional the way the template is: an export shows whatever is actually on
     * each product row, and both a company and a seller can have real unit_price/unit_of_measure/
     * packaging_unit/packaging_size values now (unit_price for a seller, the other three for
     * either). Always writes the full column set.
     */
    public byte[] exportProducts(List<Product> products) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            writeHeaderRow(workbook, sheet, ALL_HEADER_NAMES);
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
            }
            sheet.createFreezePane(0, 1);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate product export", e);
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
     */
    public List<ParsedProductRow> parse(MultipartFile file, boolean isSeller) {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BulkUploadValidationException(missingHeaderErrors(headerNamesFor(isSeller)));
            }

            Map<String, Integer> columns = readHeaderColumns(headerRow);
            List<String> missing =
                    requiredHeadersFor(isSeller).stream().filter(h -> !columns.containsKey(h)).toList();
            if (!missing.isEmpty()) {
                throw new BulkUploadValidationException(missingHeaderErrors(missing));
            }

            List<ProductRowError> errors = new ArrayList<>();
            List<ParsedProductRow> rows = new ArrayList<>();
            Map<String, Integer> skuFirstSeenAtRow = new LinkedHashMap<>();

            int lastRow = sheet.getLastRowNum();
            for (int rowIndex = 1; rowIndex <= lastRow; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                int excelRow = rowIndex + 1;
                if (row == null || isBlankRow(row, columns)) {
                    continue;
                }

                String sku = stringValue(row, columns.get("sku"));
                if (sku != null && sku.toUpperCase().startsWith(EXAMPLE_SKU_MARKER_PREFIX)) {
                    continue;
                }

                ParsedProductRow parsed = parseRow(row, columns, excelRow, errors, isSeller);
                if (parsed == null) {
                    continue;
                }

                Integer firstSeenAt = skuFirstSeenAtRow.putIfAbsent(parsed.sku().toUpperCase(), excelRow);
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
        } catch (IOException | RuntimeException e) {
            if (e instanceof BulkUploadValidationException validationException) {
                throw validationException;
            }
            throw new BulkUploadValidationException(
                    List.of(new ProductRowError(0, "file", "The uploaded file is not a valid .xlsx file")));
        }
    }

    private ParsedProductRow parseRow(
            Row row, Map<String, Integer> columns, int excelRow, List<ProductRowError> errors, boolean isSeller) {
        int errorsBefore = errors.size();

        String name = stringValue(row, columns.get("name"));
        if (name == null) {
            errors.add(new ProductRowError(excelRow, "name", "is required"));
        }

        String sku = stringValue(row, columns.get("sku"));
        if (sku == null) {
            errors.add(new ProductRowError(excelRow, "sku", "is required"));
        }

        String description = stringValue(row, columns.get("description"));

        // unit_price is required only for a seller's upload - see class javadoc and
        // ProductManagementService.create's UnitPriceRequiredException for the same rule on
        // the manual path. A non-seller's blank cell is fine; a non-blank one is still parsed
        // (so a stray value doesn't error) but is discarded downstream and never stored.
        BigDecimal unitPrice = null;
        Cell unitPriceCell = cellAt(row, columns.get("unit_price"));
        if (unitPriceCell == null || isCellBlank(unitPriceCell)) {
            if (isSeller) {
                errors.add(new ProductRowError(excelRow, "unit_price", "is required"));
            }
        } else {
            unitPrice = nonNegativeDecimal(unitPriceCell, excelRow, "unit_price", errors);
        }

        BigDecimal costPrice = null;
        Cell costPriceCell = cellAt(row, columns.get("cost_price"));
        if (costPriceCell != null && !isCellBlank(costPriceCell)) {
            costPrice = nonNegativeDecimal(costPriceCell, excelRow, "cost_price", errors);
        }

        int quantityOnHand = 0;
        Cell quantityCell = cellAt(row, columns.get("quantity_on_hand"));
        if (quantityCell != null && !isCellBlank(quantityCell)) {
            Integer parsed = nonNegativeInteger(quantityCell, excelRow, "quantity_on_hand", errors);
            quantityOnHand = parsed == null ? 0 : parsed;
        }

        Integer lowStockThreshold = null;
        Cell thresholdCell = cellAt(row, columns.get("low_stock_threshold"));
        if (thresholdCell != null && !isCellBlank(thresholdCell)) {
            lowStockThreshold = nonNegativeInteger(thresholdCell, excelRow, "low_stock_threshold", errors);
        }

        // unit_of_measure/packaging_unit/packaging_size: optional for every tenant kind, but
        // cross-validated - see ProductManagementService.requirePackagingUnitAndSizePaired and
        // .requirePackagingImpliesUnitOfMeasure for the same rules on the manual path. Presence
        // of each is tracked from the raw cell (before validation), not from the resolved
        // value, so an invalid code in one column doesn't ALSO trigger a misleading "requires"
        // error against another column that was in fact supplied correctly.
        Cell unitOfMeasureCell = cellAt(row, columns.get("unit_of_measure"));
        boolean unitOfMeasureProvided = unitOfMeasureCell != null && !isCellBlank(unitOfMeasureCell);
        String unitOfMeasure = null;
        if (unitOfMeasureProvided) {
            String rawUnitOfMeasure = stringValue(unitOfMeasureCell);
            Optional<UnitOfMeasure> resolved =
                    UnitOfMeasure.fromCode(rawUnitOfMeasure).filter(unit -> unit.role() == UnitOfMeasureRole.BASE);
            if (resolved.isPresent()) {
                unitOfMeasure = resolved.get().code();
            } else {
                errors.add(new ProductRowError(
                        excelRow, "unit_of_measure", "'" + rawUnitOfMeasure + "' is not a recognized unit of measure"));
            }
        }

        // packaging_unit mirrors unit_of_measure's parse but requires the PACKAGING role - a
        // code that exists but is BASE-role (e.g. "KG" submitted here) gets the same "not
        // recognized" treatment as a code that is not on the list at all, matching
        // ProductManagementService.resolvePackagingUnit/InvalidUnitOfMeasureException on the
        // manual path.
        Cell packagingUnitCell = cellAt(row, columns.get("packaging_unit"));
        boolean packagingUnitProvided = packagingUnitCell != null && !isCellBlank(packagingUnitCell);
        String packagingUnit = null;
        if (packagingUnitProvided) {
            String rawPackagingUnit = stringValue(packagingUnitCell);
            Optional<UnitOfMeasure> resolved = UnitOfMeasure.fromCode(rawPackagingUnit)
                    .filter(unit -> unit.role() == UnitOfMeasureRole.PACKAGING);
            if (resolved.isPresent()) {
                packagingUnit = resolved.get().code();
            } else {
                errors.add(new ProductRowError(
                        excelRow, "packaging_unit", "'" + rawPackagingUnit + "' is not a recognized packaging unit"));
            }
        }

        Cell packagingSizeCell = cellAt(row, columns.get("packaging_size"));
        boolean packagingSizeProvided = packagingSizeCell != null && !isCellBlank(packagingSizeCell);
        BigDecimal packagingSize = null;
        if (packagingSizeProvided) {
            packagingSize = nonNegativeDecimal(packagingSizeCell, excelRow, "packaging_size", errors);
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

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new ParsedProductRow(
                excelRow, name, sku, description, unitPrice, costPrice, quantityOnHand, lowStockThreshold,
                unitOfMeasure, packagingUnit, packagingSize);
    }

    private BigDecimal nonNegativeDecimal(Cell cell, int excelRow, String column, List<ProductRowError> errors) {
        Double value = numericValue(cell);
        if (value == null) {
            errors.add(new ProductRowError(excelRow, column, "must be a number"));
            return null;
        }
        if (value < 0) {
            errors.add(new ProductRowError(excelRow, column, "must be a positive number"));
            return null;
        }
        return BigDecimal.valueOf(value);
    }

    private Integer nonNegativeInteger(Cell cell, int excelRow, String column, List<ProductRowError> errors) {
        Double value = numericValue(cell);
        if (value == null) {
            errors.add(new ProductRowError(excelRow, column, "must be a whole number"));
            return null;
        }
        if (value < 0 || value != Math.floor(value)) {
            errors.add(new ProductRowError(excelRow, column, "must be a non-negative whole number"));
            return null;
        }
        return (int) (double) value;
    }

    private Double numericValue(Cell cell) {
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        if (cell.getCellType() == CellType.STRING) {
            try {
                return Double.parseDouble(cleanString(cell.getStringCellValue()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private Map<String, Integer> readHeaderColumns(Row headerRow) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (Cell cell : headerRow) {
            String value = stringValue(cell);
            if (value != null) {
                String normalized = value.toLowerCase().trim();
                if (ALL_HEADER_NAMES.contains(normalized)) {
                    columns.put(normalized, cell.getColumnIndex());
                }
            }
        }
        return columns;
    }

    private List<ProductRowError> missingHeaderErrors(List<String> missingHeaders) {
        return missingHeaders.stream()
                .map(header -> new ProductRowError(1, header, "Required column '" + header + "' is missing from the uploaded file"))
                .toList();
    }

    private boolean isBlankRow(Row row, Map<String, Integer> columns) {
        return stringValue(row, columns.get("name")) == null && stringValue(row, columns.get("sku")) == null;
    }

    private Cell cellAt(Row row, Integer columnIndex) {
        return columnIndex == null ? null : row.getCell(columnIndex);
    }

    private boolean isCellBlank(Cell cell) {
        return cell.getCellType() == CellType.BLANK
                || (cell.getCellType() == CellType.STRING && cleanString(cell.getStringCellValue()).isEmpty());
    }

    private String cleanString(String value) {
        return INVISIBLE_CHARACTERS.matcher(value).replaceAll("").trim();
    }

    private String stringValue(Row row, Integer columnIndex) {
        Cell cell = cellAt(row, columnIndex);
        return cell == null ? null : stringValue(cell);
    }

    private String stringValue(Cell cell) {
        String value = switch (cell.getCellType()) {
            case STRING -> cleanString(cell.getStringCellValue());
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            default -> null;
        };
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void writeHeaderRow(Workbook workbook, Sheet sheet, List<String> headers) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);

        List<Integer> columnWidths = columnWidthsFor(headers);
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, columnWidths.get(i) * 256);
        }
    }

    private void writeExampleRow(
            Workbook workbook, Sheet sheet, int rowIndex, List<String> headers, Map<String, String> valuesByHeader) {
        Font italicGreyFont = workbook.createFont();
        italicGreyFont.setItalic(true);
        italicGreyFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle exampleStyle = workbook.createCellStyle();
        exampleStyle.setFont(italicGreyFont);

        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < headers.size(); i++) {
            Cell cell = row.createCell(i);
            String value = valuesByHeader.get(headers.get(i));
            if (value != null) {
                cell.setCellValue(value);
            }
            cell.setCellStyle(exampleStyle);
        }
    }

    private void addExplanatoryComment(Workbook workbook, Sheet sheet, boolean isSeller) {
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        anchor.setCol1(1);
        anchor.setRow1(0);
        anchor.setCol2(5);
        anchor.setRow2(6);

        String priceExplanation = isSeller
                ? "unit_price is your marketplace selling price and is required. "
                : "There is no unit_price column because pricing is not part of your product catalog - "
                        + "you only track cost_price, which stays optional. ";

        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(
                "Rows 2-3 are examples of the expected format. Delete them before uploading, "
                        + "or leave them - any row whose sku starts with '" + EXAMPLE_SKU_MARKER_PREFIX
                        + "' is skipped automatically. " + priceExplanation
                        + "unit_of_measure is what the product is fundamentally measured in (Kg, Liter, Piece, "
                        + "etc.) and may be set alone. packaging_unit and packaging_size are optional and "
                        + "describe how it is packaged - e.g. unit_of_measure=KG, packaging_unit=BAG, "
                        + "packaging_size=50 means a 50kg bag. packaging_unit and packaging_size must be provided "
                        + "together, or not at all, and both require unit_of_measure to also be set. Codes for "
                        + "both columns come from GET /api/products/units-of-measure - the role field there tells "
                        + "you which column each code belongs in (BASE codes go in unit_of_measure, PACKAGING "
                        + "codes go in packaging_unit)."));
        comment.setAuthor("Procure Paddy");
        sheet.getRow(0).getCell(1).setCellComment(comment);
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
