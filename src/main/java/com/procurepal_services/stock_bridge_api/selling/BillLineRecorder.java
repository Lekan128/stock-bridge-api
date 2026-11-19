package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A line sent to a table's bill (V99, selling till Step 7): the stock-out of a sale line, in its own transaction,
 * also says which bill it's on, the meal a side goes with and its note for the kitchen. Kept apart from
 * {@link BillService} so the stock-out needs nothing that needs it back.
 */
@Component
@RequiredArgsConstructor
public class BillLineRecorder {

    private final NamedParameterJdbcTemplate jdbc;

    /** Called before the sale's payment is looked at: a bill line carries none, and its bill must still be open. */
    public void record(UUID clientId, StockOutRequest request, UUID movementId, UUID saleId, UUID branchId, UUID userId) {
        if (request.paymentMethod() == SalePaymentMethod.SPLIT) {
            throw new MadeWithException("Choose cash, transfer or card.");
        }
        if (request.billLine() == null) {
            return;
        }
        if (request.saleId() == null) {
            throw new MadeWithException("This item isn't on a bill. Open the table again.");
        }
        if (request.paymentMethod() != null) {
            throw new MadeWithException("A bill is paid when it's settled, not item by item.");
        }
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT status, branch_id FROM bills WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", saleId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new MadeWithException("That bill isn't open any more. Open the table again.");
        }
        if (!"OPEN".equals(found.get(0).get("status"))) {
            throw new MadeWithException("That bill was paid or closed. Open the table again to start a new one.");
        }
        if (branchId != null && !branchId.equals(found.get(0).get("branch_id"))) {
            throw new MadeWithException("That bill is at another branch.");
        }
        StockOutRequest.BillLine line = request.billLine();
        String note = line.note() == null || line.note().isBlank() ? null : line.note().trim();
        jdbc.update("""
                INSERT INTO bill_lines (movement_id, bill_id, client_id, line_key, with_key, note, sent_by)
                VALUES (:movementId, :billId, :clientId, :key, :withKey, :note, :userId)
                """,
                new MapSqlParameterSource().addValue("movementId", movementId).addValue("billId", saleId)
                        .addValue("clientId", clientId).addValue("key", line.lineKey()).addValue("withKey", line.withKey())
                        .addValue("note", note).addValue("userId", userId));
    }
}
