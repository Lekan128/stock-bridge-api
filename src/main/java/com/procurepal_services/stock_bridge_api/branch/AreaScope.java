package com.procurepal_services.stock_bridge_api.branch;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * The areas a report covers (STOCK_AREAS_PART_3_6.md): what the top bar says the person is looking at -
 * one area, or the areas they work in. {@code null} everywhere means the whole branch, as before areas. A
 * movement with no area is the branch's original area's (D-E3), so a branch whose original area is chosen
 * counts those rows too ({@code originalBranchIds}).
 */
public record AreaScope(Set<UUID> areaIds, Set<UUID> originalBranchIds) {

    /** What an empty list binds to: Postgres refuses {@code IN ()}, and no row has this id. */
    static final List<UUID> NOTHING = List.of(new UUID(0, 0));

    public static boolean everywhere(AreaScope scope) {
        return scope == null;
    }

    /** The areas, for an {@code IN} - never empty. */
    public static Collection<UUID> areaIds(AreaScope scope) {
        return scope == null || scope.areaIds.isEmpty() ? NOTHING : scope.areaIds;
    }

    /** The branches whose original area is chosen, for an {@code IN} - never empty. */
    public static Collection<UUID> originalBranchIds(AreaScope scope) {
        return scope == null || scope.originalBranchIds.isEmpty() ? NOTHING : scope.originalBranchIds;
    }

    /**
     * " AND (the row is in one of the areas)" for SQL over stock_movements (or anything with area_id and
     * branch_id), {@code alias} being "m." or "". Nothing at all when the scope is everywhere.
     */
    public static String sql(AreaScope scope, String alias) {
        if (scope == null) {
            return "";
        }
        return " AND (" + alias + "area_id IN (:scopeAreaIds) OR (" + alias + "area_id IS NULL AND " + alias
                + "branch_id IN (:scopeOriginalBranchIds)))";
    }

    /** The parameters {@link #sql} names. */
    public static MapSqlParameterSource bind(AreaScope scope, MapSqlParameterSource params) {
        if (scope != null) {
            params.addValue("scopeAreaIds", areaIds(scope)).addValue("scopeOriginalBranchIds", originalBranchIds(scope));
        }
        return params;
    }
}
