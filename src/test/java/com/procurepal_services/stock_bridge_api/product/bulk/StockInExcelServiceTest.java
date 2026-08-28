package com.procurepal_services.stock_bridge_api.product.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.procurepal_services.stock_bridge_api.imports.io.LookupSheetWriter;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The pre-filled stock-in sheet: that it really is the tenant's catalog rather than a blank form,
 * that its dropdowns are in the written file, and above all that a row the user left alone is
 * skipped in silence.
 */
class StockInExcelServiceTest {

    private final StockInExcelService service = new StockInExcelService(new SpreadsheetReader());

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final List<String> VENDORS = List.of("Dangote Nigeria Plc", "Ade Foods Ltd");

    private static final List<StockInTemplateRow> CATALOG = List.of(
            new StockInTemplateRow(UUID.randomUUID(), "RICE-50", "Rice 50kg", "Dangote Nigeria Plc", "BAG",
                    new BigDecimal("42000"), new BigDecimal("50")),
            new StockInTemplateRow(UUID.randomUUID(), "GARRI-25", "Garri 25kg", "Dangote Nigeria Plc", "BAG",
                    new BigDecimal("18500"), new BigDecimal("25")),
            // A product with no supplier line yet - its unit and pack size still pre-fill, and only
            // the two genuinely unknown columns are left blank.
            new StockInTemplateRow(UUID.randomUUID(), "OIL-5L", "Groundnut Oil 5L", null, "LITER", null, null));

    // ---------------------------------------------------------------------------- the columns ----

    @Test
    void theSheetCarriesTheContractsStockInColumnsInOrder() {
        assertThat(headersOf(service.generateTemplate(CATALOG, VENDORS, TODAY)))
                .containsExactly("sku", "product_name", "vendor_name", "quantity", "unit", "unit_cost",
                        "packaging_size", "received_date", "reference");
    }

    /**
     * Section 5.3's central claim, asserted directly: every column but {@code quantity} arrives
     * filled in. The example row sits above the catalog, so the products start at row index 2.
     */
    @Test
    void theTemplateIsTheTenantsCatalogWithOnlyQuantityLeftBlank() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(file);
            Row rice = sheet.getRow(2);

