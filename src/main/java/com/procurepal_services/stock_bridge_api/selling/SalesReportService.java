package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.settlement.PayoutCadence;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesReportRow;
import com.procurepal_services.stock_bridge_api.selling.dto.SalesTotals;
import com.procurepal_services.stock_bridge_api.selling.dto.StockWorthResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales and profit, read off the stock ledger (SELLING_PRICE_PLAN.md sections 4.7, 6.10, 6.11).
 * There is no sales table: a sale is its stock-out lines, so these figures cannot drift from stock.
 *
 * <h2>One line, as it stands</h2>
 * Every figure is built from sale LINES - the OUT rows of a sale - each joined to its fixes: a
 * cancellation leaves the line counted as cancelled and out of every sum; a price added later is
 * the line's price. A line belongs to the day it was SOLD, whenever it was fixed - so cancelling
 * yesterday's sale takes it out of yesterday, not today. Served by idx_stock_movements_sales_by_date
 * and the unique indexes on the two fix columns.
 *
 * <h2>Footer equals rows, by construction</h2>
 * The rows and the totals are two GROUP BYs over the same {@code lines} set with the same filters -
 * never a total added up in Java from rows that might have been cut short.
 *
 * <h2>Honest gaps</h2>
 * Profit is only worked out over lines with both a price and a cost. The lines it leaves out are
 * counted - no price, no cost - so the page can say "Profit leaves out 11 sales" instead of guessing.
 */
@Service
@RequiredArgsConstructor
public class SalesReportService {

    public static final List<String> GROUPINGS = List.of("sale", "product", "day", "user", "category");
    static final int MAX_ROWS = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    /** Optional narrowing of the lines a report covers. Nulls and falses mean "no filter". */
    public record Filters(UUID productId, UUID userId, boolean belowUsualPrice, boolean belowCost, boolean cancelledOnly) {
        public static final Filters NONE = new Filters(null, null, false, false, false);
    }

    @Transactional(readOnly = true)
    public SalesReportResponse report(
            UUID clientId, OffsetDateTime from, OffsetDateTime to, String groupBy, Filters filters, Integer limit) {
        OffsetDateTime[] range = rangeOrToday(from, to);
        String grouping = groupBy == null ? "sale" : groupBy.toLowerCase(Locale.ROOT);
        if (!GROUPINGS.contains(grouping)) {
            throw new IllegalArgumentException("groupBy must be one of " + GROUPINGS);
        }
        int rowLimit = limit == null ? 500 : Math.max(1, Math.min(limit, MAX_ROWS));
        MapSqlParameterSource params = params(clientId, range, filters);

        List<SalesReportRow> rows = jdbc.query(groupQuery(grouping, filters) + " LIMIT " + (rowLimit + 1), params,
                (rs, n) -> row(grouping, rs));
        boolean truncated = rows.size() > rowLimit;
        if (truncated) {
            rows = new ArrayList<>(rows.subList(0, rowLimit));
        }
        return new SalesReportResponse(range[0], range[1], grouping, rows, truncated, totals(clientId, range[0], range[1], filters));
    }

    /** The totals alone - the dashboard figure and a cashier's "your sales today". */
    @Transactional(readOnly = true)
    public SalesTotals totals(UUID clientId, OffsetDateTime from, OffsetDateTime to, Filters filters) {
        OffsetDateTime[] range = rangeOrToday(from, to);
        return jdbc.queryForObject(
                linesCte(filters) + "SELECT " + AGGREGATES + " FROM lines", params(clientId, range, filters),
                (rs, n) -> totals(rs));
    }

