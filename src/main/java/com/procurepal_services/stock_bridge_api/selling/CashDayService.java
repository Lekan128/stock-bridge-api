package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.email.EmailDispatcher;
import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.email.EmailRecipients;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Close the day (V97, selling till Step 13; owner 2026-11-18). Cash only. A day belongs to a drawer - the cash at one
 * area of a branch: started with the cash counted into it, money taken out with a reason, closed with a blind count.
 * The cashier sees what they counted; over or short is for whoever can see sales profit, and goes to the owner in one
 * email 15 minutes after the close, so a recount ("Count again") goes in it. A cash sale after a close reopens the
 * drawer from what was counted. All of it only while the company has "Count cash at open and close" on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashDayService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    /** How long after a close the owner's email waits - and "Count again" stays open. */
    static final java.time.Duration EMAIL_AFTER = java.time.Duration.ofMinutes(15);

    private final NamedParameterJdbcTemplate jdbc;
    private final StockWritePermissionGuard permissions;
    private final EmailDispatcher emailDispatcher;
    private final EmailRecipients emailRecipients;

    public enum OutKind { COST, BANK, OWNER, OTHER }

    /** An open (or just-closed) drawer as the till shows it. */
    public record Drawer(
            UUID id, UUID branchId, UUID areaId, String areaName, OffsetDateTime openedAt, String openedByName,
            boolean closed, OffsetDateTime closedAt, BigDecimal counted, int salesCount, boolean mayCountAgain) {}

    /** What the till needs: is the switch on, the drawer's open day if any, and whether it closed earlier today. */
    public record TillState(boolean countsCash, Drawer open, Drawer closedToday) {}

    /** The close, for the person closing; the owner's figures only for someone who sees sales profit. */
    public record CloseResult(
            Drawer drawer, BigDecimal salesAmount, int salesCount,
            BigDecimal floatAmount, boolean floatSkipped, BigDecimal cashSales, BigDecimal moneyOut, BigDecimal expected,
            BigDecimal difference, List<Map<String, Object>> otherPayments) {}

    public boolean countsCash(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT counts_cash FROM clients WHERE id = :id",
                new MapSqlParameterSource("id", clientId), Boolean.class));
    }

    @Transactional(readOnly = true)
    public TillState till(UUID clientId, UUID branchId, UUID areaId, UUID userId) {
        if (!countsCash(clientId)) {
            return new TillState(false, null, null);
        }
        Drawer open = drawers(clientId, branchId, "d.closed_at IS NULL AND d.area_id IS NOT DISTINCT FROM CAST(:areaId AS uuid)", areaId, userId)
                .stream().findFirst().orElse(null);
        Drawer closed = open != null ? null : drawers(clientId, branchId,
                "d.closed_at >= :reopenSince AND d.area_id IS NOT DISTINCT FROM CAST(:areaId AS uuid)", areaId, userId)
                .stream().findFirst().orElse(null);
        return new TillState(true, open, closed);
    }

    /** Every drawer at the branch open now, and those closed today - for "Which drawer are you counting?". */
    @Transactional(readOnly = true)
    public List<Drawer> today(UUID clientId, UUID branchId, UUID userId) {
        requireOn(clientId);
        return drawers(clientId, branchId, "(d.closed_at IS NULL OR d.closed_at >= LEAST(:today, :reopenSince))", null, userId);
    }

    @Transactional
    public Drawer start(UUID clientId, UUID branchId, UUID areaId, BigDecimal floatAmount, boolean skipped, UUID userId) {
        requireOn(clientId);
        requireBranch(clientId, branchId, areaId);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO cash_days (id, client_id, branch_id, area_id, opened_by, opened_at, float_amount, float_skipped)
                    VALUES (:id, :clientId, :branchId, :areaId, :userId, now(), :float, :skipped)
                    """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId).addValue("branchId", branchId)
                    .addValue("areaId", areaId).addValue("userId", userId)
                    .addValue("float", skipped || floatAmount == null ? BigDecimal.ZERO : floatAmount).addValue("skipped", skipped || floatAmount == null));
        } catch (org.springframework.dao.DuplicateKeyException alreadyOpen) {
            return till(clientId, branchId, areaId, userId).open();
        }
        return till(clientId, branchId, areaId, userId).open();
    }

    /**
     * A cash sale at a drawer whose day closed earlier today and isn't open: the drawer reopens from what was counted
     * (owner, 2026-11-18: "a sale after they closed"). Nothing when the switch is off or the drawer never opened today.
     */
    @Transactional
    public void onCashSale(UUID clientId, UUID branchId, UUID areaId, UUID userId) {
        if (branchId == null || !countsCash(clientId)) {
            return;
        }
        // A branch with one place has one drawer, kept without an area; the sale names that one place.
        Integer places = jdbc.queryForObject("SELECT COUNT(*) FROM areas WHERE branch_id = :branchId AND is_active",
                new MapSqlParameterSource("branchId", branchId), Integer.class);
        if (places == null || places <= 1) {
            areaId = null;
        }
        MapSqlParameterSource params = params(clientId, branchId, areaId, userId);
        Boolean open = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM cash_days d WHERE d.client_id = :clientId AND d.branch_id = :branchId
                               AND d.area_id IS NOT DISTINCT FROM CAST(:areaId AS uuid) AND d.closed_at IS NULL)
                """, params, Boolean.class);
        if (Boolean.TRUE.equals(open)) {
            return;
        }
        List<Map<String, Object>> last = jdbc.queryForList("""
                SELECT id, counted FROM cash_days d WHERE d.client_id = :clientId AND d.branch_id = :branchId
                  AND d.area_id IS NOT DISTINCT FROM CAST(:areaId AS uuid) AND d.closed_at >= :reopenSince
                ORDER BY d.closed_at DESC LIMIT 1
                """, params);
        if (last.isEmpty()) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO cash_days (id, client_id, branch_id, area_id, opened_by, opened_at, float_amount, reopened_from)
                    VALUES (:id, :clientId, :branchId, :areaId, :userId, now(), :float, :from)
                    """, params.addValue("id", UUID.randomUUID())
                    .addValue("float", last.get(0).get("counted") == null ? BigDecimal.ZERO : last.get(0).get("counted"))
                    .addValue("from", last.get(0).get("id")));
        } catch (org.springframework.dao.DuplicateKeyException alreadyOpen) {
            // Another phone reopened it first.
        }
    }

    @Transactional
    public void moneyOut(UUID clientId, UUID dayId, BigDecimal amount, OutKind kind, String note, UUID userId) {
        Map<String, Object> day = openDay(clientId, dayId);
        if (amount == null || amount.signum() <= 0) {
            throw new MadeWithException("Say how much was taken out.");
        }
        if (kind == null) {
            throw new MadeWithException("Say what it was for.");
        }
        if ((kind == OutKind.COST || kind == OutKind.OTHER) && (note == null || note.isBlank())) {
            throw new MadeWithException(kind == OutKind.COST ? "Say what the cost was, like diesel." : "Say what it was for.");
        }
        jdbc.update("""
                INSERT INTO cash_money_out (client_id, cash_day_id, amount, kind, note, created_by)
                VALUES (:clientId, :dayId, :amount, :kind, :note, :userId)
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("dayId", day.get("id"))
                .addValue("amount", amount).addValue("kind", kind.name())
                .addValue("note", note == null || note.isBlank() ? null : note.trim()).addValue("userId", userId));
    }

    /** Closes the drawer with what was counted; worked out against its sales only now. */
    @Transactional
    public CloseResult close(UUID clientId, UUID dayId, BigDecimal counted, UUID userId) {
        Map<String, Object> day = openDay(clientId, dayId);
        if (counted == null || counted.signum() < 0) {
            throw new MadeWithException("Type the cash you counted in the drawer.");
        }
        Figures figures = figures(clientId, day, OffsetDateTime.now());
        jdbc.update("""
                UPDATE cash_days SET closed_at = now(), closed_by = :userId, counted = :counted,
                    cash_sales = :cashSales, money_out = :moneyOut, expected = :expected
                WHERE id = :id AND closed_at IS NULL
                """, new MapSqlParameterSource().addValue("id", dayId).addValue("userId", userId).addValue("counted", counted)
                .addValue("cashSales", figures.cashSales()).addValue("moneyOut", figures.moneyOut()).addValue("expected", figures.expected()));
        return result(clientId, dayId, userId);
    }

    /** Money taken out of a past drawer, for {@link #recordDay}. */
    public record PastMoneyOut(BigDecimal amount, OutKind kind, String note, OffsetDateTime at) {}

    /**
     * Sample data (the demo's reset): a drawer's day as it went, worked out exactly as a close works it out - its
     * cash sales between {@code openedAt} and {@code closedAt}, less the money taken out - and counted
     * {@code countedOff} away from what was expected (negative: short). Its owner's email counts as sent: a reset
     * must never email anybody. {@code closedAt} null leaves today's drawer open.
     */
    @Transactional
    public UUID recordDay(UUID clientId, UUID branchId, UUID areaId, OffsetDateTime openedAt, BigDecimal floatAmount,
            List<PastMoneyOut> moneyOut, OffsetDateTime closedAt, BigDecimal countedOff, UUID openedBy, UUID closedBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cash_days (id, client_id, branch_id, area_id, opened_by, opened_at, float_amount, float_skipped)
                VALUES (:id, :clientId, :branchId, :areaId, :userId, :openedAt, :float, FALSE)
                """, new MapSqlParameterSource().addValue("id", id).addValue("clientId", clientId).addValue("branchId", branchId)
                .addValue("areaId", areaId).addValue("userId", openedBy).addValue("openedAt", openedAt).addValue("float", floatAmount));
        for (PastMoneyOut out : moneyOut) {
            jdbc.update("""
                    INSERT INTO cash_money_out (client_id, cash_day_id, amount, kind, note, created_by, created_at)
                    VALUES (:clientId, :dayId, :amount, :kind, :note, :userId, :at)
                    """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("dayId", id)
                    .addValue("amount", out.amount()).addValue("kind", out.kind().name()).addValue("note", out.note())
                    .addValue("userId", openedBy).addValue("at", out.at()));
        }
        if (closedAt != null) {
            Map<String, Object> day = jdbc.queryForList("SELECT * FROM cash_days WHERE id = :id", new MapSqlParameterSource("id", id))
                    .getFirst();
            Figures figures = figures(clientId, day, closedAt);
            jdbc.update("""
                    UPDATE cash_days SET closed_at = :closedAt, closed_by = :userId, counted = :counted, cash_sales = :cashSales,
                        money_out = :moneyOut, expected = :expected, email_sent_at = :closedAt
                    WHERE id = :id
                    """, new MapSqlParameterSource().addValue("id", id).addValue("closedAt", closedAt).addValue("userId", closedBy)
                    .addValue("counted", figures.expected().add(countedOff == null ? BigDecimal.ZERO : countedOff).max(BigDecimal.ZERO))
                    .addValue("cashSales", figures.cashSales()).addValue("moneyOut", figures.moneyOut())
                    .addValue("expected", figures.expected()));
        }
        return id;
    }

    /** "Count again": the same person, until the owner's email goes and no sale has reopened the drawer. */
    @Transactional
    public CloseResult countAgain(UUID clientId, UUID dayId, BigDecimal counted, UUID userId) {
        Map<String, Object> day = jdbc.queryForList("SELECT * FROM cash_days WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", dayId).addValue("clientId", clientId)).stream().findFirst()
                .orElseThrow(() -> new MadeWithException("No such drawer."));
        if (!mayCountAgain(day, userId)) {
            throw new MadeWithException("It's too late to count this drawer again. Ask a manager to put it right.");
        }
        if (counted == null || counted.signum() < 0) {
            throw new MadeWithException("Type the cash you counted in the drawer.");
        }
        jdbc.update("UPDATE cash_days SET counted = :counted, recounted_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("id", dayId).addValue("counted", counted));
        return result(clientId, dayId, userId);
    }

    /** Drawers left open from before today, for the dashboard's one line. */
    @Transactional(readOnly = true)
    public List<Drawer> leftOpen(UUID clientId, List<UUID> branchIds, UUID userId) {
        if (!countsCash(clientId) || branchIds.isEmpty()) {
            return List.of();
        }
        List<Drawer> result = new ArrayList<>();
        for (UUID branchId : branchIds) {
            result.addAll(drawers(clientId, branchId, "d.closed_at IS NULL AND d.opened_at < :today", null, userId));
        }
        return result;
    }

    /** The close as the person who asked sees it: what they counted; the owner's figures only for analytics. */
    @Transactional(readOnly = true)
    public CloseResult result(UUID clientId, UUID dayId, UUID userId) {
        Map<String, Object> day = jdbc.queryForList("SELECT * FROM cash_days WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", dayId).addValue("clientId", clientId)).stream().findFirst()
                .orElseThrow(() -> new MadeWithException("No such drawer."));
        OffsetDateTime until = day.get("closed_at") == null ? OffsetDateTime.now() : toTime(day.get("closed_at"));
        Figures figures = figures(clientId, day, until);
        Drawer drawer = drawers(clientId, (UUID) day.get("branch_id"), "d.id = CAST(:dayId AS uuid)", null, userId,
                new MapSqlParameterSource("dayId", dayId)).stream().findFirst().orElse(null);
        boolean sees = permissions.has(userId, "VIEW_ANALYTICS");
        BigDecimal counted = (BigDecimal) day.get("counted");
        return new CloseResult(drawer, figures.salesAmount(), figures.salesCount(),
                sees ? (BigDecimal) day.get("float_amount") : null,
                sees && Boolean.TRUE.equals(day.get("float_skipped")),
                sees ? figures.cashSales() : null,
                sees ? figures.moneyOut() : null,
                sees ? figures.expected() : null,
                sees && counted != null ? counted.subtract(figures.expected()) : null,
                sees ? figures.otherPayments() : null);
    }

    // ---------------------------------------------------------------- the owner's email

    /** Closes 15 minutes old: one email each to the owner, so a recount goes in it. */
    @Scheduled(fixedDelayString = "${app.cash-days.email-sweep:PT1M}", initialDelayString = "${app.cash-days.email-sweep:PT1M}")
    public void sendCloseEmails() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT d.*, a.name AS area_name, b.name AS branch_name,
                       COALESCE(NULLIF(TRIM(CONCAT(u.first_name, ' ', u.last_name)), ''), u.username) AS closed_by_name
                FROM cash_days d JOIN branches b ON b.id = d.branch_id
                LEFT JOIN areas a ON a.id = d.area_id LEFT JOIN users u ON u.id = d.closed_by
                WHERE d.closed_at IS NOT NULL AND d.email_sent_at IS NULL
                  AND d.closed_at < :due AND d.closed_at > :since
                ORDER BY d.closed_at LIMIT 50
                """, new MapSqlParameterSource().addValue("due", OffsetDateTime.now().minus(EMAIL_AFTER))
                .addValue("since", OffsetDateTime.now().minusDays(2)));
        for (Map<String, Object> day : due) {
            UUID clientId = (UUID) day.get("client_id");
            try {
                List<String> to = emailRecipients.forClient(clientId);
                if (!to.isEmpty()) {
                    emailDispatcher.dispatch(closeEmail(to, day, figures(clientId, day, toTime(day.get("closed_at")))));
                }
            } catch (RuntimeException e) {
                log.warn("Close-the-day email for {} not sent: {}", day.get("id"), e.getMessage());
            }
            jdbc.update("UPDATE cash_days SET email_sent_at = now() WHERE id = :id", new MapSqlParameterSource("id", day.get("id")));
        }
    }

    private EmailMessage closeEmail(List<String> to, Map<String, Object> day, Figures figures) {
        String place = day.get("area_name") != null ? (String) day.get("area_name") : (String) day.get("branch_name");
        BigDecimal counted = (BigDecimal) day.get("counted");
        BigDecimal difference = counted.subtract(figures.expected());
        String verdict = difference.signum() == 0 ? "cash matched"
                : difference.signum() < 0 ? "cash " + naira(difference.negate()) + " short" : "cash " + naira(difference) + " over";
        String who = day.get("closed_by_name") == null ? "Someone" : (String) day.get("closed_by_name");
        StringBuilder text = new StringBuilder();
        text.append(who).append(" closed the ").append(place).append(" drawer").append(" at ")
                .append(toTime(day.get("closed_at")).atZoneSameInstant(LAGOS).toLocalTime().withSecond(0).withNano(0)).append(".\n\n");
        text.append("Cash should be ").append(naira(figures.expected())).append(": ")
                .append(Boolean.TRUE.equals(day.get("float_skipped")) ? "no starting cash" : naira((BigDecimal) day.get("float_amount")) + " to start")
                .append(" + ").append(naira(figures.cashSales())).append(" cash sales")
                .append(figures.moneyOut().signum() > 0 ? " - " + naira(figures.moneyOut()) + " taken out" : "").append(".\n");
        text.append("Counted: ").append(naira(counted)).append(day.get("recounted_at") != null ? " (counted again)" : "").append(".\n\n");
        text.append("Sales: ").append(figures.salesCount()).append(" · ").append(naira(figures.salesAmount())).append(".\n");
        for (Map<String, Object> other : figures.otherPayments()) {
            text.append(other.get("label")).append(": ").append(naira((BigDecimal) other.get("amount"))).append("\n");
        }
        for (Map<String, Object> out : figures.outs()) {
            text.append("Taken out: ").append(naira((BigDecimal) out.get("amount"))).append(" · ").append(out.get("what")).append("\n");
        }
        String subject = who + " closed the " + place + " · " + verdict;
        String html = "<div style=\"font-family:sans-serif;font-size:14px;line-height:1.5\">"
                + escape(text.toString()).replace("\n", "<br>") + "</div>";
        return new EmailMessage(to, subject, html, text.toString(), EmailKind.TRANSACTIONAL);
    }

    // ---------------------------------------------------------------- figures

    private record Figures(
            BigDecimal cashSales, BigDecimal moneyOut, BigDecimal expected, BigDecimal salesAmount, int salesCount,
            List<Map<String, Object>> otherPayments, List<Map<String, Object>> outs) {}

    private Figures figures(UUID clientId, Map<String, Object> day, OffsetDateTime until) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId)
                .addValue("branchId", day.get("branch_id")).addValue("areaId", day.get("area_id"))
                .addValue("from", toTime(day.get("opened_at"))).addValue("until", until).addValue("dayId", day.get("id"));
        // The drawer's sale lines in its time: this branch, this area (a one-place branch: any), not cancelled. A table's
        // bill (V99) counts where and when it was paid, not where its items were made: its payments, not its lines.
        String lines = """
                SELECT o.sale_id, COALESCE(o.sale_amount, c.sale_amount, 0) AS amount, s.payment_method, s.payment_account_id
                FROM stock_movements o
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                LEFT JOIN sales s ON s.id = o.sale_id AND s.client_id = o.client_id
                WHERE o.client_id = :clientId AND o.branch_id = :branchId AND o.movement_type = 'OUT' AND o.sale_id IS NOT NULL
                  AND r.id IS NULL
                  AND (CAST(:areaId AS uuid) IS NULL OR o.area_id = :areaId)
                  AND o.created_at >= :from AND o.created_at < :until
                  AND NOT EXISTS (SELECT 1 FROM bills b WHERE b.id = o.sale_id)
                UNION ALL
                -- Step 10 (V100): charges added on top of those counter sales came in with them.
                SELECT sc.sale_id, sc.amount, s.payment_method, s.payment_account_id
                FROM sale_charges sc
                JOIN sales s ON s.id = sc.sale_id
                WHERE sc.client_id = :clientId AND NOT sc.included AND sc.amount > 0
                  AND NOT EXISTS (SELECT 1 FROM bills b WHERE b.id = sc.sale_id)
                  AND EXISTS (SELECT 1 FROM stock_movements o
                              LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                              WHERE o.sale_id = sc.sale_id AND o.branch_id = :branchId AND o.movement_type = 'OUT' AND r.id IS NULL
                                AND (CAST(:areaId AS uuid) IS NULL OR o.area_id = :areaId)
                                AND o.created_at >= :from AND o.created_at < :until)
                UNION ALL
                SELECT bp.bill_id, bp.amount, bp.method, bp.payment_account_id
                FROM bill_payments bp
                WHERE bp.client_id = :clientId AND bp.branch_id = :branchId
                  AND (CAST(:areaId AS uuid) IS NULL OR bp.area_id = :areaId)
                  AND bp.paid_at >= :from AND bp.paid_at < :until
                """;
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(amount) FILTER (WHERE payment_method = 'CASH'), 0) AS cash,
                       COALESCE(SUM(amount), 0) AS total, COUNT(DISTINCT sale_id) AS sales
                FROM (""" + lines + ") l", params);
        List<Map<String, Object>> others = jdbc.queryForList("""
                SELECT CASE WHEN l.payment_method = 'CARD' THEN 'Card'
                            WHEN pa.bank_name IS NOT NULL THEN 'Transfer · ' || pa.bank_name ELSE 'Transfer' END AS label,
                       SUM(l.amount) AS amount
                FROM (""" + lines + """
                ) l LEFT JOIN payment_accounts pa ON pa.id = l.payment_account_id
                WHERE l.payment_method IN ('TRANSFER', 'CARD')
                GROUP BY 1 ORDER BY 2 DESC
                """, params);
        List<Map<String, Object>> outs = jdbc.queryForList("""
                SELECT amount, CASE kind WHEN 'BANK' THEN 'to the bank' WHEN 'OWNER' THEN 'to the owner'
                                         ELSE COALESCE(note, lower(kind)) END AS what
                FROM cash_money_out WHERE cash_day_id = :dayId ORDER BY created_at
                """, params);
        BigDecimal cash = (BigDecimal) totals.get("cash");
        BigDecimal out = outs.stream().map(row -> (BigDecimal) row.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal start = (BigDecimal) day.get("float_amount");
        return new Figures(cash, out, start.add(cash).subtract(out), (BigDecimal) totals.get("total"),
                ((Number) totals.get("sales")).intValue(), others, outs);
    }

    // ---------------------------------------------------------------- reading drawers

    private List<Drawer> drawers(UUID clientId, UUID branchId, String where, UUID areaId, UUID userId) {
        return drawers(clientId, branchId, where, areaId, userId, new MapSqlParameterSource());
    }

    private List<Drawer> drawers(UUID clientId, UUID branchId, String where, UUID areaId, UUID userId, MapSqlParameterSource extra) {
        MapSqlParameterSource params = params(clientId, branchId, areaId, userId);
        for (String name : extra.getParameterNames()) {
            params.addValue(name, extra.getValue(name));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, a.name AS area_name,
                       COALESCE(NULLIF(TRIM(CONCAT(u.first_name, ' ', u.last_name)), ''), u.username) AS opened_by_name
                FROM cash_days d LEFT JOIN areas a ON a.id = d.area_id LEFT JOIN users u ON u.id = d.opened_by
                WHERE d.client_id = :clientId AND d.branch_id = :branchId""" + " AND " + where + """
                 ORDER BY d.closed_at IS NOT NULL, a.sort_order NULLS FIRST, a.name, d.opened_at DESC
                """, params);
        List<Drawer> result = new ArrayList<>();
        for (Map<String, Object> day : rows) {
            OffsetDateTime until = day.get("closed_at") == null ? OffsetDateTime.now() : toTime(day.get("closed_at"));
            Figures figures = figures(clientId, day, until);
            result.add(new Drawer((UUID) day.get("id"), (UUID) day.get("branch_id"), (UUID) day.get("area_id"),
                    (String) day.get("area_name"), toTime(day.get("opened_at")), (String) day.get("opened_by_name"),
                    day.get("closed_at") != null, day.get("closed_at") == null ? null : toTime(day.get("closed_at")),
                    (BigDecimal) day.get("counted"), figures.salesCount(), mayCountAgain(day, userId)));
        }
        return result;
    }

    private boolean mayCountAgain(Map<String, Object> day, UUID userId) {
        if (day.get("closed_at") == null || day.get("email_sent_at") != null || userId == null || !userId.equals(day.get("closed_by"))) {
            return false;
        }
        if (toTime(day.get("closed_at")).isBefore(OffsetDateTime.now().minus(EMAIL_AFTER))) {
            return false;
        }
        // A sale after the close reopened the drawer: that count stands.
        return !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cash_days WHERE reopened_from = :id)", new MapSqlParameterSource("id", day.get("id")), Boolean.class));
    }

    private Map<String, Object> openDay(UUID clientId, UUID dayId) {
        return jdbc.queryForList("SELECT * FROM cash_days WHERE id = :id AND client_id = :clientId AND closed_at IS NULL",
                new MapSqlParameterSource().addValue("id", dayId).addValue("clientId", clientId)).stream().findFirst()
                .orElseThrow(() -> new MadeWithException("This drawer is already closed."));
    }

    private void requireOn(UUID clientId) {
        if (!countsCash(clientId)) {
            throw new MadeWithException("Counting cash at open and close is off. The owner turns it on in Company settings.");
        }
    }

    private void requireBranch(UUID clientId, UUID branchId, UUID areaId) {
        Boolean ok = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM branches b WHERE b.id = :branchId AND b.client_id = :clientId AND b.is_active
                    AND (CAST(:areaId AS uuid) IS NULL OR EXISTS (SELECT 1 FROM areas a WHERE a.id = :areaId AND a.branch_id = b.id)))
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId).addValue("areaId", areaId),
                Boolean.class);
        if (!Boolean.TRUE.equals(ok)) {
            throw new MadeWithException("That drawer isn't at one of your branches.");
        }
    }

    /**
     * How long a closed drawer can be opened again - the owner's choice, 4 hours to start (V98; owner, 2026-11-18).
     * Within it, "Open it again" and a sale reopen it from what was counted; after it, the till asks to Start the day
     * with a fresh count. Hours, not the calendar day: a bar that closes at 2 am opens again at 6 pm as a new day.
     */
    private java.time.Duration reopenWithin(UUID clientId) {
        Integer hours = jdbc.queryForObject("SELECT cash_reopen_hours FROM clients WHERE id = :id",
                new MapSqlParameterSource("id", clientId), Integer.class);
        return java.time.Duration.ofHours(hours == null ? 4 : hours);
    }

    private MapSqlParameterSource params(UUID clientId, UUID branchId, UUID areaId, UUID userId) {
        return new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId)
                .addValue("areaId", areaId).addValue("userId", userId)
                .addValue("today", java.time.LocalDate.now(LAGOS).atStartOfDay(LAGOS).toOffsetDateTime())
                .addValue("reopenSince", OffsetDateTime.now().minus(reopenWithin(clientId)));
    }

    private static OffsetDateTime toTime(Object value) {
        if (value instanceof OffsetDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp stamp) {
            return stamp.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(java.time.ZoneOffset.UTC);
        }
        return OffsetDateTime.parse(value.toString());
    }

    private static String naira(BigDecimal amount) {
        return "₦" + String.format("%,.0f", amount);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
