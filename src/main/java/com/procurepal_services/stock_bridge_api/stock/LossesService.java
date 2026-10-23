package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.stock.dto.LossesResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The losses report (Phase 3, MULTI_BRANCH_UX_PLAN.md section 5.7; Jendol's "shrinkage by staff"): every
 * way stock leaves the books other than being sold or used, by kind, branch, person or month, at cost.
 * Read from the ledger and the waybills, as the movements report is; nothing here writes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LossesService {

    public static final List<String> GROUPINGS = List.of("kind", "branch", "person", "month");
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    /** What each kind is called on screen and in a spreadsheet. */
    public static final Map<String, String> KIND_LABELS = Map.of(
            "MISSING_IN_TRANSIT", "Missing on the way",
            "DAMAGED_IN_TRANSIT", "Damaged on the way",
            "COUNTED_SHORT", "Counted short",
            "COUNTED_OVER", "Counted over",
            "DAMAGED", "Damaged",
            "EXPIRED", "Expired",
            "LOST", "Lost or stolen");

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param from / to the range; both absent is this month so far (Lagos).
     * @param branchIds the branches covered (the person's report scope).
     */
    public LossesResponse report(UUID clientId, OffsetDateTime from, OffsetDateTime to, String groupBy, Collection<UUID> branchIds) {
        String grouping = groupBy == null ? "kind" : groupBy.toLowerCase(Locale.ROOT);
        if (!GROUPINGS.contains(grouping)) {
            throw new IllegalArgumentException("groupBy must be one of " + GROUPINGS);
        }
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(LAGOS);
        OffsetDateTime start = from != null
                ? from
                : LocalDate.now(LAGOS).withDayOfMonth(1).atStartOfDay(LAGOS).toOffsetDateTime();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("from", start)
                .addValue("to", end)
                .addValue("branchIds", branchIds == null || branchIds.isEmpty() ? List.of(new UUID(0, 0)) : branchIds);

        String key = switch (grouping) {
            case "kind" -> "kind";
            case "branch" -> "branch_id::text";
            case "person" -> "person_key";
            default -> "to_char(occurred_at AT TIME ZONE 'Africa/Lagos', 'YYYY-MM')";
        };
        String label = switch (grouping) {
            case "kind" -> "kind";
            case "branch" -> "MAX(branch_name)";
            case "person" -> "MAX(person_label)";
            default -> "to_char(occurred_at AT TIME ZONE 'Africa/Lagos', 'YYYY-MM')";
        };
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Map<String, LossesResponse.Kind>> kinds = new LinkedHashMap<>();
        Map<String, Long> unknown = new LinkedHashMap<>();
        jdbc.query(LOSSES + "SELECT " + key + " AS group_key, " + label + " AS group_label, kind, "
                + "SUM(quantity) AS units, SUM(cost) AS cost, COUNT(*) AS entries, COUNT(*) FILTER (WHERE cost IS NULL) AS unknown "
                + "FROM losses GROUP BY " + key + ", kind ORDER BY 1", params, rs -> {
                    String group = rs.getString("group_key");
                    labels.putIfAbsent(group, labelOf(grouping, group, rs.getString("group_label")));
                    BigDecimal cost = rs.getBigDecimal("cost");
                    kinds.computeIfAbsent(group, k -> new TreeMap<>()).put(rs.getString("kind"), new LossesResponse.Kind(
                            rs.getLong("units"), money(cost), rs.getLong("entries")));
                    unknown.merge(group, rs.getLong("unknown"), Long::sum);
                });
        List<LossesResponse.Row> rows = new ArrayList<>();
        Map<String, LossesResponse.Kind> all = new TreeMap<>();
        long allUnknown = 0;
        for (Map.Entry<String, Map<String, LossesResponse.Kind>> group : kinds.entrySet()) {
            long groupUnknown = unknown.getOrDefault(group.getKey(), 0L);
            rows.add(new LossesResponse.Row(group.getKey(), labels.get(group.getKey()), figures(group.getValue(), groupUnknown)));
            allUnknown += groupUnknown;
            for (Map.Entry<String, LossesResponse.Kind> kind : group.getValue().entrySet()) {
                all.merge(kind.getKey(), kind.getValue(), (a, b) ->
                        new LossesResponse.Kind(a.units() + b.units(), a.cost().add(b.cost()), a.entries() + b.entries()));
            }
        }
        // Biggest loss first, except months, which read in order.
        if (!grouping.equals("month")) {
            rows.sort((a, b) -> b.figures().cost().compareTo(a.figures().cost()));
        }
        return new LossesResponse(start, end, grouping, rows, figures(all, allUnknown));
    }

    /** Net figures: a count over is a gain, so it is taken off the units and the cost. */
    private static LossesResponse.Figures figures(Map<String, LossesResponse.Kind> byKind, long costUnknown) {
        long units = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (Map.Entry<String, LossesResponse.Kind> kind : byKind.entrySet()) {
            int sign = kind.getKey().equals("COUNTED_OVER") ? -1 : 1;
            units += sign * kind.getValue().units();
            cost = cost.add(kind.getValue().cost().multiply(BigDecimal.valueOf(sign)));
        }
        return new LossesResponse.Figures(units, money(cost), costUnknown, byKind);
    }

    private static String labelOf(String grouping, String key, String label) {
        if (grouping.equals("kind")) return KIND_LABELS.getOrDefault(key, key);
        if (grouping.equals("person") && label == null) return "Nobody named";
        return label == null ? key : label;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Every loss in range at the branches in scope, one row each: kind, branch, person, when, units,
     * cost (null when unknown). Units are positive; a count over is a positive gain of kind COUNTED_OVER.
     */
    private static final String LOSSES = """
            WITH transit AS (
                SELECT t.to_branch_id AS branch_id, t.carried_by_name, COALESCE(t.received_at, t.sent_at) AS occurred_at,
                       l.quantity_missing, l.quantity_damaged,
                       COALESCE(
                           (SELECT SUM(a.quantity * i.unit_price_at_time) / NULLIF(SUM(a.quantity), 0)
                            FROM stock_movement_allocations a JOIN stock_movements i ON i.id = a.in_movement_id
                            WHERE a.out_movement_id = l.out_movement_id AND a.quantity > 0 AND i.unit_price_at_time IS NOT NULL),
                           p.cost_price) AS unit_cost
                FROM stock_transfers t
                JOIN stock_transfer_lines l ON l.transfer_id = t.id
                JOIN products p ON p.id = l.product_id
                WHERE t.client_id = :clientId AND t.to_branch_id IN (:branchIds)
                  AND (l.quantity_missing > 0 OR l.quantity_damaged > 0)
                  AND COALESCE(t.received_at, t.sent_at) >= :from AND COALESCE(t.received_at, t.sent_at) < :to
            ),
            entries AS (
                SELECT 'MISSING_IN_TRANSIT' AS kind, branch_id, 'driver:' || COALESCE(LOWER(TRIM(carried_by_name)), '') AS person_key,
                       COALESCE(NULLIF(TRIM(carried_by_name), ''), 'No driver named') || ' (carried)' AS person_label,
                       occurred_at, quantity_missing AS quantity, quantity_missing * unit_cost AS cost
                FROM transit WHERE quantity_missing > 0
                UNION ALL
                SELECT 'DAMAGED_IN_TRANSIT', branch_id, 'driver:' || COALESCE(LOWER(TRIM(carried_by_name)), ''),
                       COALESCE(NULLIF(TRIM(carried_by_name), ''), 'No driver named') || ' (carried)',
                       occurred_at, quantity_damaged, quantity_damaged * unit_cost
                FROM transit WHERE quantity_damaged > 0
                UNION ALL
                SELECT CASE WHEN m.quantity < 0 THEN 'COUNTED_SHORT' ELSE 'COUNTED_OVER' END, m.branch_id,
                       'user:' || COALESCE(m.created_by::text, ''),
                       COALESCE(NULLIF(TRIM(CONCAT(u.first_name, ' ', u.last_name)), ''), u.username),
                       m.occurred_at, ABS(m.quantity), ABS(m.quantity) * p.cost_price
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                LEFT JOIN users u ON u.id = m.created_by
                WHERE m.client_id = :clientId AND m.branch_id IN (:branchIds) AND m.is_count AND m.quantity <> 0
                  AND m.occurred_at >= :from AND m.occurred_at < :to
                  AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = m.id)
                UNION ALL
                SELECT m.out_reason, m.branch_id, 'user:' || COALESCE(m.created_by::text, ''),
                       COALESCE(NULLIF(TRIM(CONCAT(u.first_name, ' ', u.last_name)), ''), u.username),
                       m.occurred_at, m.quantity, COALESCE(m.cost_amount, m.quantity * p.cost_price)
                FROM stock_movements m
                JOIN products p ON p.id = m.product_id
                LEFT JOIN users u ON u.id = m.created_by
                WHERE m.client_id = :clientId AND m.branch_id IN (:branchIds) AND m.movement_type = 'OUT'
                  AND m.out_reason IN ('DAMAGED', 'EXPIRED', 'LOST')
                  AND m.occurred_at >= :from AND m.occurred_at < :to
                  AND NOT EXISTS (SELECT 1 FROM stock_movements r WHERE r.reverses_movement_id = m.id)
            ),
            losses AS (
                SELECT e.*, b.name AS branch_name FROM entries e JOIN branches b ON b.id = e.branch_id
            )
            """;
}
