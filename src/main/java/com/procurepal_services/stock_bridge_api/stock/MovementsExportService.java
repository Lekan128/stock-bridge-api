package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.imports.io.WorkbookBuilder;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Service;

/**
 * The stock movements report as a spreadsheet (Phase 3, MULTI_BRANCH_BUILD_PLAN.md section 8): the
 * rows the screen shows for the same filters, newest first, with the branch each happened at when the
 * company has more than one.
 */
@Service
@RequiredArgsConstructor
public class MovementsExportService {

    /** A spreadsheet is for reading, not for the whole ledger; the file says when it stops. */
    public static final int MAX_ROWS = 5000;
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");
    private static final Map<MovementType, String> TYPES = Map.of(
            MovementType.IN, "Stock in",
            MovementType.OUT, "Stock out",
            MovementType.ADJUSTMENT, "Adjustment",
            MovementType.TRANSFER_OUT, "Sent to another branch",
            MovementType.TRANSFER_IN, "Received from another branch");

    private final BranchRepository branchRepository;

    public byte[] export(UUID clientId, List<StockMovementResponse> movements, boolean truncated, boolean multiBranch) {
        Map<UUID, String> branchNames = multiBranch
                ? branchRepository.findAllByClientId(clientId).stream().collect(Collectors.toMap(Branch::getId, Branch::getName))
                : Map.of();
        List<String> headers = new ArrayList<>(List.of("When", "Product", "SKU", "Type", "Quantity", "Unit", "Reason"));
        if (multiBranch) headers.add("Branch");
        headers.addAll(List.of("Supplier", "Unit price (₦)", "Value (₦)", "Recorded by", "Note"));
        Map<String, Integer> widths = new LinkedHashMap<>();
        headers.forEach(header -> widths.put(header, switch (header) {
            case "Product", "Note" -> 32;
            case "Type" -> 26;
            default -> 16;
        }));
        try (WorkbookBuilder builder = new WorkbookBuilder("Stock movements")) {
            builder.writeHeaderRow(headers, widths, Map.of());
            CellStyle money = builder.moneyStyle();
            int rowIndex = 1;
            for (StockMovementResponse movement : movements) {
                Row row = builder.sheet().createRow(rowIndex++);
                int col = 0;
                row.createCell(col++).setCellValue(movement.occurredAt() == null ? ""
                        : TIME.format(movement.occurredAt().atZoneSameInstant(LAGOS)));
                row.createCell(col++).setCellValue(text(movement.productName()));
                row.createCell(col++).setCellValue(text(movement.productSku()));
                row.createCell(col++).setCellValue(TYPES.getOrDefault(movement.movementType(), movement.movementType().name()));
                row.createCell(col++).setCellValue(signed(movement));
                row.createCell(col++).setCellValue(text(movement.unitOfMeasure()).toLowerCase(java.util.Locale.ROOT));
                row.createCell(col++).setCellValue(movement.outReason() == null ? "" : movement.outReason().name().replace('_', ' ').toLowerCase(java.util.Locale.ROOT));
                if (multiBranch) row.createCell(col++).setCellValue(branchNames.getOrDefault(movement.branchId(), ""));
                row.createCell(col++).setCellValue(text(movement.companyVendorName()));
                moneyCell(row, col++, movement.unitPriceAtTime(), money);
                moneyCell(row, col++, movement.lineValue(), money);
                row.createCell(col++).setCellValue(text(movement.createdByName()));
                row.createCell(col).setCellValue(text(movement.note()));
            }
            if (truncated) {
                builder.sheet().createRow(rowIndex + 1).createCell(0).setCellValue(
                        "Only the newest %d movements are listed. Narrow the dates to see the rest.".formatted(MAX_ROWS));
            }
            return builder.toBytes();
        }
    }

    /** In the direction it moved the shelf: out, sent and a negative adjustment are minus. */
    private static int signed(StockMovementResponse movement) {
        return switch (movement.movementType()) {
            case OUT, TRANSFER_OUT -> -Math.abs(movement.quantity());
            case IN, TRANSFER_IN -> Math.abs(movement.quantity());
            case ADJUSTMENT -> movement.quantity();
        };
    }

    private static void moneyCell(Row row, int col, BigDecimal value, CellStyle money) {
        if (value == null) {
            row.createCell(col).setCellValue("");
            return;
        }
        var cell = row.createCell(col);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(money);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
