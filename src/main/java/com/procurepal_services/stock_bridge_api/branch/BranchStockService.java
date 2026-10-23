package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.client.ClientProvisioning;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Where a stock write lands, and the only code that changes a stock figure (MULTI_BRANCH_BUILD_PLAN.md
 * section 4.1). Every write path calls {@link #apply} instead of {@code Product.setQuantityOnHand};
 * {@code QuantityOnHandWritesTest} fails the build if one doesn't.
 *
 * <h2>Two modes, decided by how many branches the company has</h2>
 * <ul>
 *   <li><b>One branch</b> (every company today): {@code products.quantity_on_hand} is the truth,
 *       exactly as before this class existed, and that branch's {@code branch_stock} row is a copy
 *       of it, re-copied on every write. A figure that changed outside the ledger - an import from
 *       before V20, a fix by hand, a test fixture - therefore cannot leave the copy behind, and a
 *       single shop's ceilings and counts are computed from the very number they always were.</li>
 *   <li><b>More than one</b> (Phase 1 onwards): {@code branch_stock} is the truth and
 *       {@code quantity_on_hand} is the company total, moved by the same delta in the same
 *       transaction. Adding the second branch copies every product's figure into the main
 *       branch's row first (Phase 1), so the switch between the modes is one explicit step.</li>
 * </ul>
 * A company counts as multi-branch once it has ever had two branches, closed ones included: a
 * closed branch holds nothing, but its history is real, so the rows stay the truth.
 *
 * <h2>Locking</h2>
 * Callers hold the product row's lock (D-B2), as every stock write already did; that is what
 * serialises two writes to the same product. The upserts here are atomic on their own as well.
 *
 * <p>JDBC rather than an entity, for the atomic {@code ON CONFLICT} upsert. It shares the JPA
 * transaction's connection. It needs no flush first, though {@code branch_stock} references the
 * product: the caller's lock is a {@code SELECT ... FOR UPDATE}, so the product row is already in
 * the table - a product created earlier in the same transaction (an opening balance) was flushed by
 * that query. Flushing here anyway would dirty-check the whole persistence context on every row of a
 * stock-sheet import, which grows with the sheet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BranchStockService {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The branch a write is recorded at (D-B10). A named branch must be this company's and open. No
     * branch named: the company's only branch. Never the main branch by default when there are
     * several - an old copy of the app that doesn't send one would otherwise record at the wrong
     * branch with nobody seeing it. Phase 1 adds "the one branch this person works at".
     */
    public UUID resolveForWrite(UUID requestedBranchId) {
        UUID clientId = requireTenantId();
        if (requestedBranchId != null) {
            List<Boolean> active = jdbc.queryForList(
                    "SELECT is_active FROM branches WHERE id = :id AND client_id = :clientId",
                    new MapSqlParameterSource().addValue("id", requestedBranchId).addValue("clientId", clientId),
                    Boolean.class);
            if (active.isEmpty()) {
                throw new BranchNotFoundException();
            }
            if (!active.getFirst()) {
                throw new BranchClosedException();
            }
            return requestedBranchId;
        }

        List<UUID> open = openBranchIds(clientId);
        if (open.size() == 1) {
            return open.getFirst();
        }
        if (open.isEmpty() && !hasAnyBranch(clientId)) {
            return createMissingDefaultBranch(clientId);
        }
        throw new BranchRequiredException();
    }

    /** See the class javadoc's two modes. */
    public boolean isMultiBranch(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 1 FROM branches WHERE client_id = :clientId",
                new MapSqlParameterSource("clientId", clientId),
                Boolean.class));
    }

    /** How many of {@code product} the branch holds now: the figure a stock-out or count is judged against. */
    public int quantityAt(Product product, UUID branchId) {
        if (!isMultiBranch(product.getClientId())) {
            return product.getQuantityOnHand();
        }
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :branchId AND product_id = :productId",
                new MapSqlParameterSource().addValue("branchId", branchId).addValue("productId", product.getId()),
                Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    /**
     * Moves {@code product}'s figure at {@code branchId} by {@code delta} (signed), and the company
     * total with it. The caller holds the product's lock and has decided the move is allowed.
     */
    public void apply(Product product, UUID branchId, int delta) {
        product.setQuantityOnHand(product.getQuantityOnHand() + delta);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", product.getClientId())
                .addValue("branchId", branchId)
                .addValue("productId", product.getId())
                .addValue("delta", delta)
                .addValue("total", product.getQuantityOnHand());
        if (isMultiBranch(product.getClientId())) {
            jdbc.update("""
                    INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
                    VALUES (:clientId, :branchId, :productId, :delta)
                    ON CONFLICT (branch_id, product_id)
                    DO UPDATE SET quantity_on_hand = branch_stock.quantity_on_hand + :delta
                    """, params);
        } else {
            jdbc.update("""
                    INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
                    VALUES (:clientId, :branchId, :productId, :total)
                    ON CONFLICT (branch_id, product_id)
                    DO UPDATE SET quantity_on_hand = :total
                    """, params);
        }
    }

    private List<UUID> openBranchIds(UUID clientId) {
        return jdbc.queryForList(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_active LIMIT 2",
                new MapSqlParameterSource("clientId", clientId),
                UUID.class);
    }

    private boolean hasAnyBranch(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM branches WHERE client_id = :clientId)",
                new MapSqlParameterSource("clientId", clientId),
                Boolean.class));
    }

    /**
     * Every company is created with its default branch ({@link ClientProvisioning}), and V51 gave
     * one to any that lacked it. A company that still has none was created by hand; giving it the
     * branch it should always have had is a repair, not a guess - there is no other branch the
     * write could have meant. Logged, because it means a creation path skipped provisioning.
     */
    private UUID createMissingDefaultBranch(UUID clientId) {
        log.warn("Company {} had no branch; creating its default '{}' branch", clientId,
                ClientProvisioning.DEFAULT_BRANCH_NAME);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("name", ClientProvisioning.DEFAULT_BRANCH_NAME);
        jdbc.update("""
                INSERT INTO branches (client_id, name, is_default, is_active)
                VALUES (:clientId, :name, TRUE, TRUE)
                ON CONFLICT DO NOTHING
                """, params);
        return jdbc.queryForObject(
                "SELECT id FROM branches WHERE client_id = :clientId AND is_default", params, UUID.class);
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context is set; refusing to record stock without a company");
        }
        return tenantId;
    }
}
