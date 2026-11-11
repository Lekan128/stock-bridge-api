package com.procurepal_services.stock_bridge_api.branch;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who to tell about something in an area (Parts 4 and 5), and what to call them. One notification per event per
 * reader (D-A11): the people placed in that area - or, when nobody is, the people who work in every area of the
 * branch (managers, owners), so nothing waits unseen.
 */
@Component
@RequiredArgsConstructor
class AreaPeople {

    private final NamedParameterJdbcTemplate jdbc;

    /** The people of {@code areaId}, at {@code branchId}, except {@code except} (who did it). */
    List<UUID> of(UUID areaId, UUID branchId, UUID except) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("areaId", areaId)
                .addValue("branchId", branchId)
                .addValue("except", except);
        List<UUID> people = jdbc.queryForList("""
                SELECT ua.user_id FROM user_areas ua JOIN users u ON u.id = ua.user_id
                WHERE ua.area_id = :areaId AND u.is_active AND u.id IS DISTINCT FROM :except
                """, params, UUID.class);
        if (!people.isEmpty()) {
            return people;
        }
        return jdbc.queryForList("""
                SELECT ub.user_id FROM user_branches ub JOIN users u ON u.id = ub.user_id
                WHERE ub.branch_id = :branchId AND u.is_active AND u.id IS DISTINCT FROM :except
                  AND NOT EXISTS (SELECT 1 FROM user_areas ua JOIN areas a ON a.id = ua.area_id
                                  WHERE ua.user_id = ub.user_id AND a.branch_id = :branchId)
                """, params, UUID.class);
    }

    /** Their name, or their username when they gave none. */
    String name(UUID userId) {
        if (userId == null) {
            return null;
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT NULLIF(TRIM(COALESCE(first_name, '') || ' ' || COALESCE(last_name, '')), '') AS full_name, username
                FROM users WHERE id = :id
                """, new MapSqlParameterSource("id", userId));
        if (found.isEmpty()) {
            return null;
        }
        Object full = found.getFirst().get("full_name");
        return full != null ? (String) full : (String) found.getFirst().get("username");
    }
}
