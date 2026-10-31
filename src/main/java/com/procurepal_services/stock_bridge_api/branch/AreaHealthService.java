package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaHealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Area Health (STOCK_AREAS_BUILD_PLAN.md section 6): whether every area figure is where the area model
 * says it is. Cross-tenant on purpose, for the platform operator, so plain SQL with the company named in
 * every join and never the tenant filter - like {@link BranchHealthService}.
 */
@Service
@RequiredArgsConstructor
public class AreaHealthService {

    /** Branches whose area rows are the truth: they have had more than one area (D-E2). */
    private static final String AREA_MODE = """
            area_mode AS (SELECT branch_id FROM areas GROUP BY branch_id HAVING COUNT(*) > 1)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AreaHealthResponse health() {
        long branches = count("SELECT COUNT(*) FROM branches");
        long areaModeBranches = count("WITH " + AREA_MODE + " SELECT COUNT(*) FROM area_mode");
        long areas = count("SELECT COUNT(*) FROM areas");
        long withoutOriginal = count("""
                SELECT COUNT(*) FROM branches b
                WHERE (SELECT COUNT(*) FROM areas a WHERE a.branch_id = b.id AND a.is_original) <> 1
                """);
        long withoutDelivery = count("""
                SELECT COUNT(*) FROM branches b
                WHERE (SELECT COUNT(*) FROM areas a WHERE a.branch_id = b.id AND a.is_delivery) <> 1
                """);
        long foreignAreas = count("""
                SELECT COUNT(*) FROM areas a JOIN branches b ON b.id = a.branch_id
                WHERE b.client_id <> a.client_id
                """);
        long foreignMovements = count("""
                SELECT COUNT(*) FROM stock_movements m JOIN areas a ON a.id = m.area_id
                WHERE a.branch_id <> m.branch_id
                """);
        long usedHolding = count("""
                SELECT COUNT(*) FROM area_stock s JOIN areas a ON a.id = s.area_id
                WHERE NOT a.keeps_stock AND s.quantity_on_hand <> 0
                """);
        long sumMismatch = count("WITH " + AREA_MODE + """
                , sums AS (
                    -- What the areas hold, plus what waits for an area to confirm it (Part 4, D-E6).
                    SELECT branch_id, product_id, SUM(quantity) AS total FROM (
                        SELECT branch_id, product_id, quantity_on_hand AS quantity FROM area_stock
                        WHERE branch_id IN (SELECT branch_id FROM area_mode)
                        UNION ALL
                        SELECT m.branch_id, l.product_id, l.quantity FROM area_moves m
                        JOIN area_move_lines l ON l.move_id = m.id
                        WHERE m.status = 'WAITING' AND m.branch_id IN (SELECT branch_id FROM area_mode)
                    ) held
                    GROUP BY branch_id, product_id
                ), figures AS (
                    SELECT branch_id, product_id, quantity_on_hand FROM branch_stock
                    WHERE branch_id IN (SELECT branch_id FROM area_mode)
                )
                SELECT COUNT(*) FROM figures f
                FULL JOIN sums s ON s.branch_id = f.branch_id AND s.product_id = f.product_id
                WHERE COALESCE(f.quantity_on_hand, 0) <> COALESCE(s.total, 0)
                """);
        return new AreaHealthResponse(
                branches,
                areaModeBranches,
                areas,
                withoutOriginal,
                withoutDelivery,
                foreignAreas,
                foreignMovements,
                usedHolding,
                sumMismatch,
                withoutOriginal == 0 && withoutDelivery == 0 && foreignAreas == 0 && foreignMovements == 0
                        && usedHolding == 0 && sumMismatch == 0);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }
}
