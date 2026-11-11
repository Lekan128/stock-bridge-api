package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaMoveResponse;
import com.procurepal_services.stock_bridge_api.stock.StockWritePermissionGuard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What areas that use stock up gave back or reported of what they were given (STOCK_AREAS_PART_6.md), as the
 * moves read it - kept apart from {@link AreaReturnService}, which writes them and reads moves back.
 */
@Component
@RequiredArgsConstructor
class AreaReturns {

    /** How long after a give its area may give back or report part of it (STOCK_AREAS_PLAN.md §5.6: "recently"). */
    static final Duration WINDOW = Duration.ofDays(7);

    private final NamedParameterJdbcTemplate jdbc;
    private final StockWritePermissionGuard stockWritePermissionGuard;

    /** Per move, per product, oldest first. */
    Map<UUID, Map<UUID, List<AreaMoveResponse.Return>>> byMove(Collection<UUID> moveIds) {
        Map<UUID, Map<UUID, List<AreaMoveResponse.Return>>> found = new HashMap<>();
        if (moveIds.isEmpty()) {
            return found;
        }
        jdbc.query("""
                SELECT r.move_id, r.product_id, r.kind, r.quantity, r.reason, r.note, r.occurred_at,
                       NULLIF(TRIM(COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '')), '') AS full_name,
                       u.username
                FROM area_move_returns r LEFT JOIN users u ON u.id = r.created_by
                WHERE r.move_id = ANY(:ids) ORDER BY r.occurred_at, r.created_at
                """, new MapSqlParameterSource("ids", moveIds.toArray(UUID[]::new)), rs -> {
                    String name = rs.getString("full_name") != null ? rs.getString("full_name") : rs.getString("username");
                    found.computeIfAbsent(rs.getObject("move_id", UUID.class), key -> new HashMap<>())
                            .computeIfAbsent(rs.getObject("product_id", UUID.class), key -> new ArrayList<>())
                            .add(new AreaMoveResponse.Return(rs.getString("kind"), rs.getBigDecimal("quantity"),
                                    rs.getString("reason"), rs.getString("note"), name,
                                    rs.getObject("occurred_at", java.time.OffsetDateTime.class)));
                });
        return found;
    }

    /** May record a give back or a report at all: someone who can record stock in or out (D-E9). */
    boolean mayRecord(UUID userId) {
        return stockWritePermissionGuard.has(userId, "STOCK_IN") || stockWritePermissionGuard.has(userId, "STOCK_OUT");
    }
}
