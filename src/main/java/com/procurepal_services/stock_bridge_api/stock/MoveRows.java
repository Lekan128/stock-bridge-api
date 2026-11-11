package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Moves between a branch's areas (V63) as rows of the movements list and a product's history
 * (STOCK_AREAS_PART_2.md, owner's test): one row per line, type {@link MovementType#MOVE}, from
 * {@code areaId} to {@code toAreaId}. Only moves that are done and went to an area that keeps stock: a
 * give to an area that uses what it's given is already a Used stock-out in the ledger, and an undone
 * move changed nothing (the Moves list still shows it, as undone). A move that waited for its area to confirm
 * (Part 4) is a row once confirmed, for what arrived; while it waits, the Moves list shows it.
 */
@Component
@RequiredArgsConstructor
class MoveRows {

    /** The newest rows, up to a limit, and how many there are in all. */
    record Window(List<StockMovementResponse> rows, long total) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param branchIds only these branches' moves; null for every branch of the company.
     * @param byCreatedAt newest by when it was written, as a product's history sorts, rather than by when
     *     it happened, as the movements list does.
     */
    Window newest(UUID clientId, Collection<UUID> branchIds, UUID productId, OffsetDateTime from, OffsetDateTime to,
            boolean byCreatedAt, int limit) {
        return newest(clientId, branchIds, null, productId, from, to, byCreatedAt, limit);
    }

    /** @param areas only moves into or out of these areas (Part 3.6); null is every area. */
    Window newest(UUID clientId, Collection<UUID> branchIds, com.procurepal_services.stock_bridge_api.branch.AreaScope areas,
            UUID productId, OffsetDateTime from, OffsetDateTime to, boolean byCreatedAt, int limit) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new Window(List.of(), 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("clientId", clientId).addValue("limit", limit);
        StringBuilder where = new StringBuilder(
                "m.client_id = :clientId AND ta.keeps_stock = TRUE"
                        + " AND ((NOT m.waits AND m.status = 'DONE')"
                        + " OR (m.waits AND m.status IN ('DONE', 'CONFIRMED_SHORT') AND l.quantity_confirmed > 0))"
                        // A line used straight away (Part 3.5) is a Used stock-out row already.
                        + " AND l.used_movement_id IS NULL AND NOT l.used_now");
        if (branchIds != null) {
            where.append(" AND m.branch_id = ANY(:branchIds)");
            params.addValue("branchIds", branchIds.toArray(UUID[]::new));
        }
        if (areas != null) {
            where.append(" AND (m.from_area_id IN (:scopeAreaIds) OR m.to_area_id IN (:scopeAreaIds))");
            com.procurepal_services.stock_bridge_api.branch.AreaScope.bind(areas, params);
        }
        if (productId != null) {
            where.append(" AND l.product_id = :productId");
            params.addValue("productId", productId);
        }
        if (from != null) {
            where.append(" AND m.moved_at >= :from");
            params.addValue("from", from);
        }
        if (to != null) {
            where.append(" AND m.moved_at <= :to");
            params.addValue("to", to);
        }
        String joins = """
                FROM area_move_lines l
                JOIN area_moves m ON m.id = l.move_id
                JOIN areas ta ON ta.id = m.to_area_id
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + joins + " WHERE " + where, params, Long.class);
        if (total == null || total == 0) {
            return new Window(List.of(), 0);
        }
        String order = byCreatedAt ? "m.created_at DESC, m.moved_at DESC" : "m.moved_at DESC, m.created_at DESC";
        List<StockMovementResponse> rows = jdbc.query("""
                SELECT l.move_id, l.product_id, p.name, p.sku, p.unit_of_measure,
                       CASE WHEN m.waits THEN l.quantity_confirmed ELSE l.quantity END AS quantity,
                       -- As typed, unless less arrived than was typed.
                       CASE WHEN m.waits AND l.quantity_confirmed <> l.quantity THEN NULL ELSE l.entered_unit END
                           AS entered_unit,
                       CASE WHEN m.waits AND l.quantity_confirmed <> l.quantity THEN NULL ELSE l.entered_quantity END
                           AS entered_quantity,
                       m.note, m.moved_by, m.moved_at, m.created_at, m.branch_id,
                       m.from_area_id, fa.name AS from_name, m.to_area_id, ta.name AS to_name,
                       NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username
                """ + joins + """
                JOIN areas fa ON fa.id = m.from_area_id
                JOIN products p ON p.id = l.product_id
                LEFT JOIN users u ON u.id = m.moved_by
                WHERE\s""" + where + " ORDER BY " + order + ", p.name LIMIT :limit",
                params,
                (rs, rowNum) -> {
                    UUID moveId = rs.getObject("move_id", UUID.class);
                    UUID lineProductId = rs.getObject("product_id", UUID.class);
                    String fullName = rs.getString("full_name");
                    return new StockMovementResponse(
                            // One move line is one row; its id is made from the two, so a list keys it steadily.
                            UUID.nameUUIDFromBytes((moveId + ":" + lineProductId).getBytes(StandardCharsets.UTF_8)),
                            lineProductId,
                            rs.getString("name"),
                            rs.getString("sku"),
                            rs.getString("unit_of_measure"),
                            MovementType.MOVE,
                            rs.getBigDecimal("quantity"),
                            null,
                            rs.getString("note"),
                            rs.getObject("moved_by", UUID.class),
                            fullName != null ? fullName : rs.getString("username"),
                            null,
                            null,
                            null,
                            null,
                            rs.getString("entered_unit"),
                            rs.getBigDecimal("entered_quantity"),
                            null,
                            null,
                            rs.getObject("moved_at", OffsetDateTime.class),
                            rs.getObject("created_at", OffsetDateTime.class),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            rs.getObject("branch_id", UUID.class),
                            rs.getObject("from_area_id", UUID.class),
                            null,
                            rs.getObject("to_area_id", UUID.class),
                            moveId,
                            rs.getString("from_name"),
                            rs.getString("to_name"),
                            null);
                });
        return new Window(rows, total);
    }
}
