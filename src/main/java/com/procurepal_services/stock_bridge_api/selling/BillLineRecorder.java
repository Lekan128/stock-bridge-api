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
    private final TicketPushes ticketPushes;

    /**
     * A counter sale's line (V109; owner 2026-11-21): kept for the kitchen with its side's meal and note, and put on the
     * ticket of {@code madeIn} when that's another area than the till's - made to order. Made where the till is, or not
     * made anywhere, it's handed over and sends nothing. Nothing at all where the branch has tickets off.
     */
    public void recordCounter(UUID clientId, StockOutRequest request, UUID movementId, UUID saleId, UUID branchId, UUID madeIn,
            UUID userId) {
        Boolean ticketsOn = branchId == null ? Boolean.FALSE : jdbc.queryForObject(
                "SELECT tickets_on FROM branches WHERE id = :id", new MapSqlParameterSource("id", branchId), Boolean.class);
        if (!Boolean.TRUE.equals(ticketsOn) || saleId == null) {
            return;
        }
        StockOutRequest.CounterLine line = request.counterLine();
        String note = line.note() == null || line.note().isBlank() ? null : line.note().trim();
        jdbc.update("""
                INSERT INTO counter_lines (movement_id, sale_id, client_id, line_key, with_key, note, sent_by)
                VALUES (:movementId, :saleId, :clientId, :key, :withKey, :note, :userId)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("movementId", movementId).addValue("saleId", saleId)
                        .addValue("clientId", clientId).addValue("key", line.lineKey()).addValue("withKey", line.withKey())
                        .addValue("note", note).addValue("userId", userId));
        if (madeIn == null || madeIn.equals(request.areaId())) {
            return;
        }
        UUID made = TicketService.addCounterToTicket(jdbc, clientId, saleId, branchId, madeIn, movementId, userId);
        if (made != null) {
            ticketPushes.announceLater(made);
        }
    }

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
        UUID made = TicketService.addToTicket(jdbc, clientId, saleId, movementId, line.sendKey() == null ? movementId : line.sendKey(), userId);
        if (made != null) {
            ticketPushes.announceLater(made);
        }
    }
}