    @Transactional(readOnly = true)
    public StockWorthResponse stockWorth(UUID clientId) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(p.quantity_on_hand * p.cost_price) FILTER (WHERE p.cost_price IS NOT NULL), 0) AS at_cost,
                       COALESCE(SUM(p.quantity_on_hand * p.unit_price) FILTER (WHERE p.unit_price IS NOT NULL), 0) AS at_price,
                       COUNT(*) AS active,
                       COUNT(*) FILTER (WHERE p.cost_price IS NULL) AS no_cost,
                       COUNT(*) FILTER (WHERE p.unit_price IS NULL) AS no_price
                FROM products p
                WHERE p.client_id = :clientId AND p.is_active
                """,
                new MapSqlParameterSource("clientId", clientId),
                (rs, n) -> new StockWorthResponse(
                        rs.getBigDecimal("at_cost").setScale(2, RoundingMode.HALF_UP),
                        rs.getBigDecimal("at_price").setScale(2, RoundingMode.HALF_UP),
                        rs.getLong("active"),
                        rs.getLong("no_cost"),
                        rs.getLong("no_price")));
    }

    /**
     * Null bounds mean "today in Lagos" - the shopkeeper's question at closing time. A missing
     * {@code to} is "now", a missing {@code from} the start of {@code to}'s day.
     */
    static OffsetDateTime[] rangeOrToday(OffsetDateTime from, OffsetDateTime to) {
        if (from == null && to == null) {
            LocalDate today = LocalDate.now(PayoutCadence.ZONE);
            return new OffsetDateTime[] {
                today.atStartOfDay(PayoutCadence.ZONE).toOffsetDateTime(),
                today.plusDays(1).atStartOfDay(PayoutCadence.ZONE).toOffsetDateTime()
            };
        }
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = from != null
                ? from
                : end.atZoneSameInstant(PayoutCadence.ZONE).toLocalDate().atStartOfDay(PayoutCadence.ZONE).toOffsetDateTime();
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        return new OffsetDateTime[] {start, end};
    }

    // ------------------------------------------------------------------------------ SQL ----

    /**
     * Every sale line in range, as it stands. {@code below_usual} allows half a kobo: both prices are
     * stored per stock unit to the kobo, and a carton sold at exactly its usual price must not be
     * flagged by rounding.
     */
    private static String linesCte(Filters filters) {
        StringBuilder where = new StringBuilder();
        if (filters.productId() != null) {
            where.append(" AND o.product_id = :productId");
        }
        if (filters.userId() != null) {
            where.append(" AND o.created_by = :userId");
        }
        StringBuilder narrowed = new StringBuilder(" WHERE TRUE");
        if (filters.belowUsualPrice()) {
            narrowed.append(" AND below_usual AND NOT cancelled");
        }
        if (filters.belowCost()) {
            narrowed.append(" AND amount IS NOT NULL AND cost IS NOT NULL AND amount < cost AND NOT cancelled");
        }
        if (filters.cancelledOnly()) {
            narrowed.append(" AND cancelled");
        }
        return """
                WITH all_lines AS (
                    SELECT o.id, o.sale_id, o.product_id, o.created_by, o.occurred_at, o.quantity,
                           COALESCE(o.sale_amount, c.sale_amount) AS amount,
                           o.cost_amount AS cost,
                           (r.id IS NOT NULL) AS cancelled,
                           (o.usual_price_at_time IS NOT NULL AND o.unit_price_at_time IS NOT NULL
                                AND o.unit_price_at_time < o.usual_price_at_time - 0.005) AS below_usual
                    FROM stock_movements o
                    LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                    LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                    WHERE o.client_id = :clientId
                      AND o.sale_id IS NOT NULL
                      AND o.movement_type = 'OUT'
                      AND o.occurred_at >= :from AND o.occurred_at < :to"""
                + where
                + """
                ),
                lines AS (SELECT * FROM all_lines"""
                + narrowed
                + ")\n";
    }

    private static final String AGGREGATES = """
            COUNT(DISTINCT sale_id) FILTER (WHERE NOT cancelled) AS sale_count,
            COUNT(*) FILTER (WHERE NOT cancelled) AS line_count,
            COALESCE(SUM(amount) FILTER (WHERE NOT cancelled), 0) AS sales_amount,
            COALESCE(SUM(amount) FILTER (WHERE NOT cancelled AND amount IS NOT NULL AND cost IS NOT NULL), 0) AS known_sales,
            COALESCE(SUM(cost) FILTER (WHERE NOT cancelled AND amount IS NOT NULL AND cost IS NOT NULL), 0) AS known_cost,
            COUNT(*) FILTER (WHERE NOT cancelled AND amount IS NULL) AS unpriced,
            COUNT(*) FILTER (WHERE NOT cancelled AND amount IS NOT NULL AND cost IS NULL) AS unknown_cost,
            COUNT(*) FILTER (WHERE cancelled) AS cancelled_count,
            COUNT(*) FILTER (WHERE NOT cancelled AND below_usual) AS below_usual_count,
            COUNT(*) FILTER (WHERE NOT cancelled AND amount IS NOT NULL AND cost IS NOT NULL AND amount < cost) AS below_cost_count,
            COALESCE(SUM(quantity) FILTER (WHERE NOT cancelled), 0) AS quantity""";

    private static String groupQuery(String grouping, Filters filters) {
        String cte = linesCte(filters);
        return switch (grouping) {
            case "sale" -> cte + """
                    SELECT sale_id::text AS key, NULL AS label, NULL AS unit, MIN(occurred_at) AS occurred_at,
                    """ + AGGREGATES + """

                    FROM lines GROUP BY sale_id ORDER BY MIN(occurred_at) DESC, sale_id""";
            case "product" -> cte + """
                    SELECT l.product_id::text AS key, p.name AS label, p.unit_of_measure AS unit, NULL::timestamptz AS occurred_at,
                    """ + AGGREGATES + """

                    FROM lines l JOIN products p ON p.id = l.product_id
                    GROUP BY l.product_id, p.name, p.unit_of_measure
                    ORDER BY COALESCE(SUM(l.amount) FILTER (WHERE NOT l.cancelled), 0) DESC, p.name""";
            case "day" -> cte + """
                    SELECT (occurred_at AT TIME ZONE 'Africa/Lagos')::date::text AS key, NULL AS label, NULL AS unit,
                           NULL::timestamptz AS occurred_at,
                    """ + AGGREGATES + """

                    FROM lines GROUP BY (occurred_at AT TIME ZONE 'Africa/Lagos')::date ORDER BY 1""";
            case "user" -> cte + """
                    SELECT l.created_by::text AS key,
                           COALESCE(NULLIF(TRIM(CONCAT(u.first_name, ' ', u.last_name)), ''), u.username) AS label,
                           NULL AS unit, NULL::timestamptz AS occurred_at,
                    """ + AGGREGATES + """

                    FROM lines l LEFT JOIN users u ON u.id = l.created_by
                    GROUP BY l.created_by, u.first_name, u.last_name, u.username
                    ORDER BY COALESCE(SUM(l.amount) FILTER (WHERE NOT l.cancelled), 0) DESC""";
            case "category" -> cte + """
                    SELECT p.company_category_id::text AS key, COALESCE(cc.name, 'No category') AS label,
                           NULL AS unit, NULL::timestamptz AS occurred_at,
                    """ + AGGREGATES + """

                    FROM lines l JOIN products p ON p.id = l.product_id
                    LEFT JOIN company_categories cc ON cc.id = p.company_category_id
                    GROUP BY p.company_category_id, cc.name
                    ORDER BY COALESCE(SUM(l.amount) FILTER (WHERE NOT l.cancelled), 0) DESC""";
            default -> throw new IllegalArgumentException(grouping);
        };
    }

    private static MapSqlParameterSource params(UUID clientId, OffsetDateTime[] range, Filters filters) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("from", range[0])
                .addValue("to", range[1]);
        if (filters.productId() != null) {
            params.addValue("productId", filters.productId());
        }
        if (filters.userId() != null) {
            params.addValue("userId", filters.userId());
        }
        return params;
    }

    private static SalesReportRow row(String grouping, ResultSet rs) throws SQLException {
        String key = rs.getString("key");
        String label = rs.getString("label");
        if ("day".equals(grouping)) {
            label = key;
        }
        Timestamp occurred = rs.getTimestamp("occurred_at");
        return new SalesReportRow(
                key,
                label,
                "product".equals(grouping) ? rs.getLong("quantity") : null,
                rs.getString("unit"),
                occurred == null ? null : occurred.toInstant().atOffset(ZoneOffset.UTC),
                totals(rs));
    }

    private static SalesTotals totals(ResultSet rs) throws SQLException {
        BigDecimal knownSales = money(rs.getBigDecimal("known_sales"));
        BigDecimal knownCost = money(rs.getBigDecimal("known_cost"));
        BigDecimal profit = knownSales.subtract(knownCost);
        return new SalesTotals(
                rs.getLong("sale_count"),
                rs.getLong("line_count"),
                money(rs.getBigDecimal("sales_amount")),
                knownSales,
                knownCost,
                profit,
                knownSales.signum() == 0 ? null : profit.divide(knownSales, 4, RoundingMode.HALF_UP),
                rs.getLong("unpriced"),
                rs.getLong("unknown_cost"),
                rs.getLong("cancelled_count"),
                rs.getLong("below_usual_count"),
                rs.getLong("below_cost_count"));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
