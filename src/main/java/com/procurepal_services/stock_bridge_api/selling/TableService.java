package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException;
import com.procurepal_services.stock_bridge_api.product.MadeWithException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A branch's tables (V99, selling till Step 7; owner, 2026-11-19). "Add 10" makes the next ten numbers; any table can be
 * renamed (4 becomes Window) or added by name (VIP); the owner's order is the order on the grid. A table is switched
 * off, never deleted, so an old bill keeps its table.
 */
@Service
@RequiredArgsConstructor
public class TableService {

    static final int MAX_TABLES = 200;

    private final NamedParameterJdbcTemplate jdbc;

    public record Table(UUID id, UUID branchId, String name, int sortOrder, boolean active) {}

    /** {@code count}: add that many, numbered on from the highest number there; or {@code name}: add one so named. */
    public record AddTablesRequest(Integer count, String name) {}

    /** Rename it, or switch it on or off. */
    public record TableRequest(String name, Boolean active) {}

    public record OrderRequest(List<UUID> ids) {}

    /** Every branch's tables, in their order; switched-off ones only when {@code withOff}. */
    @Transactional(readOnly = true)
    public Map<UUID, List<Table>> byBranch(UUID clientId, boolean withOff) {
        Map<UUID, List<Table>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT id, branch_id, name, sort_order, is_active FROM branch_tables
                WHERE client_id = :clientId""" + (withOff ? "" : " AND is_active") + """

                ORDER BY sort_order, created_at, name""",
                new MapSqlParameterSource("clientId", clientId),
                rs -> {
                    Table table = new Table(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                            rs.getString("name"), rs.getInt("sort_order"), rs.getBoolean("is_active"));
                    result.computeIfAbsent(table.branchId(), key -> new ArrayList<>()).add(table);
                });
        return result;
    }

    @Transactional(readOnly = true)
    public List<Table> list(UUID clientId, UUID branchId) {
        requireBranch(clientId, branchId);
        return byBranch(clientId, true).getOrDefault(branchId, List.of());
    }

    @Transactional
    public List<Table> add(UUID clientId, UUID branchId, AddTablesRequest request) {
        requireBranch(clientId, branchId);
        List<Table> existing = byBranch(clientId, true).getOrDefault(branchId, List.of());
        Set<String> taken = new HashSet<>();
        int highest = 0;
        int nextOrder = 0;
        for (Table table : existing) {
            taken.add(table.name().toLowerCase(Locale.ROOT));
            nextOrder = Math.max(nextOrder, table.sortOrder() + 1);
            if (table.name().matches("\\d{1,4}")) {
                highest = Math.max(highest, Integer.parseInt(table.name()));
            }
        }
        List<String> names = new ArrayList<>();
        String typed = request == null || request.name() == null ? "" : request.name().trim();
        if (!typed.isEmpty()) {
            names.add(name(typed, taken));
        } else {
            int count = request == null || request.count() == null ? 0 : request.count();
            if (count < 1 || count > 100) {
                throw new MadeWithException("Add between 1 and 100 tables at a time.");
            }
            for (int number = highest + 1; names.size() < count; number++) {
                if (!taken.contains(String.valueOf(number))) {
                    names.add(String.valueOf(number));
                }
            }
        }
        if (existing.size() + names.size() > MAX_TABLES) {
            throw new MadeWithException("A branch can have up to " + MAX_TABLES + " tables.");
        }
        for (String name : names) {
            jdbc.update("""
                    INSERT INTO branch_tables (client_id, branch_id, name, sort_order)
                    VALUES (:clientId, :branchId, :name, :order)""",
                    new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId)
                            .addValue("name", name).addValue("order", nextOrder++));
        }
        return list(clientId, branchId);
    }

    @Transactional
    public List<Table> update(UUID clientId, UUID tableId, TableRequest request) {
        Map<String, Object> row = jdbc.queryForList(
                "SELECT branch_id, name, is_active FROM branch_tables WHERE id = :id AND client_id = :clientId",
                new MapSqlParameterSource().addValue("id", tableId).addValue("clientId", clientId))
                .stream().findFirst().orElseThrow(() -> new MadeWithException("No such table."));
        UUID branchId = (UUID) row.get("branch_id");
        if (request.name() != null && !request.name().trim().equalsIgnoreCase((String) row.get("name"))) {
            Set<String> taken = new HashSet<>();
            byBranch(clientId, true).getOrDefault(branchId, List.of()).stream()
                    .filter(table -> !table.id().equals(tableId))
                    .forEach(table -> taken.add(table.name().toLowerCase(Locale.ROOT)));
            jdbc.update("UPDATE branch_tables SET name = :name WHERE id = :id",
                    new MapSqlParameterSource().addValue("name", name(request.name().trim(), taken)).addValue("id", tableId));
        } else if (request.name() != null && !request.name().trim().equals(row.get("name"))) {
            // Only its capitals changed.
            jdbc.update("UPDATE branch_tables SET name = :name WHERE id = :id",
                    new MapSqlParameterSource().addValue("name", name(request.name().trim(), Set.of())).addValue("id", tableId));
        }
        if (request.active() != null && request.active() != (Boolean) row.get("is_active")) {
            if (!request.active() && Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM bills WHERE table_id = :id AND status = 'OPEN')",
                    new MapSqlParameterSource("id", tableId), Boolean.class))) {
                throw new MadeWithException("This table has an open bill. Pay it first, then switch the table off.");
            }
            jdbc.update("UPDATE branch_tables SET is_active = :on WHERE id = :id",
                    new MapSqlParameterSource().addValue("on", request.active()).addValue("id", tableId));
        }
        return list(clientId, branchId);
    }

    /** The grid's order: the ids as the owner arranged them; any left out keep their place after. */
    @Transactional
    public List<Table> reorder(UUID clientId, UUID branchId, OrderRequest request) {
        requireBranch(clientId, branchId);
        List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();
        int order = 0;
        for (UUID id : ids) {
            jdbc.update("UPDATE branch_tables SET sort_order = :order WHERE id = :id AND branch_id = :branchId AND client_id = :clientId",
                    new MapSqlParameterSource().addValue("order", order++).addValue("id", id)
                            .addValue("branchId", branchId).addValue("clientId", clientId));
        }
        for (Table table : byBranch(clientId, true).getOrDefault(branchId, List.of())) {
            if (!ids.contains(table.id())) {
                jdbc.update("UPDATE branch_tables SET sort_order = :order WHERE id = :id",
                        new MapSqlParameterSource().addValue("order", order++).addValue("id", table.id()));
            }
        }
        return list(clientId, branchId);
    }

    private static String name(String typed, Set<String> taken) {
        String name = typed.replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new MadeWithException("Give the table a name or number.");
        }
        if (name.length() > 40) {
            throw new MadeWithException("Keep a table's name to 40 letters.");
        }
        if (taken.contains(name.toLowerCase(Locale.ROOT))) {
            throw new MadeWithException("There's already a table called " + name + " here.");
        }
        return name;
    }

    private void requireBranch(UUID clientId, UUID branchId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM branches WHERE id = :id AND client_id = :clientId)",
                new MapSqlParameterSource().addValue("id", branchId).addValue("clientId", clientId), Boolean.class))) {
            throw new BranchNotFoundException();
        }
    }
}
