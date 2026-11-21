package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.AreaAccessService;
import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * Kitchen and bar tickets (V102, V103; selling till Step 8; owner, 2026-11-19, claude.ai/artifact/9knT5eyUVVW2H7xrCDGfUX).
 * At a branch with tickets switched on, each Send on a bill gives every area its items come from a ticket of just its
 * items - the bill's first send NEW, later ones ADDED. The area taps Ready; the waiter taps Served as it reaches the
 * table (Served counts as Ready). An item taken off before it was made shows on its ticket as "Don't send" until
 * someone in the area taps OK; then it leaves the ticket, and a ticket with nothing left leaves the screen.
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    /** Ready tickets stay in view this long, greyed, in case one was marked by mistake. */
    private static final int READY_SHOWN = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final AreaAccessService areaAccessService;
    private final BranchAccessService branchAccessService;
    private final TicketPushes ticketPushes;

    /**
     * @param changed an item on it was taken off since it was sent, and nobody in the area has said OK yet.
     */
    public record Ticket(UUID id, UUID billId, String kind, String label, OffsetDateTime createdAt, String createdByName,
            OffsetDateTime readyAt, OffsetDateTime servedAt, String servedByName, boolean changed, List<TicketLine> lines) {}

    /**
     * One item on a ticket.
     *
     * @param withKey the meal a side goes with: on this ticket ({@code lineKey} of another line), or sent earlier, when
     *     {@code withName} says which.
     * @param takenOff NOT_MADE ("Don't send") or SENT_BACK once taken off the bill.
     */
    public record TicketLine(UUID movementId, UUID lineKey, UUID withKey, String name, BigDecimal quantity, String unit,
            String note, String takenOff, OffsetDateTime takenOffAt, String takenOffByName, String withName) {}

    /** An area someone can keep tickets for, and how many are waiting there. */
    public record TicketArea(UUID id, String name, long waiting) {}

    /** Where one area's part of a send has got to, for the bill and the table (V103). */
    public record SendStatus(UUID ticketId, UUID areaId, String areaName, OffsetDateTime sentAt, OffsetDateTime readyAt,
            OffsetDateTime servedAt, String servedByName, List<UUID> movementIds) {}

    /** A table's mark on Tables: the areas with something ready to take, and whether everything sent was served. */
    public record TableStatus(List<String> ready, boolean allServed) {}

    // ---------------------------------------------------------------------------------------- made by bills

    /**
     * A line sent to a bill joins its Send's ticket for the area it came from; the first line of each makes it. Only
     * at a branch with tickets switched on.
     */
    /** @return the ticket, when this line made it (its first line); null otherwise. */
    static UUID addToTicket(NamedParameterJdbcTemplate jdbc, UUID clientId, UUID billId, UUID movementId, UUID sendKey,
            UUID userId) {
        UUID areaId = jdbc.queryForList("SELECT area_id FROM stock_movements WHERE id = :id",
                new MapSqlParameterSource("id", movementId), UUID.class).stream().findFirst().orElse(null);
        if (areaId == null) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("billId", billId)
                .addValue("areaId", areaId).addValue("sendKey", sendKey).addValue("userId", userId)
                .addValue("movementId", movementId);
        List<UUID> made = jdbc.queryForList("""
                INSERT INTO tickets (client_id, branch_id, area_id, bill_id, send_key, kind, created_by)
                SELECT :clientId, b.branch_id, :areaId, b.id, :sendKey,
                       CASE WHEN EXISTS (SELECT 1 FROM tickets t WHERE t.bill_id = b.id AND t.send_key <> :sendKey)
                            THEN 'ADDED' ELSE 'NEW' END,
                       :userId
                FROM bills b JOIN branches br ON br.id = b.branch_id AND br.tickets_on
                WHERE b.id = :billId AND b.client_id = :clientId
                ON CONFLICT (bill_id, area_id, send_key) DO NOTHING
                RETURNING id
                """, params, UUID.class);
        jdbc.update("""
                INSERT INTO ticket_lines (ticket_id, movement_id)
                SELECT id, :movementId FROM tickets WHERE bill_id = :billId AND area_id = :areaId AND send_key = :sendKey
                ON CONFLICT DO NOTHING
                """, params);
        return made.isEmpty() ? null : made.getFirst();
    }

    /**
     * A counter sale's line onto its area's ticket for that sale (V109): one ticket per area per sale, NEW. Only where
     * the branch has tickets on. Returns the ticket when it was made just now, for its announcement.
     */
    static UUID addCounterToTicket(NamedParameterJdbcTemplate jdbc, UUID clientId, UUID saleId, UUID branchId, UUID areaId,
            UUID movementId, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("saleId", saleId)
                .addValue("branchId", branchId).addValue("areaId", areaId).addValue("userId", userId).addValue("movementId", movementId);
        List<UUID> made = jdbc.queryForList("""
                INSERT INTO tickets (client_id, branch_id, area_id, sale_id, send_key, kind, created_by)
                SELECT :clientId, br.id, :areaId, :saleId, :saleId, 'NEW', :userId
                FROM branches br WHERE br.id = :branchId AND br.client_id = :clientId AND br.tickets_on
                ON CONFLICT (sale_id, area_id, send_key) WHERE bill_id IS NULL DO NOTHING
                RETURNING id
                """, params, UUID.class);
        jdbc.update("""
                INSERT INTO ticket_lines (ticket_id, movement_id)
                SELECT id, :movementId FROM tickets WHERE sale_id = :saleId AND bill_id IS NULL AND area_id = :areaId
                ON CONFLICT DO NOTHING
                """, params);
        return made.isEmpty() ? null : made.getFirst();
    }

    // ---------------------------------------------------------------------------------------- the Tickets page

    /**
     * The areas at a branch this person works in that tickets go to (any in the last 30 days; all of them before the
     * first), each with its tickets waiting. None where the branch has tickets off.
     */
    @Transactional(readOnly = true)
    public List<TicketArea> areas(UUID clientId, UUID userId, UUID branchId) {
        if (!branchAccessService.viewBranchIds(userId).contains(branchId)) {
            throw new AccessDeniedException("Not your branch.");
        }
        boolean on = Boolean.TRUE.equals(jdbc.queryForObject("SELECT tickets_on FROM branches WHERE id = :id",
                new MapSqlParameterSource("id", branchId), Boolean.class));
        List<UUID> mine = areaAccessService.workAreaIds(userId, branchId);
        if (!on || mine.isEmpty()) {
            return List.of();
        }
        record Row(TicketArea area, boolean used) {}
        List<Row> all = jdbc.query("""
                SELECT a.id, a.name,
                       (SELECT COUNT(*) FROM tickets t WHERE t.area_id = a.id AND t.kind <> 'DONT_MAKE'
                          AND t.created_at > now() - interval '1 day' AND (""" + WAITING + """
                       )) AS waiting,
                       EXISTS (SELECT 1 FROM tickets t WHERE t.area_id = a.id AND t.created_at > now() - interval '30 days') AS used
                FROM areas a
                WHERE a.client_id = :clientId AND a.id IN (:areas) AND a.sells
                ORDER BY a.sort_order, a.created_at
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("areas", mine),
                (rs, n) -> new Row(new TicketArea(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("waiting")),
                        rs.getBoolean("used")));
        // The areas tickets go to - the Kitchen and the Bar, not the front desk - once any have; before the first, all.
        List<TicketArea> used = all.stream().filter(Row::used).map(Row::area).toList();
        return used.isEmpty() ? all.stream().map(Row::area).toList() : used;
    }

    /** A line still to be made: on the bill, not taken off. */
    private static final String LIVE = """
            EXISTS (SELECT 1 FROM ticket_lines l JOIN ticketed_lines bl ON bl.movement_id = l.movement_id
                    WHERE l.ticket_id = t.id AND bl.taken_off IS NULL
                      AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = l.movement_id))""";

    /** An item taken off before it was made that nobody in the area has said OK to. */
    private static final String CHANGED = """
            EXISTS (SELECT 1 FROM ticket_lines l JOIN ticketed_lines bl ON bl.movement_id = l.movement_id
                    WHERE l.ticket_id = t.id AND l.cleared_at IS NULL
                      AND (bl.taken_off = 'NOT_MADE' OR (bl.taken_off IS NULL
                           AND EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = l.movement_id))))""";

    /** Waiting: something on it still to make, or a change to see. */
    private static final String WAITING = "(t.ready_at IS NULL AND " + LIVE + ") OR " + CHANGED;

    /**
     * An area's tickets: those waiting, oldest first (a ticket not ready in a day is let go), then the last few ready,
     * newest first.
     */
    @Transactional(readOnly = true)
    public List<Ticket> list(UUID clientId, UUID userId, UUID areaId) {
        requireArea(clientId, userId, areaId);
        Map<UUID, Ticket> tickets = new LinkedHashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("areaId", areaId);
        String select = """
                SELECT t.id, t.bill_id, t.kind, t.created_at, t.ready_at, t.served_at, bt.name AS table_name, b.name AS bill_name,
                       COALESCE(NULLIF(btrim(concat_ws(' ', u.first_name, u.last_name)), ''), u.username) AS by_name,
                       COALESCE(NULLIF(btrim(concat_ws(' ', s.first_name, s.last_name)), ''), s.username) AS served_name,
                       """ + CHANGED + """
                 AS changed
                FROM tickets t
                LEFT JOIN bills b ON b.id = t.bill_id
                LEFT JOIN branch_tables bt ON bt.id = b.table_id
                LEFT JOIN users u ON u.id = t.created_by
                LEFT JOIN users s ON s.id = t.served_by
                WHERE t.client_id = :clientId AND t.area_id = :areaId AND t.kind <> 'DONT_MAKE'
                  AND t.created_at > now() - interval '1 day'
                """;
        org.springframework.jdbc.core.RowCallbackHandler add = rs -> {
            String table = rs.getString("table_name");
            UUID id = rs.getObject("id", UUID.class);
            tickets.put(id, new Ticket(id, rs.getObject("bill_id", UUID.class), rs.getString("kind"),
                    label(table, rs.getString("bill_name")), rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("by_name"), rs.getObject("ready_at", OffsetDateTime.class),
                    rs.getObject("served_at", OffsetDateTime.class), rs.getString("served_name"), rs.getBoolean("changed"),
                    new ArrayList<>()));
        };
        jdbc.query(select + " AND (" + WAITING + ") ORDER BY t.created_at, t.id", params, add);
        jdbc.query(select + " AND NOT (" + WAITING + ") AND t.ready_at IS NOT NULL AND " + LIVE
                + " ORDER BY t.ready_at DESC LIMIT " + READY_SHOWN, params, add);
        if (tickets.isEmpty()) {
            return List.of();
        }
        jdbc.query("""
                SELECT tl.ticket_id, o.id AS movement_id, bl.line_key, bl.with_key, p.name,
                       COALESCE(o.entered_quantity, o.quantity) AS quantity, o.entered_unit, bl.note,
                       CASE WHEN bl.taken_off IS NULL AND r.id IS NOT NULL THEN 'NOT_MADE' ELSE bl.taken_off END AS taken_off,
                       COALESCE(bl.taken_off_at, r.created_at) AS taken_off_at,
                       COALESCE(NULLIF(btrim(concat_ws(' ', tu.first_name, tu.last_name)), ''), tu.username) AS taken_off_name,
                       (SELECT wp.name FROM ticketed_lines w
                          JOIN stock_movements wm ON wm.id = w.movement_id
                          JOIN products wp ON wp.id = wm.product_id
                        WHERE w.owner_id = bl.owner_id AND w.line_key = bl.with_key LIMIT 1) AS with_name
                FROM ticket_lines tl
                JOIN stock_movements o ON o.id = tl.movement_id
                JOIN ticketed_lines bl ON bl.movement_id = o.id
                JOIN products p ON p.id = o.product_id
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN users tu ON tu.id = bl.taken_off_by
                WHERE tl.ticket_id IN (:ids)
                  -- A "Don't send" seen and OK'd leaves the ticket.
                  AND NOT (tl.cleared_at IS NOT NULL AND (bl.taken_off = 'NOT_MADE' OR r.id IS NOT NULL))
                ORDER BY bl.sent_at, o.created_at, o.id
                """, new MapSqlParameterSource("ids", tickets.keySet()), rs -> {
                    tickets.get(rs.getObject("ticket_id", UUID.class)).lines().add(new TicketLine(
                            rs.getObject("movement_id", UUID.class), rs.getObject("line_key", UUID.class),
                            rs.getObject("with_key", UUID.class), rs.getString("name"), rs.getBigDecimal("quantity"),
                            rs.getString("entered_unit"), rs.getString("note"), rs.getString("taken_off"),
                            rs.getObject("taken_off_at", OffsetDateTime.class), rs.getString("taken_off_name"),
                            rs.getString("with_name")));
                });
        return List.copyOf(tickets.values());
    }

    /** Ready, or not ready after all (a mistaken tap, which also undoes Served). */
    @Transactional
    public List<Ticket> ready(UUID clientId, UUID userId, UUID ticketId, boolean ready) {
        UUID areaId = areaOf(clientId, ticketId);
        requireArea(clientId, userId, areaId);
        int changed = jdbc.update(ready
                ? "UPDATE tickets SET ready_at = now(), ready_by = :userId WHERE id = :id AND ready_at IS NULL"
                : "UPDATE tickets SET ready_at = NULL, ready_by = NULL, served_at = NULL, served_by = NULL WHERE id = :id",
                new MapSqlParameterSource().addValue("id", ticketId).addValue("userId", userId));
        // The waiter who sent it is told (V104).
        if (ready && changed > 0) {
            ticketPushes.ready(ticketId);
        }
        return list(clientId, userId, areaId);
    }

    /** OK to a "Don't send": the items taken off leave the ticket. */
    @Transactional
    public List<Ticket> seen(UUID clientId, UUID userId, UUID ticketId) {
        UUID areaId = areaOf(clientId, ticketId);
        requireArea(clientId, userId, areaId);
        jdbc.update("""
                UPDATE ticket_lines l SET cleared_at = now()
                FROM ticketed_lines bl
                WHERE l.ticket_id = :id AND bl.movement_id = l.movement_id AND l.cleared_at IS NULL
                  AND (bl.taken_off IS NOT NULL OR EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = l.movement_id))
                """, new MapSqlParameterSource("id", ticketId));
        return list(clientId, userId, areaId);
    }

    /**
     * Served (V103): a waiter took this area's part of a send to the table - from the bill, by anyone who can sell at
     * the branch. Served counts as Ready, so the area's screen lets it go. Not served undoes it, and the Ready it gave.
     */
    @Transactional
    public void served(UUID clientId, UUID userId, UUID ticketId, boolean served) {
        UUID branchId = jdbc.queryForList("SELECT branch_id FROM tickets WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", ticketId).addValue("clientId", clientId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new MadeWithException("That ticket isn't there any more."));
        if (!branchAccessService.writeBranchIds(userId).contains(branchId)) {
            throw new AccessDeniedException("Not your branch.");
        }
        jdbc.update(served
                ? """
                  UPDATE tickets SET served_at = now(), served_by = :userId,
                         ready_by = CASE WHEN ready_at IS NULL THEN :userId ELSE ready_by END,
                         ready_at = COALESCE(ready_at, now())
                  WHERE id = :id AND served_at IS NULL
                  """
                : """
                  UPDATE tickets SET
                         ready_at = CASE WHEN ready_at = served_at THEN NULL ELSE ready_at END,
                         ready_by = CASE WHEN ready_at = served_at THEN NULL ELSE ready_by END,
                         served_at = NULL, served_by = NULL
                  WHERE id = :id
                  """,
                new MapSqlParameterSource().addValue("id", ticketId).addValue("userId", userId));
    }

    // ---------------------------------------------------------------------------------------- for bills and Tables

    /** Each area's part of each send on a bill, with where it's got to; empty where tickets are off. */
    @Transactional(readOnly = true)
    public List<SendStatus> sends(UUID billId) {
        Map<UUID, SendStatus> found = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, t.area_id, a.name AS area_name, t.created_at, t.ready_at, t.served_at, l.movement_id,
                       COALESCE(NULLIF(btrim(concat_ws(' ', s.first_name, s.last_name)), ''), s.username) AS served_name
                FROM tickets t
                JOIN areas a ON a.id = t.area_id
                JOIN ticket_lines l ON l.ticket_id = t.id
                LEFT JOIN users s ON s.id = t.served_by
                WHERE t.bill_id = :billId AND t.kind <> 'DONT_MAKE'
                ORDER BY t.created_at, t.id
                """, new MapSqlParameterSource("billId", billId), rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    found.computeIfAbsent(id, key -> {
                        try {
                            return new SendStatus(key, rs.getObject("area_id", UUID.class), rs.getString("area_name"),
                                    rs.getObject("created_at", OffsetDateTime.class), rs.getObject("ready_at", OffsetDateTime.class),
                                    rs.getObject("served_at", OffsetDateTime.class), rs.getString("served_name"), new ArrayList<>());
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    }).movementIds().add(rs.getObject("movement_id", UUID.class));
                });
        return List.copyOf(found.values());
    }

    /**
     * The open bills' marks on Tables: the areas with something ready to take, and whether everything sent (and not
     * taken off) has been served. Bills with no tickets have no mark.
     */
    @Transactional(readOnly = true)
    public Map<UUID, TableStatus> tableStatus(Collection<UUID> billIds) {
        if (billIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> ready = new HashMap<>();
        Map<UUID, boolean[]> served = new HashMap<>();
        jdbc.query("""
                SELECT t.bill_id, a.name AS area_name, t.ready_at, t.served_at
                FROM tickets t JOIN areas a ON a.id = t.area_id
                WHERE t.bill_id IN (:ids) AND t.kind <> 'DONT_MAKE'
                """ + " AND " + LIVE + " ORDER BY t.created_at", new MapSqlParameterSource("ids", billIds), rs -> {
                    UUID bill = rs.getObject("bill_id", UUID.class);
                    boolean isServed = rs.getObject("served_at") != null;
                    served.computeIfAbsent(bill, key -> new boolean[] {true})[0] &= isServed;
                    if (!isServed && rs.getObject("ready_at") != null) {
                        List<String> areas = ready.computeIfAbsent(bill, key -> new ArrayList<>());
                        if (!areas.contains(rs.getString("area_name"))) areas.add(rs.getString("area_name"));
                    }
                });
        Map<UUID, TableStatus> status = new HashMap<>();
        served.forEach((bill, all) -> status.put(bill, new TableStatus(ready.getOrDefault(bill, List.of()), all[0])));
        return status;
    }

    private UUID areaOf(UUID clientId, UUID ticketId) {
        return jdbc.queryForList("SELECT area_id FROM tickets WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", ticketId).addValue("clientId", clientId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new MadeWithException("That ticket isn't there any more."));
    }

    private static String label(String table, String name) {
        // A counter sale's ticket has no bill (V109).
        return table == null ? (name == null ? "Counter" : name) : table.matches("\\d+") ? "Table " + table : table;
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
