package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.branch.dto.AreaRequest;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.AreaUpdateRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * A branch's areas (STOCK_AREAS_PLAN.md sections 4.1 and 5.8, STOCK_AREAS_PART_1.md): adding them,
 * renaming, closing, reopening, and where deliveries arrive. Areas are a shared record, so the app only
 * changes them online (Pattern C.5). Every refusal is a sentence the owner can act on, like the branch
 * screens'.
 *
 * <p>Adding a branch's second area switches it to per-area figures (BranchStockService's class javadoc):
 * everything it holds is copied into the area it already has, under the products' locks, in the same
 * transaction - so a stock write either finished before the switch (and its figure is copied) or waits
 * for it (and lands in an area).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AreaService {

    /** More than this many areas in one branch is a warehouse's bins, which areas are not (rule 2). */
    static final int MAX_AREAS_PER_BRANCH = 20;

    /**
     * {@code open} (the default): every company may add areas - they are free, so there is no one to
     * approve (owner, 2026-10-30). {@code off}: nobody may, the server-wide switch for hiding the feature
     * without a deploy if something goes wrong. See {@link #mayAddArea}.
     */
    @Value("${app.areas.rollout:open}")
    private String rollout;

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchStockService branchStockService;
    private final BranchAccessService branchAccessService;
    private final ClientRepository clientRepository;

    /** One area as this service reads it. */
    private record AreaRow(
            UUID id, UUID branchId, String name, boolean keepsStock, boolean delivery, boolean original,
            boolean active, OffsetDateTime closedAt, boolean shortIsUsed, boolean confirms) {
    }

    // ---------------------------------------------------------------------------------------- reading

    /**
     * Every area of the company's branches, by branch, open ones first. {@code withCounts}: how many
     * products each holds, for people who manage branches, at a branch with more than one area.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<AreaResponse>> areasByBranch(UUID clientId, boolean withCounts) {
        MapSqlParameterSource params = new MapSqlParameterSource("clientId", clientId);
        Map<UUID, Long> counts = new HashMap<>();
        if (withCounts) {
            jdbc.query("""
                    SELECT area_id, COUNT(*) AS held FROM area_stock
                    WHERE client_id = :clientId AND quantity_on_hand > 0 GROUP BY area_id
                    """, params, rs -> {
                        counts.put(rs.getObject("area_id", UUID.class), rs.getLong("held"));
                    });
        }
        Set<UUID> areaMode = new HashSet<>(jdbc.queryForList("""
                SELECT branch_id FROM areas WHERE client_id = :clientId GROUP BY branch_id HAVING COUNT(*) > 1
                """, params, UUID.class));
        Map<UUID, List<AreaResponse>> byBranch = new HashMap<>();
        for (AreaRow area : rows("WHERE a.client_id = :clientId", params)) {
            Long held = withCounts && areaMode.contains(area.branchId()) && area.keepsStock()
                    ? counts.getOrDefault(area.id(), 0L)
                    : null;
            byBranch.computeIfAbsent(area.branchId(), key -> new ArrayList<>()).add(toResponse(area, held));
        }
        return byBranch;
    }

    /**
     * Whether this company may add areas now: every company, unless the server-wide switch is off. Never a
     * marketplace seller yet: its dispatch and availability read one figure, as with branches (D-U7). Only
     * gates adding - a branch that already has areas keeps them.
     */
    @Transactional(readOnly = true)
    public boolean mayAddArea(UUID clientId) {
        if ("off".equalsIgnoreCase(rollout)) {
            return false;
        }
        Client client = clientRepository.findById(clientId).orElse(null);
        return client != null && !client.canSell();
    }

    // --------------------------------------------------------------------------------------- changing

    /** Adds {@code request}'s areas to the branch, switching it to per-area figures the first time. */
    public List<AreaResponse> add(UUID branchId, AreaRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        // One change to a company's areas at a time, so two adds can't both find a branch with one area
        // and both copy its figures. NO KEY UPDATE, not UPDATE: stock writes take a key-share lock on the
        // company row while holding a product, and FOR UPDATE would deadlock with them (branch lesson).
        jdbc.queryForList("SELECT id FROM clients WHERE id = :clientId FOR NO KEY UPDATE",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
        Map<String, Object> branch = requireBranch(branchId, clientId, userId);
        if (!Boolean.TRUE.equals(branch.get("is_active"))) {
            throw new BranchChangeRefusedException("Reopen " + branch.get("name") + " before adding areas to it.");
        }
        if (!mayAddArea(clientId)) {
            throw new BranchChangeRefusedException(
                    "Adding areas isn't available for your company right now. Talk to us on WhatsApp.");
        }

        List<AreaRow> existing = rows("WHERE a.branch_id = :branchId", new MapSqlParameterSource("branchId", branchId));
        if (existing.size() + request.areas().size() > MAX_AREAS_PER_BRANCH) {
            throw new BranchChangeRefusedException("A branch can have up to " + MAX_AREAS_PER_BRANCH + " areas.");
        }
        Set<String> taken = new HashSet<>();
        existing.forEach(area -> taken.add(key(area.name())));
        List<String> names = new ArrayList<>();
        for (AreaRequest.NewArea area : request.areas()) {
            String name = requireName(area.name());
            if (!taken.add(key(name))) {
                throw new BranchChangeRefusedException("There's already an area called " + name + " here.");
            }
            names.add(name);
        }

        if (existing.size() == 1) {
            branchStockService.becomeAreaMode(clientId, branchId, existing.getFirst().id());
        }
        int order = existing.size();
        List<AreaResponse> added = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            AreaRequest.NewArea wanted = request.areas().get(i);
            boolean keepsStock = wanted.keepsStock() == null || wanted.keepsStock();
            boolean shortIsUsed = keepsStock && Boolean.TRUE.equals(wanted.shortIsUsed());
            UUID id = jdbc.queryForObject("""
                    INSERT INTO areas (client_id, branch_id, name, keeps_stock, short_is_used, confirms, sort_order)
                    VALUES (:clientId, :branchId, :name, :keepsStock, :shortIsUsed, :confirms, :order)
                    RETURNING id
                    """, new MapSqlParameterSource()
                            .addValue("clientId", clientId)
                            .addValue("branchId", branchId)
                            .addValue("name", names.get(i))
                            .addValue("keepsStock", keepsStock)
                            .addValue("shortIsUsed", shortIsUsed)
                            .addValue("confirms", Boolean.TRUE.equals(wanted.confirms()))
                            .addValue("order", order++), UUID.class);
            added.add(toResponse(requireArea(id, clientId), keepsStock ? 0L : null));
        }
        log.info("Areas added to branch {} of company {}: {}", branchId, clientId, names);
        return added;
    }

    /** Rename, change what kind of area it is, or make it where deliveries arrive. */
    public AreaResponse update(UUID areaId, AreaUpdateRequest request, UUID userId) {
        UUID clientId = requireTenantId();
        AreaRow area = requireArea(areaId, clientId);
        requireBranch(area.branchId(), clientId, userId);
        MapSqlParameterSource params = new MapSqlParameterSource("id", areaId);

        if (request.name() != null) {
            String name = requireName(request.name());
            boolean clash = rows("WHERE a.branch_id = :branchId", new MapSqlParameterSource("branchId", area.branchId()))
                    .stream()
                    .anyMatch(other -> !other.id().equals(areaId) && key(other.name()).equals(key(name)));
            if (clash) {
                throw new BranchChangeRefusedException("There's already an area called " + name + " here.");
            }
            jdbc.update("UPDATE areas SET name = :name WHERE id = :id", params.addValue("name", name));
        }

        if (request.keepsStock() != null && request.keepsStock() != area.keepsStock()) {
            if (!request.keepsStock()) {
                if (area.delivery()) {
                    throw new BranchChangeRefusedException("Deliveries arrive in " + area.name()
                            + ", so it keeps stock. Choose another area for deliveries first.");
                }
                if (area.original()) {
                    throw new BranchChangeRefusedException(
                            area.name() + " holds this branch's stock history, so it keeps stock.");
                }
                requireEmpty(area, "Count it to zero first, then change it.");
            }
            jdbc.update("UPDATE areas SET keeps_stock = :keepsStock WHERE id = :id",
                    params.addValue("keepsStock", request.keepsStock()));
        }

        // Stored ↔ worked with (Part 3.5): any time, it only changes what later counts mean. An area that uses
        // what it's given has no counts, so it's never "worked with".
        boolean keepsNow = request.keepsStock() != null ? request.keepsStock() : area.keepsStock();
        boolean shortIsUsed = keepsNow && (request.shortIsUsed() != null ? request.shortIsUsed() : area.shortIsUsed());
        if (shortIsUsed != area.shortIsUsed()) {
            jdbc.update("UPDATE areas SET short_is_used = :shortIsUsed WHERE id = :id",
                    new MapSqlParameterSource("id", areaId).addValue("shortIsUsed", shortIsUsed));
        }

        // Confirms what arrives (Part 4): any time. Moves already waiting still wait for it (§5.8).
        if (request.confirms() != null && request.confirms() != area.confirms()) {
            jdbc.update("UPDATE areas SET confirms = :confirms WHERE id = :id",
                    new MapSqlParameterSource("id", areaId).addValue("confirms", request.confirms()));
        }

        if (Boolean.TRUE.equals(request.delivery()) && !area.delivery()) {
            AreaRow now = requireArea(areaId, clientId);
            if (!now.active()) {
                throw new BranchChangeRefusedException("Reopen " + now.name() + " before deliveries can arrive there.");
            }
            if (!now.keepsStock()) {
                throw new BranchChangeRefusedException(now.name() + " uses stock up as soon as it arrives, so it can't be where deliveries come in.");
            }
            // The old one first: one delivery area per branch is a unique index.
            jdbc.update("UPDATE areas SET is_delivery = FALSE WHERE branch_id = :branchId AND is_delivery",
                    new MapSqlParameterSource("branchId", area.branchId()));
            jdbc.update("UPDATE areas SET is_delivery = TRUE WHERE id = :id", params);
        }
        return toResponse(requireArea(areaId, clientId), null);
    }

    /** Closes an empty area. Its history keeps its name; nothing new is recorded there. */
    public AreaResponse close(UUID areaId, UUID userId) {
        UUID clientId = requireTenantId();
        AreaRow area = requireArea(areaId, clientId);
        requireBranch(area.branchId(), clientId, userId);
        if (!area.active()) {
            return toResponse(area, null);
        }
        if (area.delivery()) {
            throw new BranchChangeRefusedException("Deliveries arrive in " + area.name()
                    + ". Choose another area for deliveries before closing it.");
        }
        requireEmpty(area, "Count it to zero first, then close it.");
        jdbc.update("UPDATE areas SET is_active = FALSE, closed_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", areaId));
        log.info("Area {} '{}' closed at branch {}", areaId, area.name(), area.branchId());
        return toResponse(requireArea(areaId, clientId), null);
    }

    public AreaResponse reopen(UUID areaId, UUID userId) {
        UUID clientId = requireTenantId();
        AreaRow area = requireArea(areaId, clientId);
        requireBranch(area.branchId(), clientId, userId);
        jdbc.update("UPDATE areas SET is_active = TRUE, closed_at = NULL WHERE id = :id",
                new MapSqlParameterSource("id", areaId));
        return toResponse(requireArea(areaId, clientId), null);
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** Refuses a change to an area that still holds stock, saying how much and what to do. */
    private void requireEmpty(AreaRow area, String wayOut) {
        Long held = jdbc.queryForObject("""
                SELECT COUNT(*) FROM area_stock WHERE area_id = :id AND quantity_on_hand <> 0
                """, new MapSqlParameterSource("id", area.id()), Long.class);
        if (held != null && held > 0) {
            throw new BranchChangeRefusedException(area.name() + " still has " + held + " "
                    + (held == 1 ? "item" : "items") + ". " + wayOut);
        }
        // A move waiting to be confirmed there, or that left from there, still has somewhere to settle (§5.8).
        Long waiting = jdbc.queryForObject("""
                SELECT COUNT(*) FROM area_moves WHERE status = 'WAITING' AND (to_area_id = :id OR from_area_id = :id)
                """, new MapSqlParameterSource("id", area.id()), Long.class);
        if (waiting != null && waiting > 0) {
            throw new BranchChangeRefusedException(area.name() + " has " + (waiting == 1 ? "a move" : waiting + " moves")
                    + " waiting to be confirmed. Confirm or cancel " + (waiting == 1 ? "it" : "them") + " first.");
        }
    }

    /** The branch, if it is this company's and this person works there; its name and whether it's open. */
    private Map<String, Object> requireBranch(UUID branchId, UUID clientId, UUID userId) {
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT name, is_active FROM branches WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", branchId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new BranchNotFoundException();
        }
        // Checked fresh, never from the token (D-E9): a manager moved off a branch can't change its areas.
        // A closed branch is no one's to record at, so for it the test is that they could see it.
        boolean open = Boolean.TRUE.equals(found.getFirst().get("is_active"));
        boolean allowed = open
                ? branchAccessService.mayRecordAt(userId, branchId)
                : branchAccessService.viewBranchIds(userId).contains(branchId);
        if (!allowed) {
            throw new BranchNotAllowedException((String) found.getFirst().get("name"));
        }
        return found.getFirst();
    }

    private AreaRow requireArea(UUID areaId, UUID clientId) {
        List<AreaRow> found = rows("WHERE a.id = :id AND a.client_id = :clientId",
                new MapSqlParameterSource().addValue("id", areaId).addValue("clientId", clientId));
        if (found.isEmpty()) {
            throw new AreaNotFoundException();
        }
        return found.getFirst();
    }

    private List<AreaRow> rows(String where, MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT a.id, a.branch_id, a.name, a.keeps_stock, a.is_delivery, a.is_original, a.is_active, a.closed_at,
                       a.short_is_used, a.confirms
                FROM areas a
                """ + where + " ORDER BY a.is_active DESC, a.sort_order, a.created_at", params,
                (rs, n) -> new AreaRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("name"),
                        rs.getBoolean("keeps_stock"),
                        rs.getBoolean("is_delivery"),
                        rs.getBoolean("is_original"),
                        rs.getBoolean("is_active"),
                        rs.getObject("closed_at", OffsetDateTime.class),
                        rs.getBoolean("short_is_used"),
                        rs.getBoolean("confirms")));
    }

    private static AreaResponse toResponse(AreaRow area, Long productsInStock) {
        return new AreaResponse(area.id(), area.name(), area.keepsStock(), area.delivery(), area.active(),
                area.closedAt(), productsInStock, null, area.shortIsUsed(), area.confirms());
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new BranchChangeRefusedException("Give the area a name.");
        }
        if (name.length() > 80) {
            throw new BranchChangeRefusedException("Keep the area's name under 80 letters.");
        }
        return name;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set");
        }
        return tenantId;
    }
}
