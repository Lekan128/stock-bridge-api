package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportRow;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Service;

/**
 * The Sales report as a spreadsheet (SELLING_PRICE_PLAN.md section 6.11): the rows on screen and the
 * same footer, computed by the server, so the file and the page can never disagree. Profit columns
 * cover only lines with both a price and a cost, and the last lines say how many were left out.
 */
@Service
public class SalesExportService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    public byte[] export(SalesReportResponse report) {
        String grouping = report.groupBy();
        List<String> headers = new ArrayList<>();
        headers.add(switch (grouping) {
            case "sale" -> "Time";
            case "product" -> "Product";
            case "day" -> "Day";
            case "user" -> "Person";
            case "branch" -> "Branch";
            default -> "Category";
        });
        // V51: at a multi-branch company each sale says where it was made.
        boolean branchColumn = grouping.equals("sale") && report.rows().stream().anyMatch(line -> line.branch() != null);
        if (grouping.equals("sale")) {
            headers.add("Recorded by");
            headers.add("Items");
            if (branchColumn) headers.add("Branch");
        } else if (grouping.equals("product")) {
            headers.add("Sold");
        } else {
            headers.add("Sales");
        }
        headers.addAll(List.of("Sales (₦)", "Cost (₦)", "Profit (₦)", "Margin"));
        Map<String, Integer> widths = new LinkedHashMap<>();
        headers.forEach(header -> widths.put(header, header.equals("Items") ? 40 : 18));

        try (WorkbookBuilder builder = new WorkbookBuilder("Sales")) {
            builder.writeHeaderRow(headers, widths, Map.of());
            CellStyle money = builder.moneyStyle();
            int rowIndex = 1;
            for (SalesReportRow line : report.rows()) {
                Row row = builder.sheet().createRow(rowIndex++);
                int col = 0;
                if (grouping.equals("sale")) {
                    row.createCell(col++).setCellValue(line.occurredAt() == null ? "" : TIME.format(line.occurredAt().atZoneSameInstant(LAGOS)));
                    row.createCell(col++).setCellValue(line.label() == null ? "" : line.label());
                    row.createCell(col++).setCellValue(line.detail() == null ? "" : line.detail());
                    if (branchColumn) row.createCell(col++).setCellValue(line.branch() == null ? "" : line.branch());
                } else if (grouping.equals("product")) {
                    row.createCell(col++).setCellValue(line.label() == null ? "" : line.label());
                    row.createCell(col++).setCellValue((line.quantity() == null ? 0 : line.quantity())
                            + (line.unitOfMeasure() == null ? "" : " " + line.unitOfMeasure().toLowerCase(java.util.Locale.ROOT)));
                } else {
                    row.createCell(col++).setCellValue(line.label() == null ? "No category" : line.label());
                    row.createCell(col++).setCellValue(line.totals().saleCount());
                }
                writeMoney(row, col, line.totals(), money);
            }
            Row total = builder.sheet().createRow(rowIndex++);
            Cell label = total.createCell(0);
            label.setCellValue("Total");
            label.setCellStyle(builder.headerStyle());
            int moneyColumn = headers.indexOf("Sales (₦)");
            if (!grouping.equals("product") && !grouping.equals("sale")) {
                total.createCell(1).setCellValue(report.totals().saleCount());
            }
            writeMoney(total, moneyColumn, report.totals(), money);

            SalesTotals totals = report.totals();
            rowIndex++;
            if (totals.unpricedLineCount() > 0 || totals.unknownCostLineCount() > 0) {
                builder.sheet().createRow(rowIndex++).createCell(0).setCellValue(
                        "Profit leaves out %d line(s): %d with no price and %d with no cost."
                                .formatted(totals.unpricedLineCount() + totals.unknownCostLineCount(),
                                        totals.unpricedLineCount(), totals.unknownCostLineCount()));
            }
            if (totals.cancelledLineCount() > 0) {
                builder.sheet().createRow(rowIndex++).createCell(0).setCellValue(
                        "%d cancelled line(s) are not counted.".formatted(totals.cancelledLineCount()));
            }
            if (report.truncated()) {
                builder.sheet().createRow(rowIndex).createCell(0).setCellValue(
                        "Only the first %d rows are listed; the total covers them all.".formatted(report.rows().size()));
            }
            return builder.toBytes();
        }
    }

    private static void writeMoney(Row row, int col, SalesTotals totals, CellStyle money) {
        cell(row, col, totals.salesAmount(), money);
        cell(row, col + 1, totals.costAmount(), money);
        cell(row, col + 2, totals.grossProfit(), money);
        Cell margin = row.createCell(col + 3);
        if (totals.margin() != null) {
            margin.setCellValue(totals.margin().movePointRight(2).setScale(1, java.math.RoundingMode.HALF_UP) + "%");
        }
    }

    private static void cell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
            cell.setCellStyle(style);
        }
    }
}
