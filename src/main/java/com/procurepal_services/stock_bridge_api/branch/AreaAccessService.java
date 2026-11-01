package com.procurepal_services.stock_bridge_api.branch;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which areas of a branch a person works in (STOCK_AREAS_PART_3.md, D-E9): every area for the account
 * holder and a role with Work at all branches; otherwise the areas listed for them ({@code user_areas}),
 * and where none of a branch's areas is listed, all of that branch's - so nobody loses anything they could
 * do before areas were assigned, and a new area reaches everyone not deliberately narrowed.
 *
 * <p>Read from the database on every write, like {@link BranchAccessService}, and transactional for the
 * reason it gives.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AreaAccessService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;

    /** Where a person's areas are narrowed: the areas listed, and the branches those are at. */
    public record Limits(boolean everywhere, Set<UUID> areaIds, Set<UUID> limitedBranchIds) {

        public boolean worksIn(UUID branchId, UUID areaId) {
            return everywhere || !limitedBranchIds.contains(branchId) || areaIds.contains(areaId);
        }
    }

    public Limits limits(UUID userId) {
        if (branchAccessService.grants(userId).recordsEverywhere()) {
            return new Limits(true, Set.of(), Set.of());
        }
        Set<UUID> areas = new HashSet<>();
        Set<UUID> branches = new HashSet<>();
        jdbc.query("""
                SELECT ua.area_id, a.branch_id FROM user_areas ua JOIN areas a ON a.id = ua.area_id
                WHERE ua.user_id = :userId
                """, new MapSqlParameterSource("userId", userId), rs -> {
                    areas.add(rs.getObject("area_id", UUID.class));
                    branches.add(rs.getObject("branch_id", UUID.class));
                });
        return new Limits(false, areas, branches);
    }

    /** The open areas of {@code branchId} the person works in, in the branch's order. */
    public List<UUID> workAreaIds(UUID userId, UUID branchId) {
        Limits limits = limits(userId);
        return jdbc.queryForList("""
                SELECT id FROM areas WHERE branch_id = :branchId AND is_active
                ORDER BY sort_order, created_at
                """, new MapSqlParameterSource("branchId", branchId), UUID.class)
                .stream().filter(areaId -> limits.worksIn(branchId, areaId)).toList();
    }

    public boolean mayRecordIn(UUID userId, UUID branchId, UUID areaId) {
        return limits(userId).worksIn(branchId, areaId);
    }

    /** Refuses a write in an area the person doesn't work in (now). 403, like a branch they don't work at. */
    public void requireMayRecordIn(UUID userId, UUID branchId, UUID areaId) {
        if (!mayRecordIn(userId, branchId, areaId)) {
            String name = jdbc.queryForList("SELECT name FROM areas WHERE id = :id", new MapSqlParameterSource("id", areaId),
                    String.class).stream().findFirst().orElse("that area");
            throw new AreaNotAllowedException(name);
        }
    }

    /**
     * What a report covers when the top bar names areas (STOCK_AREAS_PART_3_6.md): {@code requested}, each of
     * which must be one of {@code branchIds}' areas and one the person works in; null or empty is the whole of
     * those branches (null scope). Refused rather than quietly narrowed, like a branch they don't see.
     */
    public AreaScope scope(UUID userId, Collection<UUID> branchIds, Collection<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        Limits limits = limits(userId);
        Set<UUID> areas = new LinkedHashSet<>();
        Set<UUID> originals = new HashSet<>();
        jdbc.query("""
                SELECT id, branch_id, is_original FROM areas WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", requested), rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    UUID branchId = rs.getObject("branch_id", UUID.class);
                    if (!branchIds.contains(branchId)) {
                        throw new AreaNotFoundException();
                    }
                    if (!limits.worksIn(branchId, id)) {
                        throw new AreaNotAllowedException(jdbc.queryForObject("SELECT name FROM areas WHERE id = :id",
                                new MapSqlParameterSource("id", id), String.class));
                    }
                    areas.add(id);
                    if (rs.getBoolean("is_original")) {
                        originals.add(branchId);
                    }
                });
        if (areas.size() != new HashSet<>(requested).size()) {
            throw new AreaNotFoundException();
        }
        return new AreaScope(areas, originals);
    }

    /** Each listed area of a person, for the Users screen; empty = all areas. */
    public List<UUID> listed(UUID userId) {
        return jdbc.queryForList("""
                SELECT ua.area_id FROM user_areas ua JOIN areas a ON a.id = ua.area_id
                WHERE ua.user_id = :userId ORDER BY a.branch_id, a.sort_order, a.created_at
                """, new MapSqlParameterSource("userId", userId), UUID.class);
    }

    /** {@link #listed} for several people at once, for the Users list. */
    public Map<UUID, List<UUID>> listed(Collection<UUID> userIds) {
        Map<UUID, List<UUID>> byUser = new HashMap<>();
        if (userIds.isEmpty()) {
            return byUser;
        }
        jdbc.query("""
                SELECT ua.user_id, ua.area_id FROM user_areas ua JOIN areas a ON a.id = ua.area_id
                WHERE ua.user_id IN (:userIds) ORDER BY a.branch_id, a.sort_order, a.created_at
                """, new MapSqlParameterSource("userIds", userIds), rs -> {
                    byUser.computeIfAbsent(rs.getObject("user_id", UUID.class), key -> new java.util.ArrayList<>())
                            .add(rs.getObject("area_id", UUID.class));
                });
        return byUser;
    }

    /**
     * Replaces the areas a person is limited to (empty = all areas). Every one must be the company's;
     * refused otherwise, like a branch that isn't.
     */
    @Transactional
    public void assign(UUID clientId, UUID userId, Collection<UUID> areaIds) {
        Set<UUID> wanted = new LinkedHashSet<>(areaIds);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("clientId", clientId);
        if (!wanted.isEmpty()) {
            Long ours = jdbc.queryForObject("SELECT COUNT(*) FROM areas WHERE client_id = :clientId AND id IN (:ids)",
                    new MapSqlParameterSource(params.getValues()).addValue("ids", wanted), Long.class);
            if (ours == null || ours != wanted.size()) {
                throw new AreaNotFoundException();
            }
        }
        jdbc.update("DELETE FROM user_areas WHERE user_id = :userId", params);
        for (UUID areaId : wanted) {
            jdbc.update("INSERT INTO user_areas (client_id, user_id, area_id) VALUES (:clientId, :userId, :areaId)",
                    new MapSqlParameterSource(params.getValues()).addValue("areaId", areaId));
        }
    }
}
