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
 * that it states every valid answer on the row that asks the question (UNIT_UX_CONTRACT.md section
 * 5.2), that a template saved before that change still parses, and above all that a row the user
 * left alone is skipped in silence.
 */
class StockInExcelServiceTest {

    private final StockInExcelService service = new StockInExcelService(new SpreadsheetReader());

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);

    private static final List<String> VENDORS = List.of("Dangote Nigeria Plc", "Ade Foods Ltd");

    /**
     * Three shapes that between them cover the whole column set: a product with a pack, a second
     * one whose pack is a different size (so the dropdown has to carry both labels), and one sold
     * loose with no supplier line yet.
     *
     * <p>The costs are per STOCK UNIT, because that is what {@code ProductVendor.lastCostPrice} is
     * (contract section 3.2) - ₦900 per kg for rice, which the sheet has to state as ₦45,000 per
     * 50 kg bag.
     */
    private static final List<StockInTemplateRow> CATALOG = List.of(
            new StockInTemplateRow(UUID.randomUUID(), "RICE-50", "Rice 50kg", "Dangote Nigeria Plc",
                    SheetUnitOptions.forRow("KG", "BAG", new BigDecimal("50"), null, null),
                    new BigDecimal("900")),
            new StockInTemplateRow(UUID.randomUUID(), "GARRI-25", "Garri 25kg", "Dangote Nigeria Plc",
                    SheetUnitOptions.forRow("KG", "BAG", new BigDecimal("25"), null, null),
                    new BigDecimal("740")),
            // A product with no supplier line yet - its unit set still pre-fills, and only the two
            // genuinely unknown columns are left blank.
            new StockInTemplateRow(UUID.randomUUID(), "OIL-5L", "Groundnut Oil 5L", null,
                    SheetUnitOptions.forRow("LITER", null, null, null, null), null));

    // ---------------------------------------------------------------------------- the columns ----

    @Test
    void theSheetCarriesTheContractsStockInColumnsInOrder() {
        assertThat(headersOf(service.generateTemplate(CATALOG, VENDORS, TODAY)))
                .containsExactly("sku", "product_name", "how_you_count_it", "vendor_name", "quantity",
                        "counted_in", "cost_per_unit", "received_date", "reference");
    }

    /** The removed column is gone from the sheet - contract section 5.2, plan P3-3. */
    @Test
    void packagingSizeIsNoLongerAColumnOnTheSheet() {
        assertThat(headersOf(service.generateTemplate(CATALOG, VENDORS, TODAY))).doesNotContain("packaging_size");
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
            assertThat(text(rice, headers.indexOf("counted_in"))).isEqualTo("Bag of 50 kg");
            assertThat(rice.getCell(headers.indexOf("received_date")).getLocalDateTimeCellValue().toLocalDate())
                    .isEqualTo(TODAY);

            // The one column they fill, and the only one that is empty.
            assertThat(rice.getCell(headers.indexOf("quantity"))).isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The column the whole change exists for - contract section 5.2. The sheet now states every way
     * this product can be counted, on the row, immediately beside the cell that asks for one. A
     * product with a pack shows both answers; a product sold loose shows the one true answer rather
     * than an empty cell that reads as "we forgot".
     */
    @Test
    void howYouCountItStatesTheValidAnswersOnTheRowThatAsksTheQuestion() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(file);
            int column = headers.indexOf("how_you_count_it");

            assertThat(text(sheet.getRow(2), column))
                    .as("a product with a pack: its stock unit and its pack, both spelled the way a person says them")
                    .isEqualTo("kg · or Bag of 50 kg");
            assertThat(text(sheet.getRow(3), column)).isEqualTo("kg · or Bag of 25 kg");
            assertThat(text(sheet.getRow(4), column))
                    .as("a product sold loose has exactly one way to count it, and the cell says so")
                    .isEqualTo("L");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The mirror of the price bug (UNIT_UX_REMEDIATION_PLAN.md P0-1), and the reason it used to
     * round-trip: {@code lastCostPrice} is ₦900 per kg, the row is pre-filled "Bag of 50 kg", so
     * the cell must say ₦45,000. Leaving it at 900 would state that a 50 kg bag of rice costs ₦900
     * and let a user who accepted the pre-fill import a fiftieth of what they paid.
     */
    @Test
    void costPerUnitIsPreFilledInTheTermsOfTheRowsCountedIn() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headersOf(file);
            int column = headers.indexOf("cost_per_unit");

            assertThat(sheet.getRow(2).getCell(column).getNumericCellValue())
                    .as("₦900 per kg, stated per the 50 kg bag the row is counted in")
                    .isEqualTo(45000.0);
            assertThat(sheet.getRow(3).getCell(column).getNumericCellValue())
                    .as("₦740 per kg in a 25 kg bag")
                    .isEqualTo(18500.0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A product with no vendor line still gets its unit set and its date; only what we do not know is blank. */
    @Test
    void aProductWithNoSupplierStillPreFillsEverythingWeDoKnow() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            List<String> headers = headersOf(file);
            Row oil = workbook.getSheetAt(0).getRow(4);

            assertThat(text(oil, headers.indexOf("sku"))).isEqualTo("OIL-5L");
            assertThat(text(oil, headers.indexOf("counted_in"))).isEqualTo("L");
            assertThat(oil.getCell(headers.indexOf("vendor_name"))).isNull();
            assertThat(oil.getCell(headers.indexOf("cost_per_unit"))).isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Contract section 7, non-negotiable 4: no spreadsheet cell contains an internal code. Asserted
     * over every written cell rather than the two we happen to remember, because the whole point is
     * that a code must not be able to leak back in through a column somebody adds later.
     */
    @Test
    void noCellOnTheSheetContainsAnInternalUnitCode() {
        byte[] file = service.generateTemplate(CATALOG, VENDORS, TODAY);

        try (XSSFWorkbook workbook = open(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                for (Cell cell : sheet.getRow(i)) {
                    if (cell.getCellType() == CellType.STRING) {
                        assertThat(cell.getStringCellValue()).isNotIn("KG", "BAG", "LITER", "PIECE", "CARTON");
                    }
                }
            }
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
     * one validation region per row, and BULK_IMPORT_DESIGN.md section 8.3 forbids it because it
     * breaks everywhere except Excel.
     */
    @Test
    void theCountedInAndVendorColumnsHaveFlatDropdownsInTheWrittenFile() {
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
                    .containsEntry("counted_in", "stock_in_units")
                    .containsEntry("vendor_name", "vendor_names");
            assertThat(rangeByColumn.keySet())
                    .doesNotContain("sku", "product_name", "how_you_count_it", "quantity", "cost_per_unit",
                            "received_date", "reference");

            assertThat(workbook.getName("stock_in_units")).isNotNull();
            int lookups = workbook.getSheetIndex(LookupSheetWriter.SHEET_NAME);
            assertThat(lookups).isNotNegative();
            assertThat(workbook.isSheetHidden(lookups)).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The dropdown carries the same strings the cells do - the packs actually in use on this sheet,
     * then every base unit's symbol. A user who opens it sees "Bag of 50 kg", not "BAG".
     */
    @Test
    void theCountedInDropdownOffersLabelsNotCodes() {
        assertThat(lookupList(service.generateTemplate(CATALOG, VENDORS, TODAY), "stock_in_units"))
                .startsWith("Bag of 50 kg", "Bag of 25 kg")
                .contains("kg", "L", "Piece")
                .doesNotContain("KG", "BAG", "LITER");
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
            assertThat(row.countedIn())
                    .as("the composed pack label the sheet wrote resolves back to the packaging unit it names")
                    .isEqualTo("BAG");
            assertThat(row.costPerUnit()).isEqualByComparingTo("18500");
            assertThat(row.receivedDate()).isEqualTo(TODAY);
        });
        // The other two products are accounted for, not lost - the confirm screen and the report
        // both need to be able to say "388 rows left blank".
        assertThat(sheet.skippedExcelRows()).hasSize(2);
        assertThat(sheet.warnings()).isEmpty();
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
                "sku,quantity,counted_in,received_date\n"
                        + "RICE-50,,NOT-A-UNIT,not-a-date\n"
                        + "GARRI-25,3,Bag of 25 kg,2026-01-31\n")));

        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.skippedExcelRows()).containsExactly(2);
    }

    // ------------------------------------------------- old saved templates, contract 7.8 ----

    /**
     * Contract section 7, non-negotiable 8. A tenant fills in the sheet they downloaded last year
     * and uploads it: {@code unit} and {@code unit_cost} still mean what they always meant, and the
     * only thing they hear about it is the one sentence explaining the column we no longer use.
     */
    @Test
    void aTemplateSavedBeforeTheRenameStillParsesThroughTheAliases() {
        ParsedStockInSheet sheet = service.parse(multipart("old-stock-in.csv", csv(
                "sku,product_name,vendor_name,quantity,unit,unit_cost,packaging_size,received_date,reference\n"
                        + "RICE-50,Rice 50kg,Dangote Nigeria Plc,20,BAG,45000,50,2026-01-31,WB-1\n")));

        assertThat(sheet.rows()).singleElement().satisfies(row -> {
            assertThat(row.countedIn()).isEqualTo("BAG");
            assertThat(row.costPerUnit()).isEqualByComparingTo("45000");
            assertThat(row.vendorName()).isEqualTo("Dangote Nigeria Plc");
            assertThat(row.receivedDate()).isEqualTo(LocalDate.of(2026, 1, 31));
            assertThat(row.reference()).isEqualTo("WB-1");
        });
    }

    /**
     * The removed column: accepted, ignored, and warned about - never an error, and never silence.
     * Contract section 5.2 words this sentence, and it says three things in two clauses: where the
     * pack comes from now, that the number was not used, and what to do if the delivery genuinely
     * did arrive in a different pack.
     */
    @Test
    void packagingSizeOnAnOldSheetIsIgnoredWithAWarningRatherThanAnError() {
        ParsedStockInSheet sheet = service.parse(multipart("old-stock-in.csv", csv(
                "sku,quantity,unit,packaging_size\nRICE-50,20,BAG,50\nGARRI-25,4,BAG,25\n")));

        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.warnings())
                .extracting(ProductRowError::row, ProductRowError::column, ProductRowError::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                2, "packaging_size", StockInExcelService.PACKAGING_SIZE_IGNORED_WARNING),
                        org.assertj.core.groups.Tuple.tuple(
                                3, "packaging_size", StockInExcelService.PACKAGING_SIZE_IGNORED_WARNING));
        assertThat(StockInExcelService.PACKAGING_SIZE_IGNORED_WARNING)
                .contains("your product setup")
                .contains("review screen");
    }

    /**
     * The column being present but empty is a saved template nobody filled in. Telling somebody we
     * ignored a blank cell is noise, and noise is how a warning list stops being read.
     */
    @Test
    void anEmptyPackagingSizeColumnSaysNothingAtAll() {
        ParsedStockInSheet sheet = service.parse(multipart("old-stock-in.csv", csv(
                "sku,quantity,unit,packaging_size\nRICE-50,20,BAG,\n")));

        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.warnings()).isEmpty();
    }

    /** A row the user left blank must not warn either - four hundred untouched rows, four hundred notes. */
    @Test
    void aSkippedRowDoesNotWarnAboutTheIgnoredColumn() {
        ParsedStockInSheet sheet = service.parse(multipart("old-stock-in.csv", csv(
                "sku,quantity,unit,packaging_size\nRICE-50,,BAG,50\nGARRI-25,4,BAG,25\n")));

        assertThat(sheet.warnings()).extracting(ProductRowError::row).containsExactly(3);
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

    /**
     * Every spelling this column has ever had to take: the composed pack label the sheet writes, a
     * bare symbol, a trade alias, a raw code, and blank meaning "the product's own stock unit".
     * Both roles are valid here - a delivery is counted in bags or in kilos, and both are true.
     */
    @Test
    void theCountedInColumnAcceptsLabelsCodesAndAliasesAlike() {
        ParsedStockInSheet sheet = service.parse(multipart("stock-in.csv", csv(
                "sku,quantity,counted_in\n"
                        + "A,1,Bag of 50 kg\n"
                        + "B,2,kg\n"
                        + "C,3,bags\n"
                        + "D,4,KG\n"
                        + "E,5,Kilogram (kg)\n"
                        + "F,6,\n")));

        assertThat(sheet.rows()).extracting(ParsedStockInRow::countedIn)
                .containsExactly("BAG", "KG", "BAG", "KG", "KG", null);
    }

    /** A pack label whose container is not a unit we know is still an error, not a silent guess. */
    @Test
    void aCountedInWeCannotResolveIsStillAnError() {
        BulkUploadValidationException thrown = catchThrowableOfType(
                BulkUploadValidationException.class,
                () -> service.parse(multipart("stock-in.csv", csv("sku,quantity,counted_in\nA,1,Wheelbarrow of 3 kg\n"))));

        assertThat(thrown.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.column()).isEqualTo("counted_in");
            assertThat(error.message()).contains("Wheelbarrow of 3 kg");
        });
    }

    /** An error on an old sheet names the column the user can actually see in their own file. */
    @Test
    void anErrorOnAnOldSheetNamesTheHeaderThatFileUses() {
        BulkUploadValidationException thrown = catchThrowableOfType(
                BulkUploadValidationException.class,
                () -> service.parse(multipart("old-stock-in.csv", csv("sku,quantity,unit\nA,1,NOT-A-UNIT\n"))));

        assertThat(thrown.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.column()).isEqualTo("unit"));
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

    /**
     * Contract section 3.1's sentence, owned here next to the column it is about: it names the
     * product, every valid answer, and what the user actually typed. The pre-contract two-unit form
     * is kept alongside it so callers written against it keep saying the same thing.
     */
    @Test
    void theWrongUnitForAProductIsExplainedByNamingTheOnesThatAreRight() {
        assertThat(StockInExcelService.unitNotStockedMessage(
                        "Rice 50kg", List.of("kg", "Bag of 50 kg"), "cartons"))
                .isEqualTo("Rice 50kg is counted in kg or Bag of 50 kg - we don't know how to count it in cartons.");
        assertThat(StockInExcelService.unitNotStockedMessage("Groundnut Oil 5L", List.of("L"), null))
                .isEqualTo("Groundnut Oil 5L is counted in L.");
        assertThat(StockInExcelService.unitNotStockedMessage("Rice 50kg", "KG", "BAG"))
                .isEqualTo("Rice 50kg is stocked in KG or BAG.");
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

    /** The values behind one defined name on the hidden lookup sheet, i.e. what a dropdown offers. */
    private List<String> lookupList(byte[] file, String definedName) {
        try (XSSFWorkbook workbook = open(file)) {
            String formula = workbook.getName(definedName).getRefersToFormula();
            org.apache.poi.ss.util.AreaReference area =
                    new org.apache.poi.ss.util.AreaReference(formula, workbook.getSpreadsheetVersion());
            Sheet lookups = workbook.getSheet(LookupSheetWriter.SHEET_NAME);
            List<String> values = new ArrayList<>();
            for (int r = area.getFirstCell().getRow(); r <= area.getLastCell().getRow(); r++) {
                Cell cell = lookups.getRow(r).getCell(area.getFirstCell().getCol());
                values.add(cell.getStringCellValue());
            }
            return values;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
