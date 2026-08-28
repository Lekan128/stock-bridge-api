package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Component;

/**
 * Writes the .xlsx of every row and what happened to it - the file behind "Download report" on the
 * result screen (BULK_IMPORT_DESIGN.md section 9.5).
 *
 * <h2>Who this is for</h2>
 * Not the person who just ran the import; they are looking at the result screen, which already
 * tells them what happened. This is for the same person three weeks later, or their accountant,
 * asking "what exactly did that import do" - which is why it is a spreadsheet of every row rather
 * than a summary, why it leads with the row number from the file they uploaded, and why failures
 * carry their reason inline instead of being counted somewhere. It is the NetSuite/Shopify
 * convention, and it exists because a bulk write with no artefact is a bulk write nobody can audit.
 *
 * <h2>Shape</h2>
 * A headline row, a blank row, then {@code row | outcome | message} followed by the file's own
 * columns. Outcome first because it is what the reader is scanning for, and colour-coded because
 * scanning 400 rows for the word FAILED is exactly the job a fill colour does better than a human.
 * The original values come last and unmodified, so the report doubles as a record of what the file
 * said - which is the other question asked three weeks later, usually as "are you sure that is what
 * I sent you".
 */
@Component
public class ImportResultReportWriter {

    private static final String OUTCOME_FAILED = "FAILED";

    private static final String OUTCOME_SKIPPED = "SKIPPED";

    /**
     * Builds the report.
     *
     * @param sheet the headline and the column list.
     * @param rows every row of the original file, in file order, including the ones that were
     *     skipped. Completeness is the point: a report missing the skipped rows cannot answer "why
     *     did only 12 of my 400 products get stock", which is the most likely question a stock-in
     *     report will be asked.
     */
    public byte[] write(ImportResultReport.Sheet sheet, List<ImportResultReport.Row> rows) {
        try (WorkbookBuilder builder = new WorkbookBuilder("Import report")) {
            org.apache.poi.ss.usermodel.Sheet target = builder.sheet();

            Row headline = target.createRow(0);
            Cell headlineCell = headline.createCell(0);
            headlineCell.setCellValue(sheet.headline());
            headlineCell.setCellStyle(headlineStyle(builder));

            List<String> headers = new java.util.ArrayList<>(List.of("row", "outcome", "message"));
            sheet.columns().forEach(column -> headers.add(column.label()));

            Row header = target.createRow(2);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(builder.headerStyle());
                target.setColumnWidth(i, widthFor(i, headers.get(i)) * 256);
            }
            // Frozen below the header so the columns stay visible while scrolling a 5,000-row
            // report - the same reason the templates freeze theirs.
            target.createFreezePane(0, 3);

            Map<String, CellStyle> outcomeStyles = outcomeStyles(builder);
            int rowIndex = 3;
            for (ImportResultReport.Row reportRow : rows) {
                Row row = target.createRow(rowIndex++);
                row.createCell(0).setCellValue(reportRow.excelRow());

                Cell outcomeCell = row.createCell(1);
                String outcome = reportRow.outcome() == null ? "" : reportRow.outcome();
                outcomeCell.setCellValue(outcome);
                CellStyle style = outcomeStyles.get(outcome.toUpperCase(Locale.ROOT));
                if (style != null) {
                    outcomeCell.setCellStyle(style);
                }

                row.createCell(2).setCellValue(reportRow.message() == null ? "" : reportRow.message());

                for (int i = 0; i < sheet.columns().size(); i++) {
                    String value = reportRow.values().get(sheet.columns().get(i).key());
                    row.createCell(3 + i).setCellValue(value == null ? "" : value);
                }
            }
            return builder.toBytes();
        }
    }

    private CellStyle headlineStyle(WorkbookBuilder builder) {
        Font bold = builder.workbook().createFont();
        bold.setBold(true);
        bold.setFontHeightInPoints((short) 13);
        CellStyle style = builder.workbook().createCellStyle();
        style.setFont(bold);
        return style;
    }

    /**
     * Colour by outcome, and only for the two that matter. CREATED and UPDATED are left unstyled on
     * purpose: they are the expected result, and colouring every row would leave nothing standing
     * out. Red for FAILED, grey for SKIPPED - the two a reader is looking for.
     */
    private Map<String, CellStyle> outcomeStyles(WorkbookBuilder builder) {
        Map<String, CellStyle> styles = new LinkedHashMap<>();
        styles.put(OUTCOME_FAILED, filled(builder, IndexedColors.ROSE));
        styles.put(OUTCOME_SKIPPED, filled(builder, IndexedColors.GREY_25_PERCENT));
        return styles;
    }

    private CellStyle filled(WorkbookBuilder builder, IndexedColors colour) {
        CellStyle style = builder.workbook().createCellStyle();
        style.setFillForegroundColor(colour.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private int widthFor(int columnIndex, String header) {
        return switch (columnIndex) {
            case 0 -> 8;
            case 1 -> 12;
            case 2 -> 52;
            default -> Math.max(14, Math.min(36, header.length() + 4));
        };
    }
}
