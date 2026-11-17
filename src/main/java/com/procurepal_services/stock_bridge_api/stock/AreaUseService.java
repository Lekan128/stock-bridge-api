package com.procurepal_services.stock_bridge_api.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Kitchen this month" on Stock movements (selling till Step 6, owner 2026-11-17): for an area that makes what is
 * sold, each ingredient over the period - what came in, what the plates sold took (Step 5's Used rows naming a sale
 * line, less cancellations), what counts found used on top of that, and what's there now. Only ingredients the plates
 * took in the period: an area with none gets nothing, and the page shows no line.
 */
@Service
@RequiredArgsConstructor
public class AreaUseService {

    private final NamedParameterJdbcTemplate jdbc;

    public record Ingredient(
            UUID productId, String name, String stockUnit, BigDecimal cameIn, BigDecimal platesTook,
            BigDecimal platesTookCost, boolean counted, BigDecimal usedOnTop, BigDecimal usedOnTopCost, BigDecimal left) {}

    /** {@code counted}: a count of any of them in the period; totals are null while any part has no cost. */
    public record AreaUse(
            UUID areaId, String areaName, List<Ingredient> ingredients, BigDecimal platesTookCost, boolean counted,
            BigDecimal usedOnTopCost, OffsetDateTime lastCountedAt) {}

    @Transactional(readOnly = true)
    public AreaUse of(UUID clientId, UUID branchId, UUID areaId, OffsetDateTime from, OffsetDateTime to) {
        String areaName = jdbc.queryForList(
                "SELECT name FROM areas WHERE id = :areaId AND branch_id = :branchId AND client_id = :clientId",
                params(clientId, branchId, areaId, from, to), String.class).stream().findFirst().orElse(null);
        if (areaName == null) {
            return new AreaUse(areaId, null, List.of(), null, false, null, null);
        }
        String period = " AND (CAST(:from AS timestamptz) IS NULL OR m.occurred_at >= :from)"
                + " AND (CAST(:to AS timestamptz) IS NULL OR m.occurred_at < :to)";
        List<Ingredient> ingredients = new ArrayList<>();
        jdbc.query("""
                WITH took AS (
                    SELECT m.product_id,
                           SUM(CASE WHEN m.movement_type = 'OUT' THEN m.quantity ELSE -m.quantity END) AS qty,
                           SUM(m.cost_amount) AS cost,
                           BOOL_OR(m.cost_amount IS NULL AND m.movement_type = 'OUT') AS uncosted
                    FROM stock_movements m
                    WHERE m.client_id = :clientId AND m.branch_id = :branchId AND m.area_id = :areaId
                      AND m.used_for_movement_id IS NOT NULL""" + period + """
                    GROUP BY m.product_id),
                counts AS (
                    SELECT m.product_id,
                           SUM(CASE WHEN m.movement_type = 'OUT' THEN m.quantity ELSE -m.quantity END) AS qty,
                           COUNT(*) AS n
                    FROM stock_movements m
                    WHERE m.client_id = :clientId AND m.branch_id = :branchId AND m.area_id = :areaId AND m.is_count
                      AND m.product_id IN (SELECT product_id FROM took)""" + period + """
                    GROUP BY m.product_id),
                came AS (
                    SELECT m.product_id, SUM(m.quantity) AS qty
                    FROM stock_movements m
                    WHERE m.client_id = :clientId AND m.branch_id = :branchId AND m.area_id = :areaId
                      AND m.movement_type IN ('IN', 'TRANSFER_IN')
                      AND m.product_id IN (SELECT product_id FROM took)""" + period + """
                    GROUP BY m.product_id
                    UNION ALL
                    SELECT l.product_id, SUM(COALESCE(l.quantity_confirmed, l.quantity))
                    FROM area_moves v JOIN area_move_lines l ON l.move_id = v.id
                    WHERE v.client_id = :clientId AND v.branch_id = :branchId AND v.to_area_id = :areaId
                      AND v.status IN ('DONE', 'CONFIRMED_SHORT')
                      AND l.product_id IN (SELECT product_id FROM took)
                      AND (CAST(:from AS timestamptz) IS NULL OR v.moved_at >= :from)
                      AND (CAST(:to AS timestamptz) IS NULL OR v.moved_at < :to)
                    GROUP BY l.product_id)
                SELECT p.id, p.name, p.unit_of_measure, p.cost_price,
                       t.qty AS took, t.cost AS took_cost, t.uncosted,
                       COALESCE((SELECT SUM(c.qty) FROM came c WHERE c.product_id = p.id), 0) AS came_in,
                       cn.qty AS on_top, COALESCE(cn.n, 0) AS counts,
                       COALESCE((SELECT a.quantity_on_hand FROM area_stock a WHERE a.area_id = :areaId AND a.product_id = p.id), 0) AS left_now
                FROM took t JOIN products p ON p.id = t.product_id
                LEFT JOIN counts cn ON cn.product_id = p.id
                WHERE t.qty > 0
                ORDER BY p.name
                """, params(clientId, branchId, areaId, from, to), rs -> {
            BigDecimal costPrice = rs.getBigDecimal("cost_price");
            boolean counted = rs.getLong("counts") > 0;
            BigDecimal onTop = counted ? rs.getBigDecimal("on_top") : null;
            BigDecimal onTopCost = onTop == null || costPrice == null
                    ? null : costPrice.multiply(onTop).setScale(2, RoundingMode.HALF_UP);
            ingredients.add(new Ingredient(
                    rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit_of_measure"),
                    rs.getBigDecimal("came_in"), rs.getBigDecimal("took"),
                    rs.getBoolean("uncosted") ? null : rs.getBigDecimal("took_cost"),
                    counted, onTop, onTopCost, rs.getBigDecimal("left_now")));
        });
        BigDecimal tookCost = BigDecimal.ZERO;
        BigDecimal onTopCost = BigDecimal.ZERO;
        boolean anyCounted = false;
        for (Ingredient ingredient : ingredients) {
            tookCost = tookCost == null || ingredient.platesTookCost() == null ? null : tookCost.add(ingredient.platesTookCost());
            if (ingredient.counted()) {
                anyCounted = true;
                onTopCost = onTopCost == null || ingredient.usedOnTopCost() == null ? null : onTopCost.add(ingredient.usedOnTopCost());
            }
        }
        OffsetDateTime lastCounted = ingredients.isEmpty() ? null : jdbc.queryForList("""
                SELECT MAX(m.occurred_at) FROM stock_movements m
                WHERE m.client_id = :clientId AND m.branch_id = :branchId AND m.area_id = :areaId AND m.is_count""" + period,
                params(clientId, branchId, areaId, from, to), OffsetDateTime.class).stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new AreaUse(areaId, areaName, ingredients, tookCost, anyCounted, anyCounted ? onTopCost : null, lastCounted);
    }

    private static MapSqlParameterSource params(UUID clientId, UUID branchId, UUID areaId, OffsetDateTime from, OffsetDateTime to) {
        return new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId)
                .addValue("areaId", areaId).addValue("from", from).addValue("to", to);
    }
}
