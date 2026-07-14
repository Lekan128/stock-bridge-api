package com.procurepal_services.stock_bridge_api.product.bulk;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * only knows about spreadsheet structure, not tenant data.
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

    private static final List<String> HEADER_NAMES =
            List.of("name", "sku", "description", "unit_price", "cost_price", "quantity_on_hand", "low_stock_threshold");
    private static final List<String> REQUIRED_HEADERS = List.of("name", "sku", "unit_price");
    private static final List<Integer> COLUMN_WIDTHS_CHARS = List.of(28, 18, 42, 14, 14, 18, 20);
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

    public byte[] generateTemplate() {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            writeHeaderRow(workbook, sheet);
            writeExampleRow(workbook, sheet, 1, "Sample Widget", "EXAMPLE-SKU-DELETE-ME-1",
                    "Delete this row, or leave it - example rows are skipped automatically", "19.99", "9.50", "100", "10");
            writeExampleRow(workbook, sheet, 2, "Sample Gadget", "EXAMPLE-SKU-DELETE-ME-2",
                    "Delete this row, or leave it - example rows are skipped automatically", "49.99", "20.00", "25", "5");
            sheet.createFreezePane(0, 1);
            addExplanatoryComment(workbook, sheet);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate product import template", e);
        }
    }

    public byte[] exportProducts(List<Product> products) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            writeHeaderRow(workbook, sheet);
            int rowIndex = 1;
            for (Product product : products) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(product.getName());
                row.createCell(1).setCellValue(product.getSku());
                row.createCell(2).setCellValue(product.getDescription() == null ? "" : product.getDescription());
                row.createCell(3).setCellValue(product.getUnitPrice().doubleValue());
                if (product.getCostPrice() != null) {
                    row.createCell(4).setCellValue(product.getCostPrice().doubleValue());
                }
                row.createCell(5).setCellValue(product.getQuantityOnHand());
                if (product.getLowStockThreshold() != null) {
                    row.createCell(6).setCellValue(product.getLowStockThreshold());
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
     */
    public List<ParsedProductRow> parse(MultipartFile file) {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BulkUploadValidationException(missingHeaderErrors(HEADER_NAMES));
            }

            Map<String, Integer> columns = readHeaderColumns(headerRow);
            List<String> missing = REQUIRED_HEADERS.stream().filter(h -> !columns.containsKey(h)).toList();
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

                ParsedProductRow parsed = parseRow(row, columns, excelRow, errors);
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

    private ParsedProductRow parseRow(Row row, Map<String, Integer> columns, int excelRow, List<ProductRowError> errors) {
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

        BigDecimal unitPrice = null;
        Cell unitPriceCell = cellAt(row, columns.get("unit_price"));
        if (unitPriceCell == null || isCellBlank(unitPriceCell)) {
            errors.add(new ProductRowError(excelRow, "unit_price", "is required"));
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

        if (errors.size() > errorsBefore) {
            return null;
        }
        return new ParsedProductRow(excelRow, name, sku, description, unitPrice, costPrice, quantityOnHand, lowStockThreshold);
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
                if (HEADER_NAMES.contains(normalized)) {
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

    private void writeHeaderRow(Workbook workbook, Sheet sheet) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(boldFont);

        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADER_NAMES.size(); i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(HEADER_NAMES.get(i));
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, COLUMN_WIDTHS_CHARS.get(i) * 256);
        }
    }

    private void writeExampleRow(
            Workbook workbook, Sheet sheet, int rowIndex, String name, String sku, String description,
            String unitPrice, String costPrice, String quantityOnHand, String lowStockThreshold) {
        Font italicGreyFont = workbook.createFont();
        italicGreyFont.setItalic(true);
        italicGreyFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        CellStyle exampleStyle = workbook.createCellStyle();
        exampleStyle.setFont(italicGreyFont);

        Row row = sheet.createRow(rowIndex);
        String[] values = {name, sku, description, unitPrice, costPrice, quantityOnHand, lowStockThreshold};
        for (int i = 0; i < values.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(values[i]);
            cell.setCellStyle(exampleStyle);
        }
    }

    private void addExplanatoryComment(Workbook workbook, Sheet sheet) {
        CreationHelper helper = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_DONT_RESIZE);
        anchor.setCol1(1);
        anchor.setRow1(0);
        anchor.setCol2(5);
        anchor.setRow2(6);

        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(
                "Rows 2-3 are examples of the expected format. Delete them before uploading, "
                        + "or leave them - any row whose sku starts with '" + EXAMPLE_SKU_MARKER_PREFIX
                        + "' is skipped automatically."));
        comment.setAuthor("Stock Bridge");
        sheet.getRow(0).getCell(1).setCellComment(comment);
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
