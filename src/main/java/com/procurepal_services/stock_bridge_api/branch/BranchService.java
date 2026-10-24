package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.entity.Branch;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.BranchRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A company's branches: listing them for a person, adding, renaming, making one the main branch,
 * closing and reopening (MULTI_BRANCH_UX_PLAN.md sections 5.1 and 5.11), and who works where (5.10).
 *
 * <h2>The second branch (D-B13)</h2>
 * A one-branch company's figures live on its products; from its second branch on they live on
 * {@code branch_stock}. {@link #add} makes that switch in the same transaction that adds the branch:
 * every product's figure is copied into the main branch's row, and everybody who works nowhere in
 * particular is written down as working at the main branch, so moving the main branch later moves
 * nobody. The company's products are locked first, in id order - the order every stock write locks
 * them in - so a write in flight finishes before the copy reads its product, and one arriving after
 * waits and then sees two branches. Without that, a write between the copy's read and its upsert
 * would be overwritten with the figure from before it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchService {

    private final BranchRepository branchRepository;
    private final ClientRepository clientRepository;
    private final BranchAccessService branchAccessService;
    private final BranchStockService branchStockService;
    private final NamedParameterJdbcTemplate jdbc;

    /** {@code pilot} until multiple branches launch; see {@link #mayAddBranch}. */
    @Value("${app.branches.rollout:pilot}")
    private String rollout;

    @Transactional(readOnly = true)
    public BranchListResponse list(UUID userId) {
        UUID clientId = requireTenantId();
        Set<UUID> visible = new LinkedHashSet<>(branchAccessService.viewBranchIds(userId));
        Set<UUID> writable = new LinkedHashSet<>(branchAccessService.writeBranchIds(userId));
        boolean manages = hasPermission(userId, "MANAGE_BRANCHES");
        Map<UUID, Totals> totals = manages ? totals(clientId) : Map.of();
        BranchAccessService.Grants grants = branchAccessService.grants(userId);

        List<BranchResponse> branches = branchRepository.findAllByClientId(clientId).stream()
                .filter(branch -> visible.contains(branch.getId()))
                .sorted(Comparator.comparing((Branch branch) -> !branch.isDefaultBranch())
                        .thenComparing(branch -> !branch.isActive())
                        .thenComparing(Branch::getName, String.CASE_INSENSITIVE_ORDER))
                .map(branch -> toResponse(branch, writable.contains(branch.getId()), totals.get(branch.getId()), manages))
                .toList();
        boolean multiBranch = branchStockService.isMultiBranch(clientId);
        List<BranchListResponse.BranchName> sendTo = !multiBranch || !hasPermission(userId, "SEND_STOCK") ? List.of()
                : branchRepository.findAllByClientIdAndActiveTrueOrderByNameAsc(clientId).stream()
                        .map(branch -> new BranchListResponse.BranchName(branch.getId(), branch.getName()))
                        .toList();
        return new BranchListResponse(
                branches,
                multiBranch,
                grants.seesEverywhere(),
                grants.recordsEverywhere(),
                manages && mayAddBranch(clientId),
                sendTo);
    }

    /** {@link BranchController#setThresholds}: a branch the person sees, of a multi-branch company. */
    @Transactional
    public int setThresholds(UUID branchId, BranchController.BulkThresholdRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        if (!branchStockService.isMultiBranch(clientId)) {
            throw new BranchChangeRefusedException("Your company has one branch: set each product's own low-stock level instead.");
        }
        if (!branchAccessService.viewBranchIds(userId).contains(branchId)) {
            throw new BranchNotFoundException();
        }
        return branchStockService.setThresholds(clientId, branchId, new java.util.LinkedHashSet<>(request.productIds()),
                request.lowStockThreshold());
    }

    /**
     * Adds a branch; the company's second also switches it to per-branch figures (class javadoc). Refused
     * in the pilot for a company support hasn't enabled, so a company is never left half-switched.
     */
    @Transactional
    public BranchResponse add(BranchRequest request) {
        UUID clientId = requireTenantId();
        String name = requireName(request.name());
        // One add at a time per company, so two can't both find it single-branch and both switch it.
        // NO KEY UPDATE, not UPDATE: stock writes take a key-share lock on the company row (their rows
        // reference it) while holding a product; FOR UPDATE here would deadlock with them.
        jdbc.queryForList("SELECT id FROM clients WHERE id = :clientId FOR NO KEY UPDATE",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
        if (!mayAddBranch(clientId)) {
            throw new BranchChangeRefusedException(
                    "Adding branches isn't switched on for your company yet. Talk to us on WhatsApp to join the pilot.");
        }
        requireNameFree(clientId, name, null);

        if (!branchStockService.isMultiBranch(clientId)) {
            becomeMultiBranch(clientId);
        }
        Branch branch = branchRepository.save(Branch.builder()
                .name(name)
                .phone(blankToNull(request.phone()))
                .address(blankToNull(request.address()))
                .defaultBranch(false)
                .active(true)
                .build());
        log.info("Branch added: {} '{}' for company {}", branch.getId(), name, clientId);
        return toResponse(branch, true, null, true);
    }

    @Transactional
    public BranchResponse update(UUID branchId, BranchRequest request) {
        UUID clientId = requireTenantId();
        Branch branch = requireBranch(branchId, clientId);
        if (request.name() != null) {
            String name = requireName(request.name());
            requireNameFree(clientId, name, branchId);
            branch.setName(name);
        }
        if (request.phone() != null) {
            branch.setPhone(blankToNull(request.phone()));
        }
        if (request.address() != null) {
            branch.setAddress(blankToNull(request.address()));
        }
        return toResponse(branch, true, null, true);
    }

    /**
     * Makes {@code branchId} the main branch. Two statements in this order: the one-default-per-company
     * index would refuse the new main while the old one is still marked.
     */
    @Transactional
    public BranchResponse makeMain(UUID branchId) {
        UUID clientId = requireTenantId();
        Branch branch = requireBranch(branchId, clientId);
        if (!branch.isActive()) {
            throw new BranchChangeRefusedException("Reopen " + branch.getName() + " before making it the main branch.");
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId);
        jdbc.update("UPDATE branches SET is_default = FALSE WHERE client_id = :clientId AND is_default AND id <> :branchId",
                params);
        jdbc.update("UPDATE branches SET is_default = TRUE WHERE id = :branchId", params);
        // The entity loaded above predates both statements; say what is now true.
        branch.setDefaultBranch(true);
        return toResponse(branch, true, null, true);
    }

    /**
     * Closes a branch: it leaves every chooser and keeps its history (UX section 5.11). Refused, with what
     * to do instead, while it is the main branch, still holds stock, or is the only place somebody works.
     */
    @Transactional
    public BranchResponse close(UUID branchId) {
        UUID clientId = requireTenantId();
        Branch branch = requireBranch(branchId, clientId);
        if (branch.isDefaultBranch()) {
            throw new BranchChangeRefusedException(
                    branch.getName() + " is the main branch. Make another branch the main branch first.");
        }
        if (!branch.isActive()) {
            return toResponse(branch, false, null, true);
        }
        MapSqlParameterSource params = new MapSqlParameterSource("branchId", branchId);
        Long stocked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM branch_stock WHERE branch_id = :branchId AND quantity_on_hand <> 0", params, Long.class);
        if (stocked != null && stocked > 0) {
            throw new BranchChangeRefusedException(branch.getName() + " still has " + stocked
                    + (stocked == 1 ? " product" : " products") + " in stock. Count them to zero first.");
        }
        List<String> stranded = jdbc.queryForList("""
                SELECT COALESCE(NULLIF(TRIM(CONCAT_WS(' ', u.first_name, u.last_name)), ''), u.username)
                FROM users u
                WHERE u.is_active AND EXISTS (SELECT 1 FROM user_branches ub WHERE ub.user_id = u.id AND ub.branch_id = :branchId)
                  AND NOT EXISTS (
                      SELECT 1 FROM user_branches other JOIN branches b ON b.id = other.branch_id
                      WHERE other.user_id = u.id AND other.branch_id <> :branchId AND b.is_active)
                ORDER BY 1
                """, params, String.class);
        if (!stranded.isEmpty()) {
            throw new BranchChangeRefusedException(String.join(", ", stranded)
                    + (stranded.size() == 1 ? " only works" : " only work") + " at " + branch.getName()
                    + ". Give them another branch first.");
        }
        branch.setActive(false);
        branch.setClosedAt(OffsetDateTime.now());
        log.info("Branch closed: {} '{}' for company {}", branchId, branch.getName(), clientId);
        return toResponse(branch, false, null, true);
    }

    @Transactional
    public BranchResponse reopen(UUID branchId) {
        UUID clientId = requireTenantId();
        Branch branch = requireBranch(branchId, clientId);
        branch.setActive(true);
        branch.setClosedAt(null);
        return toResponse(branch, true, null, true);
    }

    /**
     * Where a person works (UX section 5.10), replacing what was there. At least one open branch of this
     * company. Ignored in effect - though kept - for a role that works at every branch.
     */
    @Transactional
    public void assign(UUID userId, Collection<UUID> branchIds) {
        UUID clientId = requireTenantId();
        Set<UUID> wanted = new LinkedHashSet<>(branchIds);
        if (wanted.isEmpty()) {
            throw new BranchChangeRefusedException("Choose at least one branch, or deactivate the user.");
        }
        List<UUID> open = jdbc.queryForList(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_active AND id IN (:ids)",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("ids", wanted), UUID.class);
        if (open.size() != wanted.size()) {
            throw new BranchNotFoundException();
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("clientId", clientId);
        jdbc.update("DELETE FROM user_branches WHERE user_id = :userId", params);
        for (UUID branchId : wanted) {
            jdbc.update("INSERT INTO user_branches (client_id, user_id, branch_id) VALUES (:clientId, :userId, :branchId)",
                    new MapSqlParameterSource(params.getValues()).addValue("branchId", branchId));
        }
    }

    /** Forgets where a person works - for someone whose role now puts them at every branch. */
    @Transactional
    public void clearAssignments(UUID userId) {
        jdbc.update("DELETE FROM user_branches WHERE user_id = :userId", new MapSqlParameterSource("userId", userId));
    }

    /** The branches a person is written down as working at - empty for nobody in particular. */
    @Transactional(readOnly = true)
    public List<UUID> assignedBranchIds(UUID userId) {
        return jdbc.queryForList("SELECT branch_id FROM user_branches WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), UUID.class);
    }

    /**
     * Whether this company may add a branch now: launched for everyone, or enabled for it by support in
     * the pilot. A company that already has two is not affected either way - this only gates adding.
     */
    public boolean mayAddBranch(UUID clientId) {
        Client client = clientRepository.findById(clientId).orElse(null);
        if (client == null || client.canSell()) {
            // Marketplace sellers ship from one branch in v1 (decision D-U7): their availability and
            // dispatch read one figure, and a second branch would split it. Phase 5 builds that.
            return false;
        }
        return "open".equalsIgnoreCase(rollout) || client.isBranchesEnabled();
    }

    /** Support letting a company into the pilot, or taking it out (it keeps any branches it has). */
    @Transactional
    public void setEnabledBySupport(UUID clientId, boolean enabled) {
        Client client = clientRepository.findById(clientId).orElseThrow(BranchNotFoundException::new);
        client.setBranchesEnabled(enabled);
        log.info("Multiple branches {} for company {} by support", enabled ? "enabled" : "disabled", clientId);
    }

    private void becomeMultiBranch(UUID clientId) {
        MapSqlParameterSource params = new MapSqlParameterSource("clientId", clientId);
        UUID main = jdbc.queryForObject(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_default", params, UUID.class);
        params.addValue("main", main);
        jdbc.queryForList("SELECT id FROM products WHERE client_id = :clientId ORDER BY id FOR UPDATE", params, UUID.class);
        int copied = jdbc.update("""
                INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
                SELECT p.client_id, :main, p.id, p.quantity_on_hand FROM products p WHERE p.client_id = :clientId
                ON CONFLICT (branch_id, product_id) DO UPDATE SET quantity_on_hand = EXCLUDED.quantity_on_hand
                """, params);
        int placed = jdbc.update("""
                INSERT INTO user_branches (client_id, user_id, branch_id)
                SELECT u.client_id, u.id, :main FROM users u
                WHERE u.client_id = :clientId
                  AND NOT EXISTS (SELECT 1 FROM user_branches ub WHERE ub.user_id = u.id)
                """, params);
        log.info("Company {} now has more than one branch: {} product figures copied to its main branch, {} people placed there",
                clientId, copied, placed);
    }

    private record Totals(long productsInStock, BigDecimal stockWorth, long people) {
    }

    private Map<UUID, Totals> totals(UUID clientId) {
        MapSqlParameterSource params = new MapSqlParameterSource("clientId", clientId);
        String sql = branchStockService.isMultiBranch(clientId)
                ? """
                  SELECT bs.branch_id, COUNT(*) FILTER (WHERE bs.quantity_on_hand > 0) AS products,
                         COALESCE(SUM(bs.quantity_on_hand * COALESCE(p.cost_price, 0)) FILTER (WHERE bs.quantity_on_hand > 0), 0) AS worth
                  FROM branch_stock bs JOIN products p ON p.id = bs.product_id
                  WHERE bs.client_id = :clientId
                  GROUP BY bs.branch_id
                  """
                : """
                  SELECT b.id AS branch_id, COUNT(p.id) FILTER (WHERE p.quantity_on_hand > 0) AS products,
                         COALESCE(SUM(p.quantity_on_hand * COALESCE(p.cost_price, 0)) FILTER (WHERE p.quantity_on_hand > 0), 0) AS worth
                  FROM branches b LEFT JOIN products p ON p.client_id = b.client_id
                  WHERE b.client_id = :clientId AND b.is_default
                  GROUP BY b.id
                  """;
        // Who works there: their own Works at. People whose role works everywhere are not counted at each.
        Map<UUID, Long> people = new HashMap<>();
        jdbc.query("""
                SELECT ub.branch_id, COUNT(*) AS people FROM user_branches ub JOIN users u ON u.id = ub.user_id
                WHERE ub.client_id = :clientId AND u.is_active GROUP BY ub.branch_id
                """, params, rs -> {
                    people.put(rs.getObject("branch_id", UUID.class), rs.getLong("people"));
                });
        Map<UUID, Totals> totals = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            UUID branch = rs.getObject("branch_id", UUID.class);
            totals.put(branch, new Totals(rs.getLong("products"), rs.getBigDecimal("worth"), people.getOrDefault(branch, 0L)));
        });
        for (Map.Entry<UUID, Long> entry : people.entrySet()) {
            totals.putIfAbsent(entry.getKey(), new Totals(0, BigDecimal.ZERO, entry.getValue()));
        }
        return totals;
    }

    private static BranchResponse toResponse(Branch branch, boolean canRecord, Totals totals, boolean manages) {
        Totals shown = manages ? (totals == null ? new Totals(0, BigDecimal.ZERO, 0) : totals) : null;
        return new BranchResponse(
                branch.getId(),
                branch.getName(),
                branch.getPhone(),
                branch.isDefaultBranch(),
                branch.isActive(),
                branch.getClosedAt(),
                canRecord && branch.isActive(),
                shown == null ? null : shown.productsInStock(),
                shown == null ? null : shown.stockWorth(),
                branch.getAddress(),
                shown == null ? null : shown.people());
    }

    private boolean hasPermission(UUID userId, String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT u.is_root OR EXISTS (
                    SELECT 1 FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                    WHERE rp.role_id = u.role_id AND p.code = :code)
                FROM users u WHERE u.id = :userId
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("code", code), Boolean.class));
    }

    private Branch requireBranch(UUID branchId, UUID clientId) {
        return branchRepository.findByIdAndClientId(branchId, clientId).orElseThrow(BranchNotFoundException::new);
    }

    private void requireNameFree(UUID clientId, String name, UUID exceptBranchId) {
        boolean taken = branchRepository.findAllByClientId(clientId).stream()
                .anyMatch(other -> !other.getId().equals(exceptBranchId) && other.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new BranchChangeRefusedException("There's already a branch called " + name + ".");
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BranchChangeRefusedException("Give the branch a name.");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set");
        }
        return tenantId;
    }
}
