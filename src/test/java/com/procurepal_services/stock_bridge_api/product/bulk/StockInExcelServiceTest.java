package com.procurepal_services.stock_bridge_api.product.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.imports.io.TemplateConventions;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The stock sheet (BULK_IMPORT_CX_PLAN.md task 1.4): the tenant's own products, one row per way
 * each is bought, a key nobody has to read, a price that starts blank beside the last one paid,
 * and instructions a person can actually see - all of which the reader then steps around.
 */
class StockInExcelServiceTest {

    private final StockInExcelService service = new StockInExcelService();

    private static final UUID RICE = UUID.randomUUID();
    private static final UUID OIL = UUID.randomUUID();
    private static final UUID BIRO = UUID.randomUUID();

    /**
     * Rice bought in 50 kg bags or loose; oil bought loose only; biros bought by the piece or in
     * packs of ten. Prices are per stock unit, as stored - ₦900 a kg is ₦45,000 a bag.
     */
    private static List<StockInTemplateRow> catalog() {
        List<StockInTemplateRow> rows = new ArrayList<>();
        addRows(rows, RICE, "RICE-50", "Rice (Mama Gold)", "Tony Stores",
                SheetUnitOptions.forRow("KG", "BAG", new BigDecimal("50"), List.of()), new BigDecimal("900"));
        addRows(rows, OIL, "OIL-5L", "Groundnut oil", null,
                SheetUnitOptions.forRow("LITER", null, null, List.of()), null);
        addRows(rows, BIRO, "28.0", "Biro (blue)", "Tony Stores",
                SheetUnitOptions.forRow("PIECE", "PACK", new BigDecimal("10"), List.of()), new BigDecimal("15"));
        return rows;
    }

    private static void addRows(
            List<StockInTemplateRow> rows, UUID id, String sku, String name, String vendor,
            List<UnitOption> options, BigDecimal costPerStockUnit) {
        for (UnitOption option : SheetUnitOptions.rowOptions(options)) {
            rows.add(new StockInTemplateRow(id, sku, name, vendor, option,
                    costPerStockUnit == null ? null : costPerStockUnit.multiply(option.factorToStockUnit())));
        }
    }

    @Test
    void theHelpTabOpensFirstAndTheReaderSkipsIt() {
        byte[] file = service.generateTemplate(catalog());
        try (XSSFWorkbook workbook = open(file)) {
            assertThat(workbook.getSheetName(0)).isEqualTo(TemplateConventions.HELP_SHEET_NAME);
            assertThat(workbook.getActiveSheetIndex()).isZero();
            assertThat(workbook.getSheetName(1)).isEqualTo("Delivery");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(read(file).headers()).first().isEqualTo("Product");
    }

    @Test
    void theColumnsAreNamedForPeople() {
        assertThat(read(service.generateTemplate(catalog())).headers()).containsExactly(
                "Product", "Comes in", "How many arrived", "Price paid for one (₦)", "Last price paid (₦)",
                "Supplier", "Your code", "Date (if different)", "Ref");
    }

    @Test
    void theGuidanceRowIsVisibleOnTheSheetAndDroppedOnRead() {
        byte[] file = service.generateTemplate(catalog());
        try (XSSFWorkbook workbook = open(file)) {
            Row guidance = workbook.getSheet("Delivery").getRow(1);
            assertThat(guidance.getCell(0).getStringCellValue()).startsWith(TemplateConventions.GUIDANCE_MARKER);
            assertThat(guidance.getCell(2).getStringCellValue()).contains("ONLY COLUMN YOU NEED");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SheetTable table = read(file);
        assertThat(table.rows()).hasSize(catalog().size());
        assertThat(table.rows().get(0).excelRow()).isEqualTo(3);
    }

    @Test
    void eachWayOfBuyingHasItsOwnRow() {
        SheetTable table = read(service.generateTemplate(catalog()));
        List<String> comesIn = table.rows().stream().map(row -> table.value(row, "comes_in")).toList();
        assertThat(comesIn).containsExactly(
                "Bag · 50 kg", "Loose · kg",
                "Loose · L",
                "Pack · 10 pieces", "Piece");
    }

    @Test
    void thePriceStartsBlankBesideTheLastPricePaidForThatWayOfBuying() {
        SheetTable table = read(service.generateTemplate(catalog()));
        assertThat(table.rows()).allSatisfy(row -> assertThat(table.value(row, "price_paid_for_one")).isNull());
        List<String> last = table.rows().stream().map(row -> table.value(row, "last_price_paid")).toList();
        assertThat(last).containsExactly("45000", "900", null, "150", "15");
    }

    @Test
    void theRefIsHiddenAndLeadsBackToTheProductWhateverTheCodeSays() {
        byte[] file = service.generateTemplate(catalog());
        try (XSSFWorkbook workbook = open(file)) {
            Sheet sheet = workbook.getSheet("Delivery");
            assertThat(sheet.isColumnHidden(StockInExcelService.HEADERS.indexOf("Ref"))).isTrue();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SheetTable table = read(file);
        List<UUID> ids = table.rows().stream()
                .map(row -> ProductRefs.decode(table.value(row, "ref")).orElseThrow())
                .toList();
        assertThat(ids).containsExactly(RICE, RICE, OIL, BIRO, BIRO);
        // A damaged code is shown as it is; it is reference only now.
        assertThat(table.value(table.rows().get(3), "your_code")).isEqualTo("28.0");
    }

    /** A whole-number format on the quantity column would show 2.5 bags as 3. */
    @Test
    void theQuantityColumnShowsAFractionAsTyped() {
        try (XSSFWorkbook workbook = open(service.generateTemplate(catalog()))) {
            Sheet sheet = workbook.getSheet("Delivery");
            int quantity = StockInExcelService.HEADERS.indexOf("How many arrived");
            assertThat(sheet.getColumnStyle(quantity).getDataFormatString()).isEqualTo("General");
            Cell unfilled = sheet.getRow(2).getCell(quantity);
            assertThat(unfilled).isNull();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SheetTable read(byte[] file) {
        return new SpreadsheetReader().read(file, "stock-sheet.xlsx");
    }

    private static XSSFWorkbook open(byte[] file) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