            assertThat(text(rice, headers.indexOf("sku"))).isEqualTo("RICE-50");
            assertThat(text(rice, headers.indexOf("product_name"))).isEqualTo("Rice 50kg");
            assertThat(text(rice, headers.indexOf("vendor_name"))).isEqualTo("Dangote Nigeria Plc");
            assertThat(text(rice, headers.indexOf("unit"))).isEqualTo("BAG");
            assertThat(rice.getCell(headers.indexOf("unit_cost")).getNumericCellValue()).isEqualTo(42000.0);
            assertThat(rice.getCell(headers.indexOf("packaging_size")).getNumericCellValue()).isEqualTo(50.0);
            assertThat(rice.getCell(headers.indexOf("received_date")).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(TODAY);

            // The one column they fill, and the only one that is empty.
            assertThat(rice.getCell(headers.indexOf("quantity"))).isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A product with no vendor line still gets its unit and date; only what we do not know is blank. */
    @Test
    void aProductWithNoSupplierStillPreFillsEverythingWeDoKnow() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            List<String> headers = headersOf(file);
            Row oil = workbook.getSheetAt(0).getRow(4);

            assertThat(text(oil, headers.indexOf("sku"))).isEqualTo("OIL-5L");
            assertThat(text(oil, headers.indexOf("unit"))).isEqualTo("LITER");
            assertThat(oil.getCell(headers.indexOf("vendor_name"))).isNull();
            assertThat(oil.getCell(headers.indexOf("unit_cost"))).isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -------------------------------------------------------------------------- the dropdowns ----

    /**
     * Read back out of the written file, for the reason {@code ProductExcelServiceTest} states: a
     * validation that failed to attach is invisible to a test that only checks cell values.
     *
     * <p>Flat lists only. A per-row dependent dropdown via {@code INDIRECT} would show up here as
     * one validation region per row, and section 8.3 forbids it because it breaks everywhere except
     * Excel.
     */
    @Test
    void theUnitAndVendorColumnsHaveFlatDropdownsInTheWrittenFile() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(file);
            Map<String, String> rangeByColumn = new LinkedHashMap<>();
            for (XSSFDataValidation validation : sheet.getDataValidations()) {
                assertThat(validation.getValidationConstraint().getFormula1())
                        .as("a formula constraint over a named range, never an INDIRECT")
                        .doesNotContain("INDIRECT");
                for (int i = 0; i < validation.getRegions().countRanges(); i++) {
                    rangeByColumn.put(
                            headers.get(validation.getRegions().getCellRangeAddress(i).getFirstColumn()),
                            validation.getValidationConstraint().getFormula1());
                }
            }

            assertThat(rangeByColumn)
                    .containsEntry("unit", "stock_in_units")
                    .containsEntry("vendor_name", "vendor_names");
            assertThat(rangeByColumn.keySet())
                    .doesNotContain("sku", "product_name", "quantity", "unit_cost", "received_date", "reference");

            assertThat(workbook.getName("stock_in_units")).isNotNull();
            int lookups = workbook.getSheetIndex(LookupSheetWriter.SHEET_NAME);
            assertThat(lookups).isNotNegative();
            assertThat(workbook.isSheetHidden(lookups)).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------------ the silent skip ----

    /**
     * The rule that makes a 400-row pre-filled sheet usable for a 12-row delivery: rows with no
     * quantity are skipped, and nothing is said about them. If this ever became an error, the
     * user's first act on every import would be deleting hundreds of rows by hand.
     */
    @Test
    void rowsWithNoQuantityAreSkippedSilentlyRatherThanErrored() {
        byte[] filled = fillQuantities(service.generateTemplate(CATALOG, VENDORS, TODAY), Map.of("GARRI-25", "12"));

        ParsedStockInSheet sheet = service.parse(multipart("stock-in.xlsx", filled));

        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.sku()).isEqualTo("GARRI-25");
            assertThat(row.quantity()).isEqualTo(12);
            assertThat(row.vendorName()).isEqualTo("Dangote Nigeria Plc");
            assertThat(row.unit()).isEqualTo("BAG");
            assertThat(row.unitCost()).isEqualByComparingTo("18500");
            assertThat(row.packagingSize()).isEqualByComparingTo("25");
            assertThat(row.receivedDate()).isEqualTo(TODAY);
        });
        // The other two products are accounted for, not lost - the confirm screen and the report
        // both need to be able to say "388 rows left blank".
        assertThat(sheet.skippedExcelRows()).hasSize(2);
    }

    /** A typed zero says the same thing as a blank cell, and there is no such thing as a delivery of nothing. */
    @Test
    void anExplicitZeroQuantityIsAlsoASilentSkip() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,quantity\nRICE-50,0\nGARRI-25,12\n")));

        assertThat(sheet.rows()).extracting(ParsedStockInRow::sku).containsExactly("GARRI-25");
        assertThat(sheet.skippedExcelRows()).containsExactly(2);
    }

    /** A negative delivery is not a delivery - it is a stock adjustment, which is a different screen. */
    @Test
    void aNegativeQuantityIsAnError() {
        BulkUploadValidationException thrown = catchThrowableOfType(
                BulkUploadValidationException.class,
                () -> service.parse(multipart("stock-in.csv", csv("sku,quantity\nRICE-50,-5\n"))));

        assertThat(thrown.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.column()).isEqualTo("quantity");
            assertThat(error.message()).contains("cannot be negative");
        });
    }

    /**
     * A row the user never touched must not be able to produce an error from any other column. The
     * pre-filled unit here is nonsense, and it still has to be silent, because the user left the row
     * alone and nothing about it is their problem.
     */
    @Test
    void aSkippedRowCannotProduceErrorsFromItsOtherColumns() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,quantity,unit,received_date\nRICE-50,,NOT-A-UNIT,not-a-date\nGARRI-25,3,BAG,2026-01-31\n")));

        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.skippedExcelRows()).containsExactly(2);
    }

    // --------------------------------------------------------------------------- other columns ----

    @Test
    void productNameIsIgnoredOnTheWayBackIn() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,product_name,quantity\nRICE-50,Something Else Entirely,3\n")));

        // No error, no rename, no trace of it - the column exists only so the row is readable.
        assertThat(sheet.rows()).singleElement().satisfies(row -> assertThat(row.sku()).isEqualTo("RICE-50"));
    }

    @Test
    void theDatesPeopleActuallyTypeAreUnderstood() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,quantity,received_date\n"
                        + "A,1,2026-01-31\n"
                        + "B,2,31/01/2026\n"
                        + "C,3,31-01-2026\n"
                        + "D,4,31 Jan 2026\n"
                        + "E,5,\n")));

        assertThat(sheet.rows()).extracting(ParsedStockInRow::receivedDate)
                .containsExactly(
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 1, 31),
                        LocalDate.of(2026, 1, 31),
                        null);
    }

    @Test
    void anUnreadableDateSaysWhatAReadableOneLooksLike() {
        BulkUploadValidationException thrown = catchThrowableOfType(
                BulkUploadValidationException.class,
                () -> service.parse(multipart("stock-in.csv", csv("sku,quantity,received_date\nA,1,last tuesday\n"))));

        assertThat(thrown.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.column()).isEqualTo("received_date");
            assertThat(error.message()).contains("2026-01-31").contains("31/01/2026");
        });
    }

    /** Both roles are valid in this column - a delivery is counted in bags or in kilos, and both are true. */
    @Test
    void theUnitColumnAcceptsBothBaseAndPackagingUnitsAndNormalizesThem() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,quantity,unit\nA,1,bags\nB,2,kilos\nC,3,\n")));

        assertThat(sheet.rows()).extracting(ParsedStockInRow::unit).containsExactly("BAG", "KG", null);
    }

    /** The example row ships with a filled quantity, and must still never become a delivery. */
    @Test
    void theExampleRowIsSkippedEvenThoughItHasAQuantity() {
        ParsedStockInSheet sheet = service.parse(multipart(
                "stock-in.xlsx", fillQuantities(service.generateTemplate(CATALOG, VENDORS, TODAY), Map.of())));

        assertThat(sheet.rows()).isEmpty();
        assertThat(sheet.skippedExcelRows()).hasSize(3);
    }

    @Test
    void aMissingSkuColumnIsAHeaderError() {
        BulkUploadValidationException thrown = catchThrowableOfType(
                BulkUploadValidationException.class,
                () -> service.parse(multipart("stock-in.csv", csv("product_name,quantity\nRice,3\n"))));

        assertThat(thrown.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.column()).isEqualTo("sku");
            assertThat(error.message()).contains("missing");
        });
    }

    /** The copy section 8.3 asks for, owned here next to the column it is about. */
    @Test
    void theWrongUnitForAProductIsExplainedByNamingTheOnesThatAreRight() {
        assertThat(StockInExcelService.unitNotStockedMessage("Rice 50kg", "KG", "BAG"))
                .isEqualTo("Rice 50kg is stocked in KG or BAG.");
        assertThat(StockInExcelService.unitNotStockedMessage("Groundnut Oil 5L", "LITER", null))
                .isEqualTo("Groundnut Oil 5L is stocked in LITER.");
    }

    /** A tenant with no products yet gets a usable blank sheet rather than a failure. */
    @Test
    void anEmptyCatalogStillProducesAUsableSheet() {
        byte[] file = service.generateTemplate(List.of(), List.of(), TODAY);

        assertThat(headersOf(file)).hasSize(9);
        assertThat(service.parse(multipart("stock-in.xlsx", file)).rows()).isEmpty();
    }

    // ------------------------------------------------------------------------------ fixtures ----

    private MockMultipartFile multipart(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content);
    }

    private byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private String text(Row row, int columnIndex) {
        Cell cell = row.getCell(columnIndex);
        return cell == null || cell.getCellType() != CellType.STRING ? null : cell.getStringCellValue();
    }

    /** Types a quantity into the rows the user cared about, exactly as they would in Excel. */
    private byte[] fillQuantities(byte[] template, Map<String, String> quantitiesBySku) {
        try (XSSFWorkbook workbook = open(template)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(template);
            int skuColumn = headers.indexOf("sku");
            int quantityColumn = headers.indexOf("quantity");
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Cell skuCell = row == null ? null : row.getCell(skuColumn);
                if (skuCell == null) {
                    continue;
                }
                String quantity = quantitiesBySku.get(skuCell.getStringCellValue());
                if (quantity != null) {
                    row.createCell(quantityColumn).setCellValue(Double.parseDouble(quantity));
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> headersOf(byte[] file) {
        try (XSSFWorkbook workbook = open(file)) {
            List<String> headers = new ArrayList<>();
            for (Cell cell : workbook.getSheetAt(0).getRow(0)) {
                headers.add(cell.getStringCellValue());
            }
            return headers;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private XSSFWorkbook open(byte[] file) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
