package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import com.procurepal_services.stock_bridge_api.selling.dto.SaleResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tables' bills (V99, selling till Step 7; owner, 2026-11-19). A bill is a sale kept open for a table, or a name, and
 * paid later: its id is the sale id of every line sent to it. Sending (the ordinary stock-out of each line, see
 * {@link BillLineRecorder}) takes the stock then; paying settles it into the drawer of the till that took the money.
 * Until paid, it's on Tables and stays out of Sales.
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SaleService saleService;
    private final SalePaymentService salePaymentService;
    private final PaymentAccountService paymentAccountService;
    private final CashDayService cashDayService;
    private final BranchAccessService branchAccessService;

    /** An open bill on the grid: what it's for, what's on it so far, since when and who opened it. */
    public record BillSummary(
            UUID id, UUID branchId, UUID tableId, String name, String label, String status, OffsetDateTime openedAt,
            String openedByName, BigDecimal total, int items, OffsetDateTime lastSentAt) {}

    /** One sent line, as the bill shows it: grouped by when and by whom it was sent; a side names its meal's key. */
    public record BillLine(
            UUID movementId, UUID lineKey, UUID withKey, UUID productId, String productName, UUID categoryId, BigDecimal quantity,
            String enteredUnit, BigDecimal enteredQuantity, BigDecimal amount, UUID areaId, String areaName, String note,
            OffsetDateTime sentAt, String sentByName, String takenOff, String takenOffNote) {}

    public record Bill(BillSummary summary, List<BillLine> lines) {}

    /** A table's bill ({@code tableId}), or one for a name (a bar tab, a takeaway). */
    public record OpenRequest(UUID branchId, UUID tableId, String name) {}

    /** NOT_MADE: stock goes back. SENT_BACK: stock stays used, not charged. Both need a few words why. */
    public record TakeOffRequest(String kind, String note) {}

    /** How the whole bill was paid, and the area of the till taking it - the drawer the cash goes into. */
    public record PayRequest(SalePaymentMethod method, UUID accountId, UUID areaId) {}

    // ------------------------------------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<BillSummary> open(UUID clientId, UUID userId, UUID branchId) {
        requireBranch(userId, branchId);
        return jdbc.query(SUMMARY + " WHERE b.client_id = :clientId AND b.branch_id = :branchId AND b.status = 'OPEN'"
                + " ORDER BY b.opened_at", new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId),
                BillService::summary);
    }

    @Transactional(readOnly = true)
    public Bill get(UUID clientId, UUID userId, UUID billId) {
        BillSummary summary = summaryOf(clientId, billId);
        requireBranch(userId, summary.branchId());
        List<BillLine> lines = jdbc.query("""
                SELECT o.id, bl.line_key, bl.with_key, o.product_id, p.name AS product_name, p.company_category_id, o.quantity, o.entered_unit,
                       o.entered_quantity, COALESCE(o.sale_amount, c.sale_amount) AS amount, o.area_id, a.name AS area_name,
                       bl.note, bl.sent_at,
                       COALESCE(NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''), u.username) AS sent_by_name,
                       CASE WHEN bl.taken_off IS NULL AND r.id IS NOT NULL THEN 'NOT_MADE' ELSE bl.taken_off END AS taken_off,
                       bl.taken_off_note
                FROM bill_lines bl
                JOIN stock_movements o ON o.id = bl.movement_id
                JOIN products p ON p.id = o.product_id
                LEFT JOIN areas a ON a.id = o.area_id
                LEFT JOIN users u ON u.id = bl.sent_by
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                WHERE bl.bill_id = :billId AND bl.client_id = :clientId
                ORDER BY bl.sent_at, o.created_at, o.id
                """, new MapSqlParameterSource().addValue("billId", billId).addValue("clientId", clientId),
                (rs, n) -> new BillLine(rs.getObject("id", UUID.class), rs.getObject("line_key", UUID.class),
                        rs.getObject("with_key", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                        rs.getObject("company_category_id", UUID.class),
                        rs.getBigDecimal("quantity"), rs.getString("entered_unit"), rs.getBigDecimal("entered_quantity"),
                        rs.getBigDecimal("amount"), rs.getObject("area_id", UUID.class), rs.getString("area_name"),
                        rs.getString("note"), rs.getObject("sent_at", OffsetDateTime.class), rs.getString("sent_by_name"),
                        rs.getString("taken_off"), rs.getString("taken_off_note")));
        return new Bill(summary, lines);
    }

    /** Where each of a branch's products comes from on a bill, and so where its ticket goes. */
    public record ServedFrom(UUID productId, UUID areaId) {}

    /**
     * Each item on a bill comes from where it's made or kept (owner, 2026-11-19): a made-here dish from its Made in area,
     * a kept product from the area holding it - one that sells (the Bar) before the delivery area (the Store), which only
     * supplies the others. Products with neither are left out: the till's own area takes them.
     */
    @Transactional(readOnly = true)
    public List<ServedFrom> servedFrom(UUID clientId, UUID userId, UUID branchId) {
        requireBranch(userId, branchId);
        return jdbc.query("""
                SELECT p.id, COALESCE(mn.id, mi.area_id, ks.area_id) AS area_id
                FROM products p
                JOIN product_branches pb ON pb.product_id = p.id AND pb.branch_id = :branchId
                -- Made in, as the recipe reads it (MadeWithService.areaAt): by name first, then this branch's choice.
                LEFT JOIN LATERAL (
                    SELECT a.id FROM areas a
                    WHERE a.branch_id = :branchId AND a.is_active AND lower(a.name) = lower(p.made_in_area_name)
                    LIMIT 1
                ) mn ON TRUE
                LEFT JOIN product_made_in mi ON mi.product_id = p.id AND mi.branch_id = :branchId
                LEFT JOIN LATERAL (
                    SELECT s.area_id FROM area_stock s JOIN areas a ON a.id = s.area_id AND a.is_active
                    WHERE s.product_id = p.id AND s.branch_id = :branchId
                    ORDER BY (s.quantity_on_hand > 0) DESC, a.is_delivery, s.quantity_on_hand DESC
                    LIMIT 1
                ) ks ON TRUE
                WHERE p.client_id = :clientId AND p.is_active AND COALESCE(mn.id, mi.area_id, ks.area_id) IS NOT NULL
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId),
                (rs, n) -> new ServedFrom(rs.getObject("id", UUID.class), rs.getObject("area_id", UUID.class)));
    }

    // ------------------------------------------------------------------------------------------- changing

    /** Opens a bill - or hands back the table's open one, so two phones opening Table 5 share one bill. */
    @Transactional
    public BillSummary open(UUID clientId, UUID userId, OpenRequest request) {
        if (request == null || request.branchId() == null) {
            throw new MadeWithException("Choose the branch first.");
        }
        requireBranch(userId, request.branchId());
        String name = request.name() == null ? null : request.name().trim().replaceAll("\\s+", " ");
        if (request.tableId() != null) {
            Map<String, Object> table = jdbc.queryForList(
                    "SELECT branch_id, is_active FROM branch_tables WHERE id = :id AND client_id = :clientId",
                    new MapSqlParameterSource().addValue("id", request.tableId()).addValue("clientId", clientId))
                    .stream().findFirst().orElseThrow(() -> new MadeWithException("No such table."));
            if (!request.branchId().equals(table.get("branch_id"))) {
                throw new MadeWithException("That table is at another branch.");
            }
            if (!Boolean.TRUE.equals(table.get("is_active"))) {
                throw new MadeWithException("That table is switched off.");
            }
            List<UUID> existing = jdbc.queryForList("SELECT id FROM bills WHERE table_id = :id AND status = 'OPEN'",
                    new MapSqlParameterSource("id", request.tableId()), UUID.class);
            if (!existing.isEmpty()) {
                return summaryOf(clientId, existing.get(0));
            }
            name = null;
        } else if (name == null || name.isEmpty()) {
            throw new MadeWithException("Choose a table, or give the bill a name.");
        } else if (name.length() > 60) {
            throw new MadeWithException("Keep the name to 60 letters.");
        }
        UUID id = UUID.randomUUID();
        // Two phones opening the same free table at once: the second finds the first's bill.
        int made = jdbc.update("""
                INSERT INTO bills (id, client_id, branch_id, table_id, name, opened_by)
                VALUES (:id, :clientId, :branchId, :tableId, :name, :userId)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId)
                .addValue("branchId", request.branchId()).addValue("tableId", request.tableId()).addValue("name", name)
                .addValue("userId", userId));
        if (made == 0) {
            UUID theirs = jdbc.queryForObject("SELECT id FROM bills WHERE table_id = :id AND status = 'OPEN'",
                    new MapSqlParameterSource("id", request.tableId()), UUID.class);
            return summaryOf(clientId, theirs);
        }
        return summaryOf(clientId, id);
    }

    /** Takes a sent line off an open bill, saying why; "Not made yet" puts its stock back, "Made, sent back" doesn't. */
    @Transactional
    public Bill takeOff(UUID clientId, UUID userId, UUID billId, UUID movementId, TakeOffRequest request) {
        BillSummary summary = requireOpen(clientId, userId, billId);
        String kind = request == null ? null : request.kind();
        if (!"NOT_MADE".equals(kind) && !"SENT_BACK".equals(kind)) {
            throw new MadeWithException("Say whether it was made yet.");
        }
        String note = request.note() == null ? "" : request.note().trim();
        if (note.isEmpty()) {
            throw new MadeWithException("Say why, in a few words.");
        }
        if (note.length() > 200) {
            throw new MadeWithException("Keep it to 200 letters.");
        }
        Map<String, Object> line = jdbc.queryForList("""
                SELECT bl.taken_off, EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = bl.movement_id) AS reversed
                FROM bill_lines bl WHERE bl.movement_id = :id AND bl.bill_id = :billId
                """, new MapSqlParameterSource().addValue("id", movementId).addValue("billId", billId))
                .stream().findFirst().orElseThrow(() -> new MadeWithException("That item isn't on this bill."));
        if (line.get("taken_off") != null || Boolean.TRUE.equals(line.get("reversed"))) {
            throw new MadeWithException("That item is already off the bill.");
        }
        if ("NOT_MADE".equals(kind)) {
            saleService.cancelBillLine(billId, movementId, userId);
        }
        jdbc.update("""
                UPDATE bill_lines SET taken_off = :kind, taken_off_note = :note, taken_off_by = :userId, taken_off_at = now()
                WHERE movement_id = :id
                """, new MapSqlParameterSource().addValue("kind", kind).addValue("note", note).addValue("userId", userId)
                .addValue("id", movementId));
        return get(clientId, userId, summary.id());
    }

    /** Pays the whole bill: it becomes a sale like any other, its money in the drawer of the till that took it. */
    @Transactional
    public SaleResponse pay(UUID clientId, UUID userId, UUID billId, PayRequest request) {
        BillSummary summary = requireOpen(clientId, userId, billId);
        if (request == null || request.method() == null) {
            throw new MadeWithException("Say how they paid.");
        }
        if (summary.items() == 0) {
            throw new MadeWithException("Nothing on this bill to pay. Close it instead.");
        }
        UUID branchId = summary.branchId();
        UUID account = null;
        if (request.method() == SalePaymentMethod.TRANSFER) {
            List<PaymentAccountService.PaymentAccount> accounts =
                    paymentAccountService.byBranch(clientId, false).getOrDefault(branchId, List.of());
            if (request.accountId() != null) {
                paymentAccountService.requireUsable(clientId, branchId, request.accountId());
                account = request.accountId();
            } else if (accounts.size() == 1) {
                account = accounts.get(0).id();
            }
        }
        if (request.areaId() != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM areas WHERE id = :id AND branch_id = :branchId)",
                new MapSqlParameterSource().addValue("id", request.areaId()).addValue("branchId", branchId), Boolean.class))) {
            throw new MadeWithException("That till's area isn't at this bill's branch.");
        }
        salePaymentService.recordIfAbsent(clientId, billId, branchId, request.method(), account, userId);
        jdbc.update("""
                INSERT INTO bill_payments (client_id, bill_id, branch_id, area_id, amount, method, payment_account_id, paid_by)
                VALUES (:clientId, :billId, :branchId, :areaId, :amount, :method, :accountId, :userId)
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("billId", billId)
                .addValue("branchId", branchId).addValue("areaId", request.areaId()).addValue("amount", summary.total())
                .addValue("method", request.method().name()).addValue("accountId", account).addValue("userId", userId));
        int closed = jdbc.update("""
                UPDATE bills SET status = 'PAID', closed_by = :userId, closed_at = now() WHERE id = :id AND status = 'OPEN'
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("id", billId));
        if (closed == 0) {
            throw new MadeWithException("This bill was just paid on another phone.");
        }
        if (request.method() == SalePaymentMethod.CASH) {
            cashDayService.onCashSale(clientId, branchId, request.areaId(), userId);
        }
        return saleService.get(billId);
    }

    /** Closes a bill with nothing left on it - every item came off, or it was opened by mistake. */
    @Transactional
    public BillSummary close(UUID clientId, UUID userId, UUID billId) {
        BillSummary summary = requireOpen(clientId, userId, billId);
        if (summary.items() > 0) {
            throw new MadeWithException("There are still items on this bill. Take them off or pay it.");
        }
        jdbc.update("UPDATE bills SET status = 'CANCELLED', closed_by = :userId, closed_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("userId", userId).addValue("id", billId));
        return summaryOf(clientId, billId);
    }

    // ------------------------------------------------------------------------------------------- shared

    /** The bill as the grid shows it; live lines only (not taken off) count. */
    private static final String SUMMARY = """
            SELECT b.id, b.branch_id, b.table_id, b.name, t.name AS table_name, b.status, b.opened_at,
                   COALESCE(NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''), u.username) AS opened_by_name,
                   COALESCE(x.total, 0) AS total, COALESCE(x.items, 0) AS items, x.last_sent
            FROM bills b
            LEFT JOIN branch_tables t ON t.id = b.table_id
            LEFT JOIN users u ON u.id = b.opened_by
            LEFT JOIN LATERAL (
                SELECT SUM(COALESCE(o.sale_amount, c.sale_amount, 0)) AS total, COUNT(*) AS items, MAX(bl.sent_at) AS last_sent
                FROM bill_lines bl
                JOIN stock_movements o ON o.id = bl.movement_id
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                WHERE bl.bill_id = b.id AND r.id IS NULL AND bl.taken_off IS NULL
            ) x ON TRUE""";

    private static BillSummary summary(ResultSet rs, int n) throws SQLException {
        String table = rs.getString("table_name");
        String label = table == null ? rs.getString("name") : table.matches("\\d+") ? "Table " + table : table;
        return new BillSummary(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getObject("table_id", UUID.class), rs.getString("name"), label, rs.getString("status"),
                rs.getObject("opened_at", OffsetDateTime.class), rs.getString("opened_by_name"), rs.getBigDecimal("total"),
                rs.getInt("items"), rs.getObject("last_sent", OffsetDateTime.class));
    }

    private BillSummary summaryOf(UUID clientId, UUID billId) {
        return jdbc.query(SUMMARY + " WHERE b.id = :id AND b.client_id = :clientId",
                new MapSqlParameterSource().addValue("id", billId).addValue("clientId", clientId), BillService::summary)
                .stream().findFirst().orElseThrow(() -> new MadeWithException("No such bill."));
    }

    private BillSummary requireOpen(UUID clientId, UUID userId, UUID billId) {
        BillSummary summary = summaryOf(clientId, billId);
        requireBranch(userId, summary.branchId());
        if (!"OPEN".equals(summary.status())) {
            throw new MadeWithException("PAID".equals(summary.status()) ? "This bill is already paid." : "This bill is closed.");
        }
        return summary;
    }

    private void requireBranch(UUID userId, UUID branchId) {
        if (!branchAccessService.writeBranchIds(userId).contains(branchId)) {
            throw new AccessDeniedException("You don't sell at that branch.");
        }
    }
}
