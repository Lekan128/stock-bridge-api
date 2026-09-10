package com.procurepal_services.stock_bridge_api.imports.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * The downloadable record of what an import did - the file an accountant asks for three weeks
 * later. Its whole value is completeness, so that is what is asserted: every row of the original
 * file appears, including the skipped ones, each with its outcome and the values it carried.
 */
class ImportResultReportWriterTest {

    private final ImportResultReportWriter writer = new ImportResultReportWriter();

    private static final ImportResultReport.Sheet SHEET = new ImportResultReport.Sheet(
            "Imported 3 rows from products-jan.xlsx",
            List.of(
                    new ImportResultReport.Column("sku", "SKU"),
                    new ImportResultReport.Column("name", "Product name"),
                    new ImportResultReport.Column("quantity_on_hand", "Quantity")));

    private static final List<ImportResultReport.Row> ROWS = List.of(
            new ImportResultReport.Row(2, "CREATED", "", Map.of("sku", "RICE-50", "name", "Rice 50kg", "quantity_on_hand", "40")),
            new ImportResultReport.Row(3, "SKIPPED", "No quantity entered", Map.of("sku", "GARRI-25", "name", "Garri 25kg")),
            new ImportResultReport.Row(4, "FAILED", "We don't recognise \"KGS\" as a unit", Map.of("sku", "OIL-5L", "name", "Groundnut Oil 5L")));

    @Test
    void everyRowOfTheOriginalFileAppearsWithItsOutcomeAndItsValues() {
        byte[] file = writer.write(SHEET, ROWS);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);

            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Imported 3 rows from products-jan.xlsx");
            // row | outcome | message, then the file's own columns under their human labels.
            Row header = sheet.getRow(2);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("row");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("outcome");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("message");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("SKU");
            assertThat(header.getCell(5).getStringCellValue()).isEqualTo("Quantity");

            // The row number is the one in the file on the user's desk - the single most useful
            // column here, and the reason it comes first.
            assertThat(sheet.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("CREATED");
            assertThat(sheet.getRow(3).getCell(3).getStringCellValue()).isEqualTo("RICE-50");
            assertThat(sheet.getRow(3).getCell(5).getStringCellValue()).isEqualTo("40");

            // Skipped rows are present, not dropped: "why did only 12 of my 400 products get
            // stock" is the question this report exists to answer.
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("SKIPPED");
            assertThat(sheet.getRow(4).getCell(2).getStringCellValue()).isEqualTo("No quantity entered");
            // A column with no value for this row is blank, never the word "null".
            assertThat(sheet.getRow(4).getCell(5).getStringCellValue()).isEmpty();

            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("FAILED");
            assertThat(sheet.getRow(5).getCell(2).getStringCellValue()).contains("KGS");

            assertThat(sheet.getLastRowNum()).isEqualTo(5);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Colour-coded so a reader can find the failures by scanning rather than by reading. */
    @Test
    void failedAndSkippedOutcomesAreVisuallyDistinct() {
        byte[] file = writer.write(SHEET, ROWS);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            short created = sheet.getRow(3).getCell(1).getCellStyle().getFillForegroundColor();
            short skipped = sheet.getRow(4).getCell(1).getCellStyle().getFillForegroundColor();
            short failed = sheet.getRow(5).getCell(1).getCellStyle().getFillForegroundColor();

            assertThat(failed).isNotEqualTo(created);
            assertThat(skipped).isNotEqualTo(created);
            assertThat(failed).isNotEqualTo(skipped);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aReportWithNoRowsIsStillAValidFileWithItsHeadline() {
        byte[] file = writer.write(new ImportResultReport.Sheet("Nothing was imported", List.of()), List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("Nothing was imported");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
