package com.procurepal_services.stock_bridge_api.branch;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Where a person may record and what they may see (MULTI_BRANCH_BUILD_PLAN.md D-B9, decided D-U3).
 *
 * <ul>
 *   <li><b>Record at</b> ({@link #writeBranchIds}): every open branch for the account holder and for a
 *       role with {@code WORK_AT_ALL_BRANCHES} (owners); otherwise the branches they work at
 *       ({@code user_branches}), and the main branch for somebody with none - which is everybody at a
 *       one-branch company.</li>
 *   <li><b>See</b> ({@link #viewBranchIds}): every branch, closed ones included, for those plus a role
 *       with {@code VIEW_ALL_BRANCHES} (read-only); otherwise exactly where they record.</li>
 * </ul>
 * Read from the database on every call, never from the access token, for the reason
 * {@code StockWritePermissionGuard} gives: a phone sends writes made offline after a person's access
 * changed, and the access they have now is the one that counts.
 *
 * <h2>Transactional, though every method is a few lines of SQL</h2>
 * The app runs with open-in-view (application.yml): a request's EntityManager, and the connection it
 * acquired for the first transactional read, is held for the whole request. SQL issued OUTSIDE a
 * transaction cannot use that held connection and borrows a second one - so a stock write that checked
 * its branch from the controller needed two connections at once, and two writes racing for the same
 * product (or one beside a held transaction and a scheduled job) emptied the pool and waited thirty
 * seconds. Inside a transaction the same SQL runs on the request's one connection, or on the caller's
 * transaction's when there is one.
 */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
@RequiredArgsConstructor
public class BranchAccessService {

    static final String WORK_AT_ALL_BRANCHES = "WORK_AT_ALL_BRANCHES";
    static final String VIEW_ALL_BRANCHES = "VIEW_ALL_BRANCHES";

    private final NamedParameterJdbcTemplate jdbc;

    /** What a person's access rests on, read once. */
    record Grants(UUID clientId, boolean recordsEverywhere, boolean seesEverywhere) {
    }

    public List<UUID> writeBranchIds(UUID userId) {
        Grants grants = grants(userId);
        if (grants.recordsEverywhere()) {
            return openBranches(grants.clientId());
        }
        List<UUID> assigned = jdbc.queryForList("""
                SELECT b.id FROM user_branches ub JOIN branches b ON b.id = ub.branch_id
                WHERE ub.user_id = :userId AND b.is_active
                ORDER BY b.is_default DESC, b.name
                """, new MapSqlParameterSource("userId", userId), UUID.class);
        if (!assigned.isEmpty() || hasAssignments(userId)) {
            // Assignments only to closed branches mean nowhere, not the main branch: the person was
            // placed deliberately, and recording somewhere they weren't placed would be a guess.
            return assigned;
        }
        return jdbc.queryForList(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_default AND is_active",
                new MapSqlParameterSource("clientId", grants.clientId()), UUID.class);
    }

    public List<UUID> viewBranchIds(UUID userId) {
        Grants grants = grants(userId);
        if (grants.seesEverywhere()) {
            return jdbc.queryForList(
                    "SELECT id FROM branches WHERE client_id = :clientId ORDER BY is_default DESC, name",
                    new MapSqlParameterSource("clientId", grants.clientId()), UUID.class);
        }
        return writeBranchIds(userId);
    }

    /**
     * The open branches this person sees - what stock figures and low-stock judgements cover. A closed
     * branch holds nothing (it can't close otherwise), so counting it would call every product with a
     * level "low" there. Reports keep {@link #viewBranchIds}: a closed branch's history still happened.
     */
    public List<UUID> openViewBranchIds(UUID userId) {
        Grants grants = grants(userId);
        if (grants.seesEverywhere()) {
            return jdbc.queryForList(
                    "SELECT id FROM branches WHERE client_id = :clientId AND is_active ORDER BY is_default DESC, name",
                    new MapSqlParameterSource("clientId", grants.clientId()), UUID.class);
        }
        return writeBranchIds(userId);
    }

    /** {@link #reportScope} for stock figures: with no branch asked for, only the open ones. */
    public List<UUID> stockScope(UUID userId, UUID requested) {
        return requested == null ? openViewBranchIds(userId) : reportScope(userId, requested);
    }

    /**
     * The branches a report covers (D-B11): the one asked for, if this person sees it, or else every
     * branch they see. Never one they don't - D-U3 held on the server, not only in the app.
     */
    public List<UUID> reportScope(UUID userId, UUID requested) {
        List<UUID> visible = viewBranchIds(userId);
        if (requested == null) {
            return visible;
        }
        if (!visible.contains(requested)) {
            throw new BranchNotFoundException();
        }
        return List.of(requested);
    }

    public boolean mayRecordAt(UUID userId, UUID branchId) {
        return writeBranchIds(userId).contains(branchId);
    }

    /** Whether a role puts whoever holds it at every branch - decided before the person exists. */
    public boolean roleRecordsEverywhere(UUID roleId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                               WHERE rp.role_id = :roleId AND p.code = :workAll)
                """, new MapSqlParameterSource().addValue("roleId", roleId).addValue("workAll", WORK_AT_ALL_BRANCHES),
                Boolean.class));
    }

    /** Whether a person's role puts them at every branch - the Users screen says so instead of ticks. */
    public boolean recordsEverywhere(UUID userId) {
        return grants(userId).recordsEverywhere();
    }

    /**
     * Refuses a write at a branch the person doesn't work at (now). 403, like a permission taken away:
     * the phone shows it as "needs you" with the reason, and nothing is recorded.
     */
    public void requireMayRecordAt(UUID userId, UUID branchId) {
        if (!mayRecordAt(userId, branchId)) {
            String name = jdbc.queryForList(
                    "SELECT name FROM branches WHERE id = :id", new MapSqlParameterSource("id", branchId), String.class)
                    .stream().findFirst().orElse("that branch");
            throw new BranchNotAllowedException(name);
        }
    }

    Grants grants(UUID userId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT u.client_id, u.is_root,
                       EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                               WHERE rp.role_id = u.role_id AND p.code = :workAll) AS work_all,
                       EXISTS (SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                               WHERE rp.role_id = u.role_id AND p.code = :viewAll) AS view_all
                FROM users u WHERE u.id = :userId
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("workAll", WORK_AT_ALL_BRANCHES)
                .addValue("viewAll", VIEW_ALL_BRANCHES));
        boolean root = Boolean.TRUE.equals(row.get("is_root"));
        boolean workAll = root || Boolean.TRUE.equals(row.get("work_all"));
        return new Grants((UUID) row.get("client_id"), workAll, workAll || Boolean.TRUE.equals(row.get("view_all")));
    }

    private List<UUID> openBranches(UUID clientId) {
        return jdbc.queryForList(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_active ORDER BY is_default DESC, name",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
    }

    private boolean hasAssignments(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_branches WHERE user_id = :userId)",
                new MapSqlParameterSource("userId", userId), Boolean.class));
    }
}
