package com.procurepal_services.stock_bridge_api.imports.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The reader layer, on its own - no Spring, no database. Everything here is a pure function of a
 * byte array, which is exactly why it is worth covering hard: these are the paths a malformed or
 * enormous customer file takes, and they are the ones that are hardest to reproduce afterwards.
 */
class SpreadsheetReaderTest {

    private final SpreadsheetReader reader = new SpreadsheetReader();

    private static final List<String> HEADERS = List.of("name", "sku", "quantity_on_hand", "unit_of_measure");

    // ---------------------------------------------------------------- the two formats agree ----

    /**
     * The property the whole CSV feature rests on: the same content in the two formats produces
     * byte-identical row shapes. If this ever stops holding, a CSV upload starts validating
     * differently from the xlsx upload of the same data - and the difference would show up as
     * mysterious per-customer bugs, not as a failure anyone could locate.
     */
    @Test
    void csvAndXlsxProduceIdenticalRowShapes() {
        byte[] xlsx = xlsx(HEADERS, List.of(
                new Object[] {"Rice 50kg", "RICE-50", 40, "KG"},
                new Object[] {"Garri 25kg", "GARRI-25", 12, "KG"}));
        byte[] csv = csv("""
                name,sku,quantity_on_hand,unit_of_measure
                Rice 50kg,RICE-50,40,KG
                Garri 25kg,GARRI-25,12,KG
                """);

        SheetTable fromXlsx = reader.read(multipart("products.xlsx", xlsx));
        SheetTable fromCsv = reader.read(multipart("products.csv", csv));

        assertThat(fromCsv.columnIndexes()).isEqualTo(fromXlsx.columnIndexes());
        assertThat(fromCsv.rows()).hasSameSizeAs(fromXlsx.rows());
        for (int i = 0; i < fromXlsx.rows().size(); i++) {
            SheetRow xlsxRow = fromXlsx.rows().get(i);
            SheetRow csvRow = fromCsv.rows().get(i);
            assertThat(csvRow.excelRow()).isEqualTo(xlsxRow.excelRow());
            for (String header : HEADERS) {
                assertThat(fromCsv.value(csvRow, header))
                        .as("column %s of row %d", header, xlsxRow.excelRow())
                        .isEqualTo(fromXlsx.value(xlsxRow, header));
            }
        }
    }

    /** RFC 4180's three hard cases, which are the whole reason this is a state machine and not a split(","). */
    @Test
    void csvHandlesQuotedDelimitersEmbeddedNewlinesAndDoubledQuotes() {
        byte[] csv = csv("name,sku,description\n"
                + "\"Rice, parboiled\",RICE-50,\"Line one\nLine two\"\n"
                + "\"The \"\"good\"\" one\",GARRI-25,plain\n");

        SheetTable table = reader.read(multipart("products.csv", csv));

        assertThat(table.rows()).hasSize(2);
        assertThat(table.value(table.rows().get(0), "name")).isEqualTo("Rice, parboiled");
        assertThat(table.value(table.rows().get(0), "description")).isEqualTo("Line one\nLine two");
        assertThat(table.value(table.rows().get(1), "name")).isEqualTo("The \"good\" one");
    }

    /**
     * Excel writes semicolon-separated files in any locale whose decimal separator is a comma, so
     * "export to CSV" from a European-configured ERP is routinely not comma-separated at all.
     */
    @Test
    void csvSniffsASemicolonDelimiter() {
        byte[] csv = csv("name;sku;quantity_on_hand\nRice 50kg;RICE-50;40\n");

        SheetTable table = reader.read(multipart("products.csv", csv));

        assertThat(table.hasColumn("sku")).isTrue();
        assertThat(table.value(table.rows().get(0), "quantity_on_hand")).isEqualTo("40");
    }

    /** A UTF-8 BOM on the first header is the single most common reason a CSV's first column "goes missing". */
    @Test
    void csvStripsAByteOrderMarkFromTheFirstHeader() {
        byte[] csv = csv("﻿name,sku\nRice 50kg,RICE-50\n");

        SheetTable table = reader.read(multipart("products.csv", csv));

        assertThat(table.hasColumn("name")).isTrue();
    }

    // ------------------------------------------------------------------------- xlsx specifics ----

    /**
     * A number in a column this module's own template formats as {@code #,##0} must arrive as
     * {@code 45000}, not as the string {@code "45,000"} - which is what POI's own
     * {@code XSSFSheetXMLHandler} would have produced, and the reason it is not used.
     */
    @Test
    void aFormattedNumberIsReadAsItsRawValueNotItsDisplayText() {
        byte[] file = xlsxWithFormattedNumber("#,##0", 45000);

        SheetTable table = reader.read(multipart("products.xlsx", file));

        assertThat(table.value(table.rows().get(0), "cost_price")).isEqualTo("45000");
    }

