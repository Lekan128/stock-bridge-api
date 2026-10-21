package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.selling.dto.SellingHealthResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The field test's scoreboard (SELLING_PRICE_PLAN.md section 9, Phase 7): every company's sale lines
 * in a window, read straight off the ledger. Cross-tenant on purpose - it is for the platform
 * operator - so it is plain SQL with the client named in every query, never the tenant filter.
 */
@Service
@RequiredArgsConstructor
public class SellingHealthService {

    /** A product's price is "probably wrong" when it is changed on at least half of its lines... */
    private static final BigDecimal EDIT_SHARE_TO_CHECK = new BigDecimal("0.5");
    /** ...and it has sold often enough for that to mean something. */
    private static final int MIN_LINES_TO_CHECK = 5;

    private static final String LINES = """
            WITH lines AS (
                SELECT o.client_id, o.product_id, o.sale_id, o.occurred_at,
                       (o.sale_amount IS NOT NULL) AS priced,
                       (o.sale_amount IS NOT NULL AND o.cost_amount IS NOT NULL) AS known_cost,
                       (c.id IS NOT NULL) AS price_added,
                       (r.id IS NOT NULL) AS cancelled,
                       (o.usual_price_at_time IS NOT NULL AND o.unit_price_at_time IS NOT NULL) AS has_usual,
                       (o.usual_price_at_time IS NOT NULL AND o.unit_price_at_time IS NOT NULL
                            AND ABS(o.unit_price_at_time - o.usual_price_at_time) > 0.005) AS edited
                FROM stock_movements o
                LEFT JOIN stock_movements r ON r.reverses_movement_id = o.id
                LEFT JOIN stock_movements c ON c.corrects_movement_id = o.id
                WHERE o.sale_id IS NOT NULL AND o.movement_type = 'OUT'
                  AND o.occurred_at >= :from AND o.occurred_at < :to
            )
            """;

    private static final String AGGREGATES = """
            COUNT(DISTINCT sale_id) FILTER (WHERE NOT cancelled) AS sale_count,
            COUNT(*) FILTER (WHERE NOT cancelled) AS line_count,
            COUNT(*) FILTER (WHERE NOT cancelled AND priced) AS priced,
            COUNT(*) FILTER (WHERE NOT cancelled AND known_cost) AS known_cost,
            COUNT(*) FILTER (WHERE NOT cancelled AND price_added) AS price_added,
            COUNT(*) FILTER (WHERE cancelled) AS cancelled,
            COUNT(*) FILTER (WHERE NOT cancelled AND has_usual) AS has_usual,
            COUNT(*) FILTER (WHERE NOT cancelled AND edited) AS edited
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SellingPolicy sellingPolicy;

    @Transactional(readOnly = true)
    public SellingHealthResponse health(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start = from != null ? from : end.minusDays(14);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("from", start).addValue("to", end);

        Map<String, Long> via = new LinkedHashMap<>();
        jdbc.query("""
                SELECT COALESCE(last.source, 'UNKNOWN') AS source, COUNT(*) AS n
                FROM clients cl
                LEFT JOIN LATERAL (
                    SELECT ch.source FROM client_setting_changes ch
                    WHERE ch.client_id = cl.id AND ch.setting = 'SELLS_STOCK' AND ch.new_value = 'true'
                    ORDER BY ch.changed_at DESC LIMIT 1
                ) last ON TRUE
                WHERE cl.sells_stock
                GROUP BY 1 ORDER BY 2 DESC
                """, new MapSqlParameterSource(), (ResultSet rs) -> {
            via.put(rs.getString("source"), rs.getLong("n"));
        });
        long companiesSelling = via.values().stream().mapToLong(Long::longValue).sum();

        Totals all = jdbc.queryForObject(LINES + "SELECT " + AGGREGATES + " FROM lines", params, (rs, n) -> totals(rs));

        List<SellingHealthResponse.ProductToCheck> toCheck = jdbc.query(LINES + """
                SELECT l.client_id, cl.name AS client_name, l.product_id, p.name AS product_name,
                       COUNT(*) FILTER (WHERE l.has_usual AND NOT l.cancelled) AS has_usual,
                       COUNT(*) FILTER (WHERE l.edited AND NOT l.cancelled) AS edited
                FROM lines l JOIN products p ON p.id = l.product_id JOIN clients cl ON cl.id = l.client_id
                GROUP BY l.client_id, cl.name, l.product_id, p.name
                HAVING COUNT(*) FILTER (WHERE l.has_usual AND NOT l.cancelled) >= :minLines
                   AND COUNT(*) FILTER (WHERE l.edited AND NOT l.cancelled)
                       >= :share * COUNT(*) FILTER (WHERE l.has_usual AND NOT l.cancelled)
                ORDER BY COUNT(*) FILTER (WHERE l.edited AND NOT l.cancelled) DESC
                LIMIT 10
                """,
                new MapSqlParameterSource(params.getValues()).addValue("minLines", MIN_LINES_TO_CHECK).addValue("share", EDIT_SHARE_TO_CHECK),
                (rs, n) -> new SellingHealthResponse.ProductToCheck(
                        rs.getObject("client_id", UUID.class), rs.getString("client_name"),
                        rs.getObject("product_id", UUID.class), rs.getString("product_name"),
                        rs.getLong("has_usual"), share(rs.getLong("edited"), rs.getLong("has_usual"))));

        List<SellingHealthResponse.CompanyHealth> companies = jdbc.query(LINES + """
                SELECT cl.id AS client_id, cl.name AS client_name, MAX(l.occurred_at) AS last_sale_at,
                """ + AGGREGATES.replace("sale_id", "l.sale_id") + """
                FROM clients cl LEFT JOIN lines l ON l.client_id = cl.id
                WHERE cl.sells_stock OR l.client_id IS NOT NULL
                GROUP BY cl.id, cl.name
                ORDER BY COUNT(l.sale_id) DESC, cl.name
                LIMIT 100
                """, params, (rs, n) -> {
            Totals t = totals(rs);
            Timestamp last = rs.getTimestamp("last_sale_at");
            return new SellingHealthResponse.CompanyHealth(
                    rs.getObject("client_id", UUID.class), rs.getString("client_name"), t.sales, t.lines,
                    share(t.priced, t.lines), share(t.knownCost, t.priced), share(t.edited, t.hasUsual),
                    last == null ? null : last.toInstant().atOffset(ZoneOffset.UTC));
        });

        return new SellingHealthResponse(
                start, end, sellingPolicy.isOpen() ? "open" : "pilot", companiesSelling, via,
                all.sales, all.lines,
                share(all.priced, all.lines), share(all.knownCost, all.priced),
                per100(all.priceAdded, all.lines), per100(all.cancelled, all.lines + all.cancelled),
                share(all.edited, all.hasUsual), toCheck, companies);
    }

    private record Totals(long sales, long lines, long priced, long knownCost, long priceAdded, long cancelled,
            long hasUsual, long edited) {
    }

    private static Totals totals(ResultSet rs) throws SQLException {
        return new Totals(rs.getLong("sale_count"), rs.getLong("line_count"), rs.getLong("priced"),
                rs.getLong("known_cost"), rs.getLong("price_added"), rs.getLong("cancelled"),
                rs.getLong("has_usual"), rs.getLong("edited"));
    }

    /** A share to four places (0.9512), or null when there is nothing to divide by. */
    private static BigDecimal share(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal per100(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
