package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.AreaAccessService;
import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Kitchen and bar tickets (V102, selling till Step 8; owner, 2026-11-19). Each Send on a bill gives every area its items
 * come from a ticket of just its items - the bill's first send NEW, later ones ADDED - and an item taken off before it was
 * made gives its area a DONT_MAKE ticket. Each area's Tickets page, on a phone or tablet there, shows what's waiting and
 * prints it; Done is marked there.
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    /** Done tickets stay in view this long, greyed, in case one was marked by mistake. */
    private static final int DONE_SHOWN = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final AreaAccessService areaAccessService;
    private final BranchAccessService branchAccessService;

    public record Ticket(UUID id, UUID billId, String kind, String label, OffsetDateTime createdAt, String createdByName,
            OffsetDateTime doneAt, List<TicketLine> lines) {}

    /**
     * One item on a ticket.
     *
     * @param withKey the meal a side goes with: on this ticket ({@code lineKey} of another line), or sent earlier, when
     *     {@code withName} says which.
     * @param takenOff NOT_MADE or SENT_BACK once taken off the bill.
     */
    public record TicketLine(UUID movementId, UUID lineKey, UUID withKey, String name, BigDecimal quantity, String unit,
            String note, String takenOff, String withName) {}

    /** An area someone can keep tickets for, and how many are waiting there. */
    public record TicketArea(UUID id, String name, long waiting) {}

    // ---------------------------------------------------------------------------------------- made by bills

    /** A line sent to a bill joins its Send's ticket for the area it came from; the first line of each makes it. */
    static void addToTicket(NamedParameterJdbcTemplate jdbc, UUID clientId, UUID billId, UUID movementId, UUID sendKey,
            UUID userId) {
        UUID areaId = jdbc.queryForList("SELECT area_id FROM stock_movements WHERE id = :id",
                new MapSqlParameterSource("id", movementId), UUID.class).stream().findFirst().orElse(null);
        if (areaId == null) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("billId", billId)
                .addValue("areaId", areaId).addValue("sendKey", sendKey).addValue("userId", userId)
                .addValue("movementId", movementId);
        jdbc.update("""
                INSERT INTO tickets (client_id, branch_id, area_id, bill_id, send_key, kind, created_by)
                SELECT :clientId, b.branch_id, :areaId, b.id, :sendKey,
                       CASE WHEN EXISTS (SELECT 1 FROM tickets t WHERE t.bill_id = b.id AND t.send_key <> :sendKey
                                         AND t.kind <> 'DONT_MAKE') THEN 'ADDED' ELSE 'NEW' END,
                       :userId
                FROM bills b WHERE b.id = :billId AND b.client_id = :clientId
                ON CONFLICT (bill_id, area_id, send_key) DO NOTHING
                """, params);
        jdbc.update("""
                INSERT INTO ticket_lines (ticket_id, movement_id)
                SELECT id, :movementId FROM tickets WHERE bill_id = :billId AND area_id = :areaId AND send_key = :sendKey
                ON CONFLICT DO NOTHING
                """, params);
    }

    /** An item taken off before it was made: its area gets a ticket saying not to make it. */
    static void dontMake(NamedParameterJdbcTemplate jdbc, UUID clientId, UUID billId, UUID movementId, UUID userId) {
        List<UUID> made = jdbc.queryForList("""
                INSERT INTO tickets (client_id, branch_id, area_id, bill_id, send_key, kind, created_by)
                SELECT :clientId, b.branch_id, m.area_id, b.id, gen_random_uuid(), 'DONT_MAKE', :userId
                FROM bills b JOIN stock_movements m ON m.id = :movementId
                WHERE b.id = :billId AND b.client_id = :clientId AND m.area_id IS NOT NULL
                RETURNING id
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("billId", billId)
                .addValue("movementId", movementId).addValue("userId", userId), UUID.class);
        for (UUID ticket : made) {
            jdbc.update("INSERT INTO ticket_lines (ticket_id, movement_id) VALUES (:ticket, :movementId)",
                    new MapSqlParameterSource().addValue("ticket", ticket).addValue("movementId", movementId));
        }
    }

    // ---------------------------------------------------------------------------------------- the Tickets page

    /**
     * The areas at a branch this person works in that tickets go to (any in the last 30 days; all of them before the
     * first), each with its tickets waiting.
     */
    @Transactional(readOnly = true)
    public List<TicketArea> areas(UUID clientId, UUID userId, UUID branchId) {
        if (!branchAccessService.viewBranchIds(userId).contains(branchId)) {
            throw new AccessDeniedException("Not your branch.");
        }
        List<UUID> mine = areaAccessService.workAreaIds(userId, branchId);
        if (mine.isEmpty()) {
            return List.of();
        }
        List<TicketArea> all = jdbc.query("""
                SELECT a.id, a.name,
                       (SELECT COUNT(*) FROM tickets t WHERE t.area_id = a.id AND t.done_at IS NULL
                          AND t.created_at > now() - interval '1 day') AS waiting,
                       EXISTS (SELECT 1 FROM tickets t WHERE t.area_id = a.id AND t.created_at > now() - interval '30 days') AS used
                FROM areas a
                WHERE a.client_id = :clientId AND a.id IN (:areas)
                ORDER BY a.sort_order, a.created_at
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("areas", mine),
                (rs, n) -> rs.getBoolean("used") ? new TicketArea(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("waiting"))
                        : new TicketArea(rs.getObject("id", UUID.class), rs.getString("name"), -1));
        // The areas tickets go to - the Kitchen and the Bar, not the store room - once any have; before the first, all.
        List<TicketArea> used = all.stream().filter(area -> area.waiting() >= 0).toList();
        return used.isEmpty() ? all.stream().map(area -> new TicketArea(area.id(), area.name(), 0)).toList() : used;
    }

    /**
     * An area's tickets: those waiting, oldest first (a ticket not done in a day is let go), then the last few done,
     * newest first.
     */
    @Transactional(readOnly = true)
    public List<Ticket> list(UUID clientId, UUID userId, UUID areaId) {
        requireArea(clientId, userId, areaId);
        Map<UUID, Ticket> tickets = new LinkedHashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("areaId", areaId);
        String select = """
                SELECT t.id, t.bill_id, t.kind, t.created_at, t.done_at, bt.name AS table_name, b.name AS bill_name,
                       COALESCE(NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''), u.username) AS by_name
                FROM tickets t
                JOIN bills b ON b.id = t.bill_id
                LEFT JOIN branch_tables bt ON bt.id = b.table_id
                LEFT JOIN users u ON u.id = t.created_by
                WHERE t.client_id = :clientId AND t.area_id = :areaId AND t.created_at > now() - interval '1 day'
                """;
        org.springframework.jdbc.core.RowCallbackHandler add = rs -> {
            String table = rs.getString("table_name");
            String label = table == null ? rs.getString("bill_name") : table.matches("\\d+") ? "Table " + table : table;
            UUID id = rs.getObject("id", UUID.class);
            tickets.put(id, new Ticket(id, rs.getObject("bill_id", UUID.class), rs.getString("kind"), label,
                    rs.getObject("created_at", OffsetDateTime.class), rs.getString("by_name"),
                    rs.getObject("done_at", OffsetDateTime.class), new ArrayList<>()));
        };
        jdbc.query(select + " AND t.done_at IS NULL ORDER BY t.created_at, t.id", params, add);
        jdbc.query(select + " AND t.done_at IS NOT NULL ORDER BY t.done_at DESC LIMIT " + DONE_SHOWN, params, add);
        if (tickets.isEmpty()) {
            return List.of();
        }
        jdbc.query("""
                SELECT tl.ticket_id, o.id AS movement_id, bl.line_key, bl.with_key, p.name,
                       COALESCE(o.entered_quantity, o.quantity) AS quantity, o.entered_unit, bl.note,
                       CASE WHEN bl.taken_off IS NULL AND r.id IS NOT NULL THEN 'NOT_MADE' ELSE bl.taken_off END AS taken_off,
                       (SELECT wp.name FROM bill_lines w
                          JOIN stock_movements wm ON wm.id = w.movement_id
                          JOIN products wp ON wp.id = wm.product_id
                        WHERE w.bill_id = bl.bill_id AND w.line_key = bl.with_key LIMIT 1) AS with_name
                FROM ticket_lines tl
                JOIN stock_movements o ON o.id = tl.movement_id
                JOIN bill_lines bl ON bl.movement_id = o.id
                JOIN products p ON p.id = o.product_id
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                WHERE tl.ticket_id IN (:ids)
                ORDER BY bl.sent_at, o.created_at, o.id
                """, new MapSqlParameterSource("ids", tickets.keySet()), rs -> {
                    tickets.get(rs.getObject("ticket_id", UUID.class)).lines().add(new TicketLine(
                            rs.getObject("movement_id", UUID.class), rs.getObject("line_key", UUID.class),
                            rs.getObject("with_key", UUID.class), rs.getString("name"), rs.getBigDecimal("quantity"),
                            rs.getString("entered_unit"), rs.getString("note"), rs.getString("taken_off"),
                            rs.getString("with_name")));
                });
        return List.copyOf(tickets.values());
    }

    /** Done, or not done after all (a mistaken tap): the ticket moves between waiting and done. */
    @Transactional
    public List<Ticket> done(UUID clientId, UUID userId, UUID ticketId, boolean done) {
        UUID areaId = jdbc.queryForList("SELECT area_id FROM tickets WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", ticketId).addValue("clientId", clientId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new MadeWithException("That ticket isn't there any more."));
        requireArea(clientId, userId, areaId);
        jdbc.update(done
                ? "UPDATE tickets SET done_at = now(), done_by = :userId WHERE id = :id AND done_at IS NULL"
                : "UPDATE tickets SET done_at = NULL, done_by = NULL WHERE id = :id",
                new MapSqlParameterSource().addValue("id", ticketId).addValue("userId", userId));
        return list(clientId, userId, areaId);
    }

    private void requireArea(UUID clientId, UUID userId, UUID areaId) {
        UUID branchId = jdbc.queryForList("SELECT branch_id FROM areas WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", areaId).addValue("clientId", clientId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new MadeWithException("That area isn't there any more."));
        if (!branchAccessService.viewBranchIds(userId).contains(branchId)
                || !areaAccessService.workAreaIds(userId, branchId).contains(areaId)) {
            throw new AccessDeniedException("You don't work in that area.");
        }
    }
}