    /** Dates are the one exception, and they have to be: a serial number is not something a parser can recognise. */
    @Test
    void aDateFormattedCellIsReadAsAnIsoDate() {
        byte[] file = xlsxWithDate(LocalDate.of(2026, 1, 31));

        SheetTable table = reader.read(multipart("stock-in.xlsx", file));

        assertThat(table.value(table.rows().get(0), "received_date")).isEqualTo("2026-01-31");
    }

    /**
     * The real-world file that motivated the invisible-character rule: a fill handle dragged past
     * the last real row, leaving rows whose every cell holds a zero-width space.
     */
    @Test
    void ghostRowsPaddedWithZeroWidthSpacesAreDropped() {
        byte[] file = xlsx(HEADERS, List.of(
                new Object[] {"Rice 50kg", "RICE-50", 40, "KG"},
                new Object[] {"​", "​", "​", "​"},
                new Object[] {"​", "​", "​", "​"}));

        SheetTable table = reader.read(multipart("products.xlsx", file));

        assertThat(table.rows()).hasSize(1);
        assertThat(table.value(table.rows().get(0), "sku")).isEqualTo("RICE-50");
    }

    /** Column position survives holes - a sheet omits empty cells from the XML entirely. */
    @Test
    void missingCellsDoNotShiftTheColumnsAfterThem() {
        byte[] file = xlsx(HEADERS, List.<Object[]>of(new Object[] {"Rice 50kg", null, null, "KG"}));

        SheetTable table = reader.read(multipart("products.xlsx", file));
        SheetRow row = table.rows().get(0);

        assertThat(table.value(row, "name")).isEqualTo("Rice 50kg");
        assertThat(table.value(row, "sku")).isNull();
        assertThat(table.value(row, "unit_of_measure")).isEqualTo("KG");
    }

    /** Excel row numbers, not list positions - every error message this feature produces quotes one. */
    @Test
    void excelRowNumbersAreOneBasedAndSurviveDroppedRows() {
        byte[] file = xlsx(HEADERS, List.of(
                new Object[] {"Rice 50kg", "RICE-50", 40, "KG"},
                new Object[] {null, null, null, null},
                new Object[] {"Garri 25kg", "GARRI-25", 12, "KG"}));

        SheetTable table = reader.read(multipart("products.xlsx", file));

        assertThat(table.rows()).extracting(SheetRow::excelRow).containsExactly(2, 4);
    }

    // ------------------------------------------------------------------------------- the caps ----

    /**
     * The ordering BULK_IMPORT_DESIGN.md section 11 requires: rejected on the COUNT, before the
     * content is interpreted. The proof is in the message - it names 6,000, the file's true total.
     * A limit enforced during the streaming read could only ever have said 5,001, because that is
     * the row at which it would have stopped.
     */
    @Test
    void anOverCapXlsxIsRejectedOnItsRowCountWithTheRealNumber() {
        byte[] file = xlsxWithRows(6000);

        assertThatThrownBy(() -> reader.read(multipart("products.xlsx", file)))
                .isInstanceOf(SpreadsheetReadException.class)
                .hasMessageContaining("6,000 rows")
                .hasMessageContaining("the limit is 5,000")
                .hasMessageContaining("two files")
                .extracting(e -> ((SpreadsheetReadException) e).getReason())
                .isEqualTo(SpreadsheetReadException.Reason.TOO_MANY_ROWS);
    }

    /** And the count itself is available without reading a single value. */
    @Test
    void rowsCanBeCountedWithoutReadingAnyContent() {
        assertThat(reader.countDataRows(xlsxWithRows(6000), "products.xlsx")).isEqualTo(6000);
        assertThat(reader.countDataRows(xlsx(HEADERS, List.<Object[]>of(new Object[] {"a", "b", 1, "KG"})), "products.xlsx"))
                .isEqualTo(1);
    }

    @Test
    void anOverCapCsvIsRejectedTheSameWay() {
        StringBuilder content = new StringBuilder("name,sku\n");
        for (int i = 0; i < 5200; i++) {
            content.append("Product ").append(i).append(",SKU-").append(i).append('\n');
        }

        assertThatThrownBy(() -> reader.read(multipart("products.csv", csv(content.toString()))))
                .isInstanceOf(SpreadsheetReadException.class)
                .hasMessageContaining("5,200 rows")
                .hasMessageContaining("two files");
    }

    /** A CSV with a quoted newline inside a field must not be counted as two rows. */
    @Test
    void csvRowCountingIsQuoteAware() {
        byte[] csv = csv("name,description\nRice,\"line one\nline two\"\nGarri,plain\n");

        assertThat(reader.countDataRows(csv, "products.csv")).isEqualTo(2);
    }

