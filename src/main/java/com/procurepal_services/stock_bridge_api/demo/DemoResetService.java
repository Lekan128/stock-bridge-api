package com.procurepal_services.stock_bridge_api.demo;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SuperAdmin's "Reset demo" (V86): wipes the shared demo company's stock, products, suppliers, branches,
 * areas, orders to suppliers and everything that hangs off them, then fills it again with
 * {@link DemoSeeder}'s restaurant - a fresh {@value DemoSeeder#DAYS} days ending today.
 *
 * <h2>What it keeps</h2>
 * The company, its users and their passwords, and its roles: prospects keep signing in exactly as before.
 * Each person's branches and areas are put back by name once the new ones exist.
 *
 * <h2>Why it can only ever touch a demo</h2>
 * {@link #start} refuses any company whose {@code is_demo} is not set, the caller has typed the company's
 * code to confirm, and every statement carries an explicit {@code client_id}. A company with marketplace
 * orders is refused too, though a demo can never place one (see {@link DemoCompanyGuard}).
 *
 * <h2>Why in the background</h2>
 * Ninety days of a busy kitchen is a few thousand stock writes, a few minutes. The request starts it and
 * the screen asks how it is going. All of it is one transaction: if anything fails, the demo is left as it
 * was before the button was pressed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoResetService {

    /**
     * Every table with a {@code client_id}, wiped or deliberately kept. {@code DemoResetIntegrationTest}
     * fails when a migration adds one that is in neither list, so a new feature's rows can't survive a reset
     * by being forgotten.
     */
    static final Set<String> KEPT = Set.of(
            "users", "roles", "client_setting_changes", "onboarding_messages", "push_subscriptions",
            "setup_requests", "product_changes", "user_branches", "user_areas", "branches", "areas",
            "orders");

    /** In the order they are emptied - forced by the foreign keys, see {@link #wipe}. */
    static final List<String> WIPED = List.of(
            "notifications", "carts", "stock_write_undo", "stock_idempotency_keys", "phone_sync_status",
            "area_move_returns", "area_moves", "stock_requests", "delivery_send_backs", "purchase_order_emails",
            "price_requests", "stock_movements", "stock_transfers", "expected_deliveries", "area_stock",
            "branch_stock", "product_price_changes", "products", "product_vendors", "import_sessions",
            "product_list_files", "company_vendors", "company_categories", "product_sku_settings", "delivery_addresses");

    public enum State { RUNNING, DONE, FAILED }

    /** What the SuperAdmin screen shows while it runs and after. */
    public record Status(State state, String step, OffsetDateTime startedAt, OffsetDateTime finishedAt,
            DemoSeeder.Result result, String problem) {
    }

    private final ClientRepository clientRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TenantScopeExecutor tenantScopeExecutor;
    private final DemoSeeder demoSeeder;

    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
    private final ExecutorService runner = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "demo-reset");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Marks a company as the demo, or back to an ordinary company - the step that makes a company's data
     * resettable, so it is guarded here and not only on the screen (owner, 2026-11-12: "if someone
     * mistakenly makes a customer's store demo that will be a disaster").
     *
     * <ul>
     *   <li>The company's code must be typed back, both ways.</li>
     *   <li>Only an EMPTY company can become a demo: no products, no stock records, no marketplace orders,
     *       nobody but the account holder. A customer who has used the app can never be made one, so a
     *       reset can never reach a real customer's data. The one exception is a company that was a demo
     *       before and was switched back - its data is the demo's own.</li>
     *   <li>Never the platform owner or a marketplace seller.</li>
     *   <li>Every change is written to {@code client_setting_changes}: who, when, and the reason.</li>
     * </ul>
     */
    @org.springframework.transaction.annotation.Transactional
    public void setDemo(UUID clientId, boolean demo, String confirmation, UUID superAdminId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(com.procurepal_services.stock_bridge_api.superadmin.ClientNotFoundException::new);
        if (confirmation == null || !confirmation.trim().equalsIgnoreCase(client.getSlug())) {
            throw new DemoActionNotAllowedException("Type " + client.getSlug() + " to confirm.");
        }
        if (client.isDemo() == demo) {
            return;
        }
        if (demo) {
            String refusal = whyNotDemo(client);
            if (refusal != null) {
                throw new DemoActionNotAllowedException(refusal);
            }
        }
        client.setDemo(demo);
        clientRepository.save(client);
        String username = jdbc.query("SELECT username FROM super_admins WHERE id = :id",
                new MapSqlParameterSource("id", superAdminId), (rs, n) -> rs.getString("username"))
                .stream().findFirst().orElse("support");
        jdbc.update("""
                INSERT INTO client_setting_changes (id, client_id, setting, old_value, new_value, source, changed_by,
                                                    changed_by_kind, changed_by_username, reason, changed_at)
                VALUES (gen_random_uuid(), :clientId, 'DEMO', :old, :new, 'SUPPORT', :by, 'SUPER_ADMIN', :username,
                        :reason, now())
                """, params(clientId)
                .addValue("old", Boolean.toString(!demo)).addValue("new", Boolean.toString(demo))
                .addValue("by", superAdminId).addValue("username", username)
                .addValue("reason", demo ? "Made the shared demo company" : "No longer a demo company"));
        log.warn("Company {} ({}) is {} a demo - by super admin {}", client.getSlug(), clientId,
                demo ? "now" : "no longer", username);
    }

    /** Whether this company may be made a demo: null when it may, otherwise the sentence saying why not. */
    public String whyNotDemo(Client client) {
        if (client.isPlatformOwner() || client.isVendor()) {
            return client.getName() + " sells on the marketplace, so it can't be a demo.";
        }
        UUID clientId = client.getId();
        if (count("SELECT count(*) FROM orders WHERE client_id = :clientId OR seller_client_id = :clientId", clientId) > 0) {
            return client.getName() + " has marketplace orders. It's a real customer and can't be a demo.";
        }
        boolean wasDemo = count("""
                SELECT count(*) FROM client_setting_changes
                WHERE client_id = :clientId AND setting = 'DEMO' AND new_value = 'true'
                """, clientId) > 0;
        if (wasDemo) {
            return null;
        }
        boolean used = count("SELECT count(*) FROM products WHERE client_id = :clientId", clientId) > 0
                || count("SELECT count(*) FROM stock_movements WHERE client_id = :clientId", clientId) > 0
                || count("SELECT count(*) FROM users WHERE client_id = :clientId", clientId) > 1;
        if (used) {
            return client.getName() + " already has products, stock or staff. Only a brand-new, empty company "
                    + "can become a demo - sign up a new one for it.";
        }
        return null;
    }

    public Status status(UUID clientId) {
        return statuses.get(clientId);
    }

    /** Checks, then resets in the background. */
    public Status start(UUID clientId, String confirmation) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(com.procurepal_services.stock_bridge_api.superadmin.ClientNotFoundException::new);
        if (!client.isDemo()) {
            throw new DemoActionNotAllowedException(
                    "Only a demo company can be reset. Mark " + client.getName() + " as a demo first.");
        }
        if (confirmation == null || !confirmation.trim().equalsIgnoreCase(client.getSlug())) {
            throw new DemoActionNotAllowedException("Type " + client.getSlug() + " to confirm the reset.");
        }
        Status current = statuses.get(clientId);
        if (current != null && current.state() == State.RUNNING) {
            return current;
        }
        long orders = count("SELECT count(*) FROM orders WHERE client_id = :clientId OR seller_client_id = :clientId", clientId);
        if (orders > 0) {
            throw new DemoActionNotAllowedException(client.getName()
                    + " has marketplace orders, so it isn't only a demo. It can't be reset.");
        }
        UUID ownerId = jdbc.query("""
                SELECT id FROM users WHERE client_id = :clientId AND is_root ORDER BY created_at LIMIT 1
                """, params(clientId), (rs, n) -> rs.getObject("id", UUID.class)).stream().findFirst()
                .orElseThrow(() -> new DemoActionNotAllowedException(client.getName() + " has no account holder."));

        OffsetDateTime startedAt = OffsetDateTime.now();
        Status running = new Status(State.RUNNING, "Starting", startedAt, null, null, null);
        statuses.put(clientId, running);
        runner.submit(() -> run(clientId, client.getSlug(), ownerId, startedAt));
        return running;
    }

    private void run(UUID clientId, String slug, UUID ownerId, OffsetDateTime startedAt) {
        try {
            DemoSeeder.Result result = transactions.execute(tx -> tenantScopeExecutor.callAs(clientId, () -> {
                step(clientId, startedAt, "Clearing the old demo");
                List<Map<String, Object>> places = placesOfPeople(clientId);
                wipe(clientId);
                DemoSeeder.Result seeded = demoSeeder.seed(clientId, ownerId, message -> step(clientId, startedAt, message));
                restorePlaces(clientId, places);
                return seeded;
            }));
            log.warn("Demo company {} ({}) reset: {}", slug, clientId, result);
            statuses.put(clientId, new Status(State.DONE, "Done", startedAt, OffsetDateTime.now(), result, null));
        } catch (Exception e) {
            log.error("Demo reset for {} ({}) failed; nothing was changed", slug, clientId, e);
            statuses.put(clientId, new Status(State.FAILED, "Failed", startedAt, OffsetDateTime.now(), null,
                    "The reset stopped and nothing was changed: " + e.getMessage()));
        }
    }

    private void step(UUID clientId, OffsetDateTime startedAt, String step) {
        log.info("Demo reset {}: {} ({} s in)", clientId, step,
                java.time.Duration.between(startedAt, OffsetDateTime.now()).toSeconds());
        statuses.put(clientId, new Status(State.RUNNING, step, startedAt, null, null, null));
    }

    /**
     * Who works where, by branch and area name, so it survives the branches and areas being made again.
     * The main branch keeps its row; the others are new rows with the same names.
     */
    private List<Map<String, Object>> placesOfPeople(UUID clientId) {
        return jdbc.queryForList("""
                SELECT ub.user_id, b.name AS branch_name, NULL AS area_name FROM user_branches ub
                JOIN branches b ON b.id = ub.branch_id WHERE ub.client_id = :clientId
                UNION ALL
                SELECT ua.user_id, b.name AS branch_name, a.name AS area_name FROM user_areas ua
                JOIN areas a ON a.id = ua.area_id JOIN branches b ON b.id = a.branch_id WHERE ua.client_id = :clientId
                """, params(clientId));
    }

    private void restorePlaces(UUID clientId, List<Map<String, Object>> places) {
        for (Map<String, Object> place : places) {
            MapSqlParameterSource row = params(clientId)
                    .addValue("userId", place.get("user_id"))
                    .addValue("branchName", place.get("branch_name"))
                    .addValue("areaName", place.get("area_name"));
            if (place.get("area_name") == null) {
                jdbc.update("""
                        INSERT INTO user_branches (client_id, user_id, branch_id)
                        SELECT :clientId, :userId, b.id FROM branches b
                        WHERE b.client_id = :clientId AND b.name = :branchName
                        ON CONFLICT DO NOTHING
                        """, row);
            } else {
                jdbc.update("""
                        INSERT INTO user_areas (client_id, user_id, area_id)
                        SELECT :clientId, :userId, a.id FROM areas a JOIN branches b ON b.id = a.branch_id
                        WHERE a.client_id = :clientId AND b.name = :branchName AND a.name = :areaName
                        ON CONFLICT DO NOTHING
                        """, row);
            }
        }
    }

    /**
     * Empties the demo, children before parents. Every RESTRICT on the way is satisfied by an earlier line
     * rather than worked around: allocations before movements, movements before products, transfers and
     * areas; transfer lines (which point at movements) before movements; import sessions last of the rows
     * that products and movements point at.
     */
    void wipe(UUID clientId) {
        MapSqlParameterSource params = params(clientId);
        String[] statements = {
                "DELETE FROM notifications WHERE client_id = :clientId",
                "DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE client_id = :clientId)",
                "DELETE FROM carts WHERE client_id = :clientId",
                "DELETE FROM stock_write_undo WHERE client_id = :clientId",
                "DELETE FROM stock_idempotency_keys WHERE client_id = :clientId",
                "DELETE FROM phone_sync_status WHERE client_id = :clientId",
                "DELETE FROM area_move_returns WHERE client_id = :clientId",
                "DELETE FROM area_move_lines WHERE move_id IN (SELECT id FROM area_moves WHERE client_id = :clientId)",
                "DELETE FROM area_moves WHERE client_id = :clientId",
                "DELETE FROM stock_request_lines WHERE request_id IN (SELECT id FROM stock_requests WHERE client_id = :clientId)",
                "DELETE FROM stock_requests WHERE client_id = :clientId",
                "DELETE FROM stock_transfer_lines WHERE transfer_id IN (SELECT id FROM stock_transfers WHERE client_id = :clientId)",
                "DELETE FROM stock_transfer_receipts WHERE transfer_id IN (SELECT id FROM stock_transfers WHERE client_id = :clientId)",
                "DELETE FROM delivery_send_backs WHERE client_id = :clientId",
                "DELETE FROM purchase_order_emails WHERE client_id = :clientId",
                "DELETE FROM price_requests WHERE client_id = :clientId",
                """
                DELETE FROM stock_movement_allocations a USING stock_movements m
                WHERE (a.in_movement_id = m.id OR a.out_movement_id = m.id) AND m.client_id = :clientId
                """,
                "DELETE FROM stock_movements WHERE client_id = :clientId",
                "DELETE FROM stock_transfers WHERE client_id = :clientId",
                "DELETE FROM expected_deliveries WHERE client_id = :clientId",
                "DELETE FROM area_stock WHERE client_id = :clientId",
                "DELETE FROM branch_stock WHERE client_id = :clientId",
                "DELETE FROM product_price_changes WHERE client_id = :clientId",
                "DELETE FROM products WHERE client_id = :clientId",
                "DELETE FROM product_vendors WHERE client_id = :clientId",
                "DELETE FROM import_sessions WHERE client_id = :clientId",
                "DELETE FROM product_list_files WHERE client_id = :clientId",
                "DELETE FROM company_vendors WHERE client_id = :clientId",
                "DELETE FROM company_categories WHERE client_id = :clientId",
                "DELETE FROM product_sku_settings WHERE client_id = :clientId",
                "DELETE FROM delivery_addresses WHERE client_id = :clientId",
                "DELETE FROM user_areas WHERE client_id = :clientId",
                "DELETE FROM user_branches WHERE client_id = :clientId",
                // The main branch and its Store stay (the Store is made by trigger with the branch); every
                // other area and branch is made again by the seed.
                """
                DELETE FROM areas WHERE client_id = :clientId
                  AND NOT (is_original AND branch_id IN (SELECT id FROM branches WHERE client_id = :clientId AND is_default))
                """,
                "DELETE FROM branches WHERE client_id = :clientId AND NOT is_default",
                "UPDATE branches SET is_active = TRUE, closed_at = NULL WHERE client_id = :clientId",
                "UPDATE areas SET is_active = TRUE, closed_at = NULL WHERE client_id = :clientId",
        };
        for (String sql : statements) {
            jdbc.update(sql, params);
        }
    }

    private long count(String sql, UUID clientId) {
        Long value = jdbc.queryForObject(sql, params(clientId), Long.class);
        return value == null ? 0 : value;
    }

    private static MapSqlParameterSource params(UUID clientId) {
        return new MapSqlParameterSource("clientId", clientId);
    }
}
