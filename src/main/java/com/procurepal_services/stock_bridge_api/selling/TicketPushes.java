package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.AreaAccessService;
import com.procurepal_services.stock_bridge_api.push.PushMessage;
import com.procurepal_services.stock_bridge_api.push.PushPrefs;
import com.procurepal_services.stock_bridge_api.push.PushService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ticket notifications (V104; owner, 2026-11-19), on top of each area's Tickets screen, not instead of it. A new ticket
 * goes to the phones of people who work in its area; Ready goes to the waiter who sent it. Each person chooses their
 * own ({@link PushPrefs}). A Send's lines arrive one by one, so a new ticket is announced a moment after its first
 * line, with every line that has arrived by then.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketPushes {

    private static final long GATHER_SECONDS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final PushService pushService;
    private final PushPrefs pushPrefs;
    private final AreaAccessService areaAccessService;
    private final ScheduledExecutorService later = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ticket-pushes");
        thread.setDaemon(true);
        return thread;
    });

    /** A ticket was just made: tell its area once the Send has had a moment to arrive. */
    public void announceLater(UUID ticketId) {
        if (PushService.quiet()) {
            return;
        }
        Runnable announce = () -> later.schedule(() -> announce(ticketId), GATHER_SECONDS, TimeUnit.SECONDS);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    announce.run();
                }
            });
        } else {
            announce.run();
        }
    }

    /** The area said Ready: tell whoever sent it. */
    public void ready(UUID ticketId) {
        if (PushService.quiet()) {
            return;
        }
        Map<String, Object> ticket = ticket(ticketId);
        if (ticket == null) {
            return;
        }
        UUID sender = (UUID) ticket.get("created_by");
        if (sender == null) {
            return;
        }
        pushService.notify(pushPrefs.wanting(List.of(sender), PushPrefs.TICKETS_READY), new PushMessage(
                ticket.get("label") + " · " + ticket.get("area_name") + ": ready", items(ticketId),
                "ticket-ready-" + ticketId,
                ticket.get("bill_id") == null ? "/app/sell" : "/app/sell/bill?id=" + ticket.get("bill_id"), false));
    }

    private void announce(UUID ticketId) {
        try {
            Map<String, Object> ticket = ticket(ticketId);
            if (ticket == null || ticket.get("ready_at") != null) {
                return;
            }
            UUID branchId = (UUID) ticket.get("branch_id");
            UUID areaId = (UUID) ticket.get("area_id");
            List<UUID> people = jdbc.queryForList("""
                    SELECT DISTINCT user_id FROM push_subscriptions WHERE client_id = :clientId AND user_id <> :sender
                    """, new MapSqlParameterSource().addValue("clientId", ticket.get("client_id"))
                    .addValue("sender", ticket.get("created_by") == null ? new UUID(0, 0) : ticket.get("created_by")), UUID.class)
                    .stream().filter(user -> areaAccessService.workAreaIds(user, branchId).contains(areaId)).toList();
            long count = jdbc.queryForObject("SELECT COUNT(*) FROM ticket_lines WHERE ticket_id = :id",
                    new MapSqlParameterSource("id", ticketId), Long.class);
            String kind = "ADDED".equals(ticket.get("kind")) ? " · added" : "";
            pushService.notify(pushPrefs.wanting(people, PushPrefs.TICKETS_NEW), new PushMessage(
                    ticket.get("label") + " · " + ticket.get("area_name") + kind + " · " + count + (count == 1 ? " item" : " items"),
                    items(ticketId), "ticket-" + ticketId, "/app/tickets", false));
        } catch (RuntimeException e) {
            log.warn("Couldn't announce ticket {}: {}", ticketId, e.getMessage());
        }
    }

    private Map<String, Object> ticket(UUID ticketId) {
        return jdbc.queryForList("""
                SELECT t.client_id, t.branch_id, t.area_id, t.bill_id, t.kind, t.created_by, t.ready_at, a.name AS area_name,
                       CASE WHEN bt.name IS NULL THEN COALESCE(b.name, 'Counter') WHEN bt.name ~ '^[0-9]+$' THEN 'Table ' || bt.name ELSE bt.name END AS label
                FROM tickets t JOIN areas a ON a.id = t.area_id LEFT JOIN bills b ON b.id = t.bill_id
                LEFT JOIN branch_tables bt ON bt.id = b.table_id
                WHERE t.id = :id
                """, new MapSqlParameterSource("id", ticketId)).stream().findFirst().orElse(null);
    }

    /** "Fried rice & turkey + Plantain, 2 Chapman": what's on it, sides with their meal. */
    private String items(UUID ticketId) {
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT p.name, COALESCE(o.entered_quantity, o.quantity) AS quantity, bl.line_key, bl.with_key
                FROM ticket_lines tl JOIN stock_movements o ON o.id = tl.movement_id
                JOIN ticketed_lines bl ON bl.movement_id = o.id JOIN products p ON p.id = o.product_id
                WHERE tl.ticket_id = :id AND bl.taken_off IS NULL
                ORDER BY bl.sent_at, o.created_at
                """, new MapSqlParameterSource("id", ticketId));
        java.util.Set<Object> keys = new java.util.HashSet<>();
        lines.forEach(line -> keys.add(line.get("line_key")));
        StringBuilder text = new StringBuilder();
        for (Map<String, Object> line : lines) {
            if (line.get("with_key") != null && keys.contains(line.get("with_key"))) continue;
            if (!text.isEmpty()) text.append(", ");
            text.append(named(line));
            for (Map<String, Object> side : lines) {
                if (line.get("line_key").equals(side.get("with_key"))) text.append(" + ").append(named(side));
            }
        }
        return text.toString();
    }

    private static String named(Map<String, Object> line) {
        BigDecimal quantity = (BigDecimal) line.get("quantity");
        return quantity.compareTo(BigDecimal.ONE) == 0 ? (String) line.get("name")
                : quantity.stripTrailingZeros().toPlainString() + " " + line.get("name");
    }
}