    @Test
    void anOversizedFileIsRejectedBeforeItsBytesAreEvenRead() {
        byte[] tooBig = new byte[(int) ImportLimits.MAX_FILE_BYTES + 1];

        assertThatThrownBy(() -> reader.read(multipart("products.xlsx", tooBig)))
                .isInstanceOf(SpreadsheetReadException.class)
                .hasMessageContaining("the limit is 10 MB")
                .extracting(e -> ((SpreadsheetReadException) e).getReason())
                .isEqualTo(SpreadsheetReadException.Reason.FILE_TOO_LARGE);
    }

    /**
     * The catch-all message is unchanged, verbatim - existing clients and the pre-existing bulk
     * upload integration test both assert on it.
     */
    @Test
    void garbageIsNotASpreadsheet() {
        assertThatThrownBy(() -> reader.read(multipart("products.xlsx", "this is not a spreadsheet".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(SpreadsheetReadException.class)
                .hasMessage("The uploaded file is not a valid .xlsx file");
    }

    // ------------------------------------------------------------------- header normalization ----

    @Test
    void headersAreFoldedOntoTheirFieldKeys() {
        assertThat(HeaderNames.normalize("Unit Of Measure")).isEqualTo("unit_of_measure");
        assertThat(HeaderNames.normalize("unit-of-measure")).isEqualTo("unit_of_measure");
        assertThat(HeaderNames.normalize("  SKU  ")).isEqualTo("sku");
        assertThat(HeaderNames.normalize("﻿name")).isEqualTo("name");
        assertThat(HeaderNames.normalize("   ")).isNull();
        assertThat(HeaderNames.normalize(null)).isNull();
    }

    @Test
    void aTitleCasedHeaderRowStillMapsToFieldKeys() {
        byte[] csv = csv("Name,SKU,Quantity On Hand\nRice 50kg,RICE-50,40\n");

        SheetTable table = reader.read(multipart("products.csv", csv));

        assertThat(table.hasColumn("quantity_on_hand")).isTrue();
        assertThat(table.value(table.rows().get(0), "quantity_on_hand")).isEqualTo("40");
    }

    // ------------------------------------------------------------------------ number rescuing ----

    @Test
    void theNumbersPeopleActuallyTypeAreUnderstood() {
        assertThat(NumberValues.parseDecimal("45000")).contains(new BigDecimal("45000"));
        assertThat(NumberValues.parseDecimal("45,000")).contains(new BigDecimal("45000"));
        assertThat(NumberValues.parseDecimal("45,000.50")).contains(new BigDecimal("45000.50"));
        assertThat(NumberValues.parseDecimal("N45,000")).contains(new BigDecimal("45000"));
        assertThat(NumberValues.parseDecimal("₦45,000.00")).contains(new BigDecimal("45000.00"));
        assertThat(NumberValues.parseDecimal(" 1 200 ")).contains(new BigDecimal("1200"));
        assertThat(NumberValues.parseDecimal("(500)")).contains(new BigDecimal("-500"));
        assertThat(NumberValues.parseDecimal("-500")).contains(new BigDecimal("-500"));
        // European grouping, from an ERP configured for a European locale.
        assertThat(NumberValues.parseDecimal("1.500,75")).contains(new BigDecimal("1500.75"));
        assertThat(NumberValues.parseDecimal("1.500.000")).contains(new BigDecimal("1500000"));
        // The documented ambiguous call: a lone comma with three digits after it is grouping.
        assertThat(NumberValues.parseDecimal("1,500")).contains(new BigDecimal("1500"));
        // ...and with anything else after it, a decimal point.
        assertThat(NumberValues.parseDecimal("1,5")).contains(new BigDecimal("1.5"));
    }

    @Test
    void thingsThatAreNotNumbersStayNotNumbers() {
        assertThat(NumberValues.parseDecimal("not-a-number")).isEmpty();
        assertThat(NumberValues.parseDecimal("")).isEmpty();
        assertThat(NumberValues.parseDecimal(null)).isEmpty();
        assertThat(NumberValues.parseDecimal("12 bags")).isEmpty();
    }

    // ------------------------------------------------------------------------------ fixtures ----

    private MockMultipartFile multipart(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "application/octet-stream", content);
    }

    private byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(List<String> headers, List<Object[]> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == null) {
                        continue;
                    }
                    if (values[i] instanceof Number number) {
                        row.createCell(i).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(i).setCellValue(values[i].toString());
                    }
                }
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] xlsxWithRows(int dataRows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("sku");
            for (int i = 1; i <= dataRows; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("Product " + i);
                row.createCell(1).setCellValue("SKU-" + i);
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] xlsxWithFormattedNumber(String format, double value) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            sheet.createRow(0).createCell(0).setCellValue("cost_price");
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(format));
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(value);
            cell.setCellStyle(style);
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] xlsxWithDate(LocalDate date) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Stock in");
            sheet.createRow(0).createCell(0).setCellValue("received_date");
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Cell cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(date);
            cell.setCellStyle(style);
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] bytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
