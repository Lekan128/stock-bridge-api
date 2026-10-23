package com.procurepal_services.stock_bridge_api.analytics;

import com.procurepal_services.stock_bridge_api.analytics.dto.BranchTableResponse;
import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.selling.SalesReportService;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner's branch table (MULTI_BRANCH_UX_PLAN.md section 5.8, MULTI_BRANCH_BUILD_PLAN.md section 6):
 * "every depot from home in under 10 seconds". One grouped query per column over the open branches the
 * person sees, so it costs the same at 3 branches as at 26.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchTableService {

    /** A phone that hasn't reported for a week is not counted as one of the branch's phones. */
    private static final int PHONE_DAYS = 7;

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;
    private final SalesReportService salesReportService;

    public BranchTableResponse table(UUID clientId, UUID userId) {
        List<UUID> visible = branchAccessService.viewBranchIds(userId);
        if (visible.isEmpty()) {
            return new BranchTableResponse(List.of(), emptyTotals(), OffsetDateTime.now(ZoneOffset.UTC));
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchIds", visible)
                .addValue("phoneDays", PHONE_DAYS);

        List<Builder> builders = jdbc.query("""
                SELECT b.id, b.name, b.is_default FROM branches b
                WHERE b.client_id = :clientId AND b.id IN (:branchIds) AND b.is_active
                ORDER BY b.is_default DESC, LOWER(b.name)
                """, params, (rs, n) -> new Builder(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("is_default")));
        Map<UUID, Builder> byId = new HashMap<>();
        builders.forEach(builder -> byId.put(builder.id, builder));

        long activeProducts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE client_id = :clientId AND is_active", params, Long.class);

        // Stock worth, products held, and low / out against the branch's own level or the product's.
        jdbc.query("""
                SELECT b.id AS branch_id,
                       COALESCE(SUM(COALESCE(bs.quantity_on_hand, 0) * p.cost_price) FILTER (WHERE p.cost_price IS NOT NULL), 0) AS worth,
                       COUNT(*) FILTER (WHERE COALESCE(bs.quantity_on_hand, 0) > 0 AND p.cost_price IS NULL) AS no_cost,
                       COUNT(*) FILTER (WHERE COALESCE(bs.quantity_on_hand, 0) > 0) AS in_stock,
                       COUNT(*) FILTER (WHERE COALESCE(bs.low_stock_threshold, p.low_stock_threshold) IS NOT NULL
                           AND COALESCE(bs.quantity_on_hand, 0) <= COALESCE(bs.low_stock_threshold, p.low_stock_threshold)) AS low,
                       COUNT(*) FILTER (WHERE COALESCE(bs.quantity_on_hand, 0) <= 0) AS out
                FROM branches b
                CROSS JOIN products p
                LEFT JOIN branch_stock bs ON bs.branch_id = b.id AND bs.product_id = p.id
                WHERE b.id IN (:branchIds) AND p.client_id = :clientId AND p.is_active
                GROUP BY b.id
                """, params, rs -> {
                    Builder builder = byId.get(rs.getObject("branch_id", UUID.class));
                    if (builder == null) return;
                    builder.worth = rs.getBigDecimal("worth");
                    builder.noCost = rs.getLong("no_cost");
                    builder.inStock = rs.getLong("in_stock");
                    builder.low = rs.getLong("low");
                    builder.out = rs.getLong("out");
                });

        // Waybills on the way, each end; missing and damaged not yet checked, at the receiving end.
        jdbc.query("""
                SELECT t.from_branch_id, t.to_branch_id, t.status, t.missing_checked_at,
                       COALESCE(SUM(l.quantity_missing + l.quantity_damaged), 0) AS lost
                FROM stock_transfers t JOIN stock_transfer_lines l ON l.transfer_id = t.id
                WHERE t.client_id = :clientId
                  AND t.status IN ('ON_THE_WAY', 'PARTLY_RECEIVED', 'RECEIVED_SHORT')
                GROUP BY t.id
                """, params, rs -> {
                    boolean open = List.of("ON_THE_WAY", "PARTLY_RECEIVED").contains(rs.getString("status"));
                    Builder from = byId.get(rs.getObject("from_branch_id", UUID.class));
                    Builder to = byId.get(rs.getObject("to_branch_id", UUID.class));
                    if (open && from != null) from.onTheWayOut++;
                    if (open && to != null) to.onTheWayIn++;
                    if (rs.getTimestamp("missing_checked_at") == null && to != null) to.missing += rs.getLong("lost");
                });

        jdbc.query("""
                SELECT branch_id, MAX(occurred_at) AS last_count FROM stock_movements
                WHERE client_id = :clientId AND branch_id IN (:branchIds) AND is_count
                GROUP BY branch_id
                """, params, rs -> {
                    Builder builder = byId.get(rs.getObject("branch_id", UUID.class));
                    if (builder != null) builder.lastCountAt = time(rs, "last_count");
                });

        jdbc.query("""
                SELECT branch_id, COUNT(*) AS phones, COALESCE(SUM(waiting_count), 0) AS waiting,
                       COALESCE(SUM(needs_you_count), 0) AS needs_you, MIN(oldest_waiting_at) AS oldest,
                       MAX(last_seen_at) AS last_seen
                FROM phone_sync_status
                WHERE client_id = :clientId AND branch_id IN (:branchIds)
                  AND last_seen_at > now() - make_interval(days => :phoneDays)
                GROUP BY branch_id
                """, params, rs -> {
                    Builder builder = byId.get(rs.getObject("branch_id", UUID.class));
                    if (builder == null) return;
                    builder.phones = rs.getLong("phones");
                    builder.waiting = rs.getLong("waiting");
                    builder.needsYou = rs.getLong("needs_you");
                    builder.oldestWaitingAt = time(rs, "oldest");
                    builder.phonesLastSeenAt = time(rs, "last_seen");
                });

        // Today's sales per branch, from the Sales report's own query so the two cannot disagree.
        for (SalesReportRow sales : salesReportService.report(clientId, null, null, "branch",
                SalesReportService.Filters.NONE.atBranches(visible), 1000).rows()) {
            Builder builder = byId.get(UUID.fromString(sales.key()));
            if (builder != null) builder.salesToday = sales.totals().salesAmount();
        }

        List<BranchTableResponse.Row> rows = new ArrayList<>();
        for (Builder builder : builders) {
            rows.add(builder.build(activeProducts));
        }
        BranchTableResponse.Totals totals = new BranchTableResponse.Totals(
                money(rows.stream().map(BranchTableResponse.Row::stockWorth).reduce(BigDecimal.ZERO, BigDecimal::add)),
                rows.stream().mapToLong(BranchTableResponse.Row::low).sum(),
                rows.stream().mapToLong(BranchTableResponse.Row::out).sum(),
                // A waybill between two of these branches is one thing on the way, not two.
                rows.stream().mapToLong(BranchTableResponse.Row::onTheWayIn).sum(),
                rows.stream().mapToLong(BranchTableResponse.Row::missing).sum(),
                money(rows.stream().map(BranchTableResponse.Row::salesToday).reduce(BigDecimal.ZERO, BigDecimal::add)),
                rows.stream().mapToLong(BranchTableResponse.Row::waiting).sum(),
                rows.stream().mapToLong(BranchTableResponse.Row::needsYou).sum());
        return new BranchTableResponse(rows, totals, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static BranchTableResponse.Totals emptyTotals() {
        return new BranchTableResponse.Totals(BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0);
    }

    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant().atOffset(ZoneOffset.UTC);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static final class Builder {
        final UUID id;
        final String name;
        final boolean main;
        BigDecimal worth = BigDecimal.ZERO;
        long noCost;
        long inStock;
        long low;
        long out;
        long onTheWayIn;
        long onTheWayOut;
        long missing;
        BigDecimal salesToday = BigDecimal.ZERO;
        OffsetDateTime lastCountAt;
        long phones;
        long waiting;
        long needsYou;
        OffsetDateTime oldestWaitingAt;
        OffsetDateTime phonesLastSeenAt;

        Builder(UUID id, String name, boolean main) {
            this.id = id;
            this.name = name;
            this.main = main;
        }

        BranchTableResponse.Row build(long activeProducts) {
            return new BranchTableResponse.Row(id, name, main, money(worth), noCost, inStock, activeProducts, low, out,
                    onTheWayIn, onTheWayOut, missing, money(salesToday), lastCountAt, phones, waiting, needsYou,
                    oldestWaitingAt, phonesLastSeenAt);
        }
    }
}
