package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.client.ClientProvisioning;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchFigure;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Transactional at class level for the reason {@link BranchAccessService} gives: outside a
 * transaction this SQL would borrow a second connection beside the one the request already holds.
 * Every caller that writes is already in a transaction, which these methods join.
 */
@Slf4j
@Service
@org.springframework.transaction.annotation.Transactional
@RequiredArgsConstructor
public class BranchStockService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;

    /** {@link #resolveForWrite(UUID, UUID)} for a write with nobody's branches to fall back on. */
    public UUID resolveForWrite(UUID requestedBranchId) {
        return resolveForWrite(requestedBranchId, null);
    }

    /**
     * The branch a write is recorded at (D-B10). A named branch must be this company's and open. No
     * branch named: the company's only open branch, or else the one branch {@code userId} works at.
     * Never the main branch by default when there are several - an old copy of the app that doesn't
     * send one would otherwise record at the wrong branch with nobody seeing it.
     *
     * <p>This says where; whether this person may record there is {@link
     * BranchAccessService#requireMayRecordAt}, checked by the endpoints that take a person's write.
     * Writes the system makes for an order (a marketplace receipt, a seller's dispatch) name their
     * branch and are not held to the person's branches.
     */
    public UUID resolveForWrite(UUID requestedBranchId, UUID userId) {
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
        if (userId != null) {
            List<UUID> theirs = branchAccessService.writeBranchIds(userId);
            if (theirs.size() == 1) {
                return theirs.getFirst();
            }
        }
        throw new BranchRequiredException();
    }

    /** Every branch the company has had, closed ones included - "all branches" for a report. */
    public List<UUID> allBranchIds(UUID clientId) {
        return jdbc.queryForList("SELECT id FROM branches WHERE client_id = :clientId",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
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

    /**
     * Each product's figure at each of {@code branchIds} (in that order), for a multi-branch company -
     * null for a one-branch company, whose product figure is the only one. A branch holding none of a
     * product reads 0; a branch with no level of its own uses the product's ({@code productThresholds}).
     */
    public Map<UUID, List<BranchFigure>> figures(
            UUID clientId, Collection<UUID> productIds, List<UUID> branchIds, Map<UUID, Integer> productThresholds) {
        if (productIds.isEmpty() || branchIds.isEmpty() || !isMultiBranch(clientId)) {
            return null;
        }
        Map<UUID, Map<UUID, int[]>> found = new HashMap<>();
        Map<UUID, Map<UUID, Integer>> thresholds = new HashMap<>();
        jdbc.query("""
                SELECT product_id, branch_id, quantity_on_hand, low_stock_threshold FROM branch_stock
                WHERE client_id = :clientId AND product_id IN (:productIds) AND branch_id IN (:branchIds)
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("productIds", productIds)
                        .addValue("branchIds", branchIds),
                rs -> {
                    UUID product = rs.getObject("product_id", UUID.class);
                    UUID branch = rs.getObject("branch_id", UUID.class);
                    found.computeIfAbsent(product, key -> new HashMap<>())
                            .put(branch, new int[] {rs.getInt("quantity_on_hand")});
                    Integer own = (Integer) rs.getObject("low_stock_threshold");
                    if (own != null) {
                        thresholds.computeIfAbsent(product, key -> new HashMap<>()).put(branch, own);
                    }
                });
        Map<UUID, Map<UUID, Integer>> onTheWay = new HashMap<>();
        jdbc.query("""
                SELECT l.product_id, t.to_branch_id,
                       SUM(l.quantity_sent - l.quantity_received - l.quantity_missing - l.quantity_damaged) AS on_the_way
                FROM stock_transfers t JOIN stock_transfer_lines l ON l.transfer_id = t.id
                WHERE t.client_id = :clientId AND t.status IN ('ON_THE_WAY', 'PARTLY_RECEIVED')
                  AND t.to_branch_id IN (:branchIds) AND l.product_id IN (:productIds)
                GROUP BY l.product_id, t.to_branch_id
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("productIds", productIds)
                        .addValue("branchIds", branchIds),
                rs -> {
                    onTheWay.computeIfAbsent(rs.getObject("product_id", UUID.class), key -> new HashMap<>())
                            .put(rs.getObject("to_branch_id", UUID.class), rs.getInt("on_the_way"));
                });
        Map<UUID, List<BranchFigure>> figures = new HashMap<>();
        for (UUID product : productIds) {
            List<BranchFigure> list = new ArrayList<>(branchIds.size());
            for (UUID branch : branchIds) {
                int[] quantity = found.getOrDefault(product, Map.of()).get(branch);
                Integer threshold = thresholds.getOrDefault(product, Map.of())
                        .getOrDefault(branch, productThresholds.get(product));
                list.add(BranchFigure.of(branch, quantity == null ? 0 : quantity[0], threshold,
                        onTheWay.getOrDefault(product, Map.of()).getOrDefault(branch, 0)));
            }
            figures.put(product, list);
        }
        return figures;
    }

    /**
     * One branch's product counts, as the dashboard reads them (Phase 3): {low, out, needs attention},
     * active products only, each against the branch's own level or else the product's - the same
     * rules as {@code StockStatus} and the company counts in {@code ProductRepository}.
     */
    public long[] productCounts(UUID clientId, UUID branchId) {
        return jdbc.queryForObject("""
                WITH at_branch AS (
                    SELECT COALESCE(bs.quantity_on_hand, 0) AS quantity,
                           COALESCE(bs.low_stock_threshold, p.low_stock_threshold) AS threshold
                    FROM products p
                    LEFT JOIN branch_stock bs ON bs.product_id = p.id AND bs.branch_id = :branchId
                    WHERE p.client_id = :clientId AND p.is_active)
                SELECT COUNT(*) FILTER (WHERE threshold IS NOT NULL AND quantity <= threshold) AS low,
                       COUNT(*) FILTER (WHERE quantity <= 0) AS out,
                       COUNT(*) FILTER (WHERE quantity <= 0 OR (threshold IS NOT NULL AND quantity <= threshold)) AS attention
                FROM at_branch
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId),
                (rs, n) -> new long[] {rs.getLong("low"), rs.getLong("out"), rs.getLong("attention")});
    }

    /**
     * Sets a product's low-stock level at a branch; null goes back to the product's own (UX section
     * 5.9). A branch that holds none of the product gets a row at 0 to carry the level.
     */
    public void setThreshold(UUID clientId, UUID productId, UUID branchId, Integer threshold) {
        jdbc.update("""
                INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand, low_stock_threshold)
                VALUES (:clientId, :branchId, :productId, 0, :threshold)
                ON CONFLICT (branch_id, product_id) DO UPDATE SET low_stock_threshold = :threshold
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("productId", productId)
                        .addValue("threshold", threshold));
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
