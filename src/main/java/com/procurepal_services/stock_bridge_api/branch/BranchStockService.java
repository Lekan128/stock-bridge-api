package com.procurepal_services.stock_bridge_api.branch;

import com.procurepal_services.stock_bridge_api.client.ClientProvisioning;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
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
 * <h2>Areas inside a branch (V60, STOCK_AREAS_BUILD_PLAN.md D-E2)</h2>
 * The same two modes again, one level down and decided per branch. A branch with <b>one area</b>
 * (every branch until areas can be added) keeps its {@code branch_stock} row as its truth and writes
 * nothing to {@code area_stock}. A branch that has <b>ever had two</b> keeps each area's figure in
 * {@code area_stock}, and its {@code branch_stock} row - moved by the same delta in the same
 * {@link #apply} - is their sum. Lots, FIFO and costs stay per branch (D-E4): an area is a quantity,
 * not a ledger of its own.
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
    private final AreaAccessService areaAccessService;

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

    /**
     * How many of {@code product} the area holds now: the figure a stock-out or count is judged against.
     * At a branch with one area, the branch's own figure, exactly as before areas.
     */
    public int quantityAt(Product product, UUID branchId, UUID areaId) {
        if (!isAreaMode(branchId)) {
            return quantityAt(product, branchId);
        }
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM area_stock WHERE area_id = :areaId AND product_id = :productId",
                new MapSqlParameterSource().addValue("areaId", areaId).addValue("productId", product.getId()),
                Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    /** How many of {@code product} the branch holds now, whatever area it is in. */
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
     * Moves {@code product}'s figure in {@code branchId}'s {@code areaId} by {@code delta} (signed), and
     * the branch's and the company's totals with it. The caller holds the product's lock and has decided
     * the move is allowed. The area is required even at a branch with one, so that no caller can forget
     * it on the day a second appears: {@link #resolveAreaForWrite} for a new write, {@link #areaOf} for
     * one being reversed.
     */
    public void apply(Product product, UUID branchId, UUID areaId, int delta) {
        java.util.Objects.requireNonNull(areaId, "every stock figure is in an area (V60)");
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
        if (isAreaMode(branchId)) {
            jdbc.update("""
                    INSERT INTO area_stock (client_id, area_id, branch_id, product_id, quantity_on_hand)
                    VALUES (:clientId, :areaId, :branchId, :productId, :delta)
                    ON CONFLICT (area_id, product_id)
                    DO UPDATE SET quantity_on_hand = area_stock.quantity_on_hand + :delta
                    """, params.addValue("areaId", areaId));
        }
    }

    /**
     * Each product's figure at each of {@code branchIds} (in that order), for a multi-branch company -
     * null for a one-branch company, whose product figure is the only one. A branch holding none of a
     * product reads 0; a branch with no level of its own uses the product's ({@code productThresholds}).
     */
    public Map<UUID, List<BranchFigure>> figures(
            UUID clientId, Collection<UUID> productIds, List<UUID> branchIds, Map<UUID, Integer> productThresholds) {
        return figures(clientId, productIds, branchIds, productThresholds, false);
    }

    /**
     * @param knownMultiBranch the caller already knows the company has more than one branch (it sees
     *     two), so the check is skipped - one query fewer on each sync page.
     */
    public Map<UUID, List<BranchFigure>> figures(
            UUID clientId, Collection<UUID> productIds, List<UUID> branchIds, Map<UUID, Integer> productThresholds,
            boolean knownMultiBranch) {
        if (productIds.isEmpty() || branchIds.isEmpty() || (!knownMultiBranch && !isMultiBranch(clientId))) {
            return null;
        }
        // Arrays, not IN lists (a sync page is 2,000 products), planned for the values each time, and no
        // client_id test: the product ids are this company's own, and a generic plan judged "one
        // company's rows at these branches" by an average company's size - 3 rows, against a chain's
        // 312k - and took 1.6 s for a 50-product page (Phase 4, 26 branches x 20k products).
        jdbc.getJdbcTemplate().execute("SET LOCAL plan_cache_mode = force_custom_plan");
        Map<UUID, Map<UUID, int[]>> found = new HashMap<>();
        Map<UUID, Map<UUID, Integer>> thresholds = new HashMap<>();
        // A sync page (2,000 products): the page's ids joined as a table, a hash join over the branch's
        // rows - 4 ms. A list page (50): the product index - 2 ms. Each is several times the other's.
        String figuresSql = productIds.size() > 200
                ? """
                  SELECT bs.product_id, bs.branch_id, bs.quantity_on_hand, bs.low_stock_threshold
                  FROM unnest(:productIds) AS page(product_id)
                  JOIN branch_stock bs ON bs.product_id = page.product_id AND bs.branch_id = ANY(:branchIds)
                  """
                : """
                  SELECT product_id, branch_id, quantity_on_hand, low_stock_threshold FROM branch_stock
                  WHERE product_id = ANY(:productIds) AND branch_id = ANY(:branchIds)
                  """;
        jdbc.query(figuresSql, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("productIds", productIds.toArray(UUID[]::new))
                        .addValue("branchIds", branchIds.toArray(UUID[]::new)),
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
                  AND t.to_branch_id = ANY(:branchIds) AND l.product_id = ANY(:productIds)
                GROUP BY l.product_id, t.to_branch_id
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("productIds", productIds.toArray(UUID[]::new))
                        .addValue("branchIds", branchIds.toArray(UUID[]::new)),
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

    /**
     * One low-stock level for many products at a branch (Phase 4, Avatar C: "20 at Lekki for every
     * drink"); null takes them back to each product's own. Products of another company are skipped.
     *
     * @return how many products were set.
     */
    public int setThresholds(UUID clientId, UUID branchId, java.util.Collection<UUID> productIds, Integer threshold) {
        if (productIds.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand, low_stock_threshold)
                SELECT :clientId, :branchId, p.id, 0, :threshold
                FROM products p WHERE p.client_id = :clientId AND p.id IN (:productIds)
                ON CONFLICT (branch_id, product_id) DO UPDATE SET low_stock_threshold = :threshold
                """, new MapSqlParameterSource()
                        .addValue("clientId", clientId)
                        .addValue("branchId", branchId)
                        .addValue("productIds", productIds)
                        .addValue("threshold", threshold));
    }

    // ------------------------------------------------------------------------------------- areas (V60)

    /**
     * Each product's figure in each open area that keeps stock, at those of {@code branchIds} that have
     * more than one area (STOCK_AREAS_PART_1.md) - "Store 30 · Kitchen 6". An area holding none of a
     * product reads 0. Null when none of the branches has areas, so a response without them is exactly
     * what it was before areas.
     */
    public Map<UUID, List<com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure>> areaFigures(
            Collection<UUID> productIds, List<UUID> branchIds) {
        if (productIds.isEmpty() || branchIds.isEmpty()) {
            return null;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("branchIds", branchIds.toArray(UUID[]::new))
                .addValue("productIds", productIds.toArray(UUID[]::new));
        // The areas a figure is shown for, in the branches' order and each branch's own order.
        List<UUID[]> areas = jdbc.query("""
                SELECT a.id, a.branch_id FROM areas a
                WHERE a.branch_id = ANY(:branchIds) AND a.is_active AND a.keeps_stock
                  AND a.branch_id IN (SELECT branch_id FROM areas WHERE branch_id = ANY(:branchIds)
                                      GROUP BY branch_id HAVING COUNT(*) > 1)
                ORDER BY array_position(:branchIds, a.branch_id), a.sort_order, a.created_at
                """, params, (rs, n) -> new UUID[] {rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class)});
        if (areas.isEmpty()) {
            return null;
        }
        Map<UUID, Map<UUID, Integer>> held = new HashMap<>();
        jdbc.query("""
                SELECT s.product_id, s.area_id, s.quantity_on_hand FROM area_stock s
                WHERE s.product_id = ANY(:productIds) AND s.area_id = ANY(:areaIds)
                """, params.addValue("areaIds", areas.stream().map(area -> area[0]).toArray(UUID[]::new)),
                rs -> {
                    held.computeIfAbsent(rs.getObject("product_id", UUID.class), key -> new HashMap<>())
                            .put(rs.getObject("area_id", UUID.class), rs.getInt("quantity_on_hand"));
                });
        Map<UUID, List<com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure>> figures = new HashMap<>();
        for (UUID product : productIds) {
            Map<UUID, Integer> at = held.getOrDefault(product, Map.of());
            figures.put(product, areas.stream()
                    .map(area -> new com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure(
                            area[0], area[1], at.getOrDefault(area[0], 0)))
                    .toList());
        }
        return figures;
    }

    /**
     * {@code figures} of one product, each with when its area last counted it (Part 3.5): "Last counted today
     * 18:05" on the count sheet. A count from before areas is the branch's original area's (D-E3).
     */
    public List<com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure> withLastCounted(
            UUID productId, List<com.procurepal_services.stock_bridge_api.branch.dto.AreaFigure> figures) {
        if (figures == null || figures.isEmpty()) {
            return figures;
        }
        Map<UUID, java.time.OffsetDateTime> last = new HashMap<>();
        jdbc.query("""
                SELECT COALESCE(m.area_id, o.id) AS area_id, MAX(m.occurred_at) AS at
                FROM stock_movements m LEFT JOIN areas o ON o.branch_id = m.branch_id AND o.is_original
                WHERE m.product_id = :productId AND m.is_count
                GROUP BY COALESCE(m.area_id, o.id)
                """, new MapSqlParameterSource("productId", productId), rs -> {
                    UUID area = rs.getObject("area_id", UUID.class);
                    if (area != null) {
                        last.put(area, rs.getObject("at", java.time.OffsetDateTime.class));
                    }
                });
        return figures.stream().map(figure -> figure.withLastCountedAt(last.get(figure.areaId()))).toList();
    }

    /**
     * The switch (D-E2): {@code branchId}'s figures become its one area's, before a second area exists to
     * share them. At a one-branch company the product's own figure is the truth (the first mode above),
     * so that is what is copied; at a multi-branch company, the branch's row. The products are locked
     * first, in id order like every write, so a write either finished before this (and is copied) or
     * waits for it (and lands in an area). The caller adds the second area in the same transaction.
     */
    public void becomeAreaMode(UUID clientId, UUID branchId, UUID areaId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("branchId", branchId)
                .addValue("areaId", areaId);
        jdbc.queryForList("SELECT id FROM products WHERE client_id = :clientId ORDER BY id FOR UPDATE", params, UUID.class);
        boolean multiBranch = isMultiBranch(clientId);
        if (!multiBranch) {
            // The one branch's row is a copy that can lag the product's figure (Branch Health's mirror
            // drift). From now on it must equal its areas' sum, so it is made exact here, once.
            jdbc.update("""
                    INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
                    SELECT p.client_id, :branchId, p.id, p.quantity_on_hand FROM products p WHERE p.client_id = :clientId
                    ON CONFLICT (branch_id, product_id) DO UPDATE SET quantity_on_hand = EXCLUDED.quantity_on_hand
                    """, params);
        }
        String source = multiBranch
                ? """
                  SELECT bs.client_id, :areaId, bs.branch_id, bs.product_id, bs.quantity_on_hand
                  FROM branch_stock bs WHERE bs.branch_id = :branchId AND bs.quantity_on_hand <> 0
                  """
                : """
                  SELECT p.client_id, :areaId, :branchId, p.id, p.quantity_on_hand
                  FROM products p WHERE p.client_id = :clientId AND p.quantity_on_hand <> 0
                  """;
        int copied = jdbc.update("INSERT INTO area_stock (client_id, area_id, branch_id, product_id, quantity_on_hand) "
                + source
                + " ON CONFLICT (area_id, product_id) DO UPDATE SET quantity_on_hand = EXCLUDED.quantity_on_hand", params);
        log.info("Branch {} of company {} now has areas: {} product figures copied into its first area",
                branchId, clientId, copied);
    }

    /**
     * Moves {@code quantity} of {@code product} from one area of {@code branchId} to another (V63,
     * STOCK_AREAS_PART_2.md): two area figures change, and nothing else - the branch's and the company's
     * figures, the lots and the costs are the same before and after. The caller holds the product's lock
     * and has checked the sending area has it.
     */
    public void moveBetweenAreas(Product product, UUID branchId, UUID fromAreaId, UUID toAreaId, int quantity) {
        if (!isAreaMode(branchId)) {
            throw new IllegalStateException("Branch " + branchId + " has one area: there is nowhere to move stock to");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", product.getClientId())
                .addValue("branchId", branchId)
                .addValue("productId", product.getId());
        String upsert = """
                INSERT INTO area_stock (client_id, area_id, branch_id, product_id, quantity_on_hand)
                VALUES (:clientId, :areaId, :branchId, :productId, :delta)
                ON CONFLICT (area_id, product_id)
                DO UPDATE SET quantity_on_hand = area_stock.quantity_on_hand + :delta
                """;
        jdbc.update(upsert, new MapSqlParameterSource(params.getValues()).addValue("areaId", fromAreaId).addValue("delta", -quantity));
        jdbc.update(upsert, new MapSqlParameterSource(params.getValues()).addValue("areaId", toAreaId).addValue("delta", quantity));
    }

    /**
     * The net of {@code productId}'s moves into and out of {@code areaId} after {@code after} - what a count
     * of the area taken then is carried forward by, beside its stock movements. Gives to an area that uses
     * what it's given are left out: each is a stock-out row, already in the ledger's share.
     */
    public long moveNetAfter(UUID productId, UUID branchId, UUID areaId, java.time.OffsetDateTime after) {
        Long net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN m.to_area_id = :areaId THEN l.quantity ELSE -l.quantity END), 0)
                FROM area_moves m JOIN area_move_lines l ON l.move_id = m.id
                WHERE m.branch_id = :branchId AND l.product_id = :productId AND m.status = 'DONE'
                  AND l.used_movement_id IS NULL AND m.moved_at > :after
                  AND (m.from_area_id = :areaId OR m.to_area_id = :areaId)
                """, new MapSqlParameterSource()
                        .addValue("productId", productId)
                        .addValue("branchId", branchId)
                        .addValue("areaId", areaId)
                        .addValue("after", after), Long.class);
        return net == null ? 0 : net;
    }

    /** What a write is doing, which decides the area it lands in when it names none (D-E10). */
    public enum AreaUse {
        /** Stock arriving: a delivery, an import, a receipt off a waybill. Goes to the delivery area. */
        RECEIVE,
        /** Stock the system sends out of the branch: a waybill, a marketplace dispatch. From the delivery area. */
        DISPATCH,
        /** A person's stock-out, sale, count or adjustment: they must say where, once there is a choice. */
        TAKE
    }

    /** One of a branch's areas, as a write needs to know it. */
    private record AreaRow(UUID id, String name, boolean active, boolean keepsStock, boolean delivery, boolean original) {
    }

    private static final String AREAS_OF_BRANCH = """
            SELECT id, name, is_active, keeps_stock, is_delivery, is_original FROM areas
            WHERE branch_id = :branchId ORDER BY sort_order, created_at
            """;

    /**
     * Whether {@code branchId}'s areas hold its figures: it has ever had two, closed ones included, for
     * the reason the class javadoc gives for closed branches.
     */
    /**
     * The summary's product counts over areas (Part 3.6): {low, out, needing attention}. An area has no level of
     * its own until Part 8, so nothing is "low" there - only out, when the areas hold none of an active product.
     */
    public long[] areaProductCounts(UUID clientId, AreaScope areas) {
        Long out = jdbc.queryForObject("""
                SELECT COUNT(*) FROM products p
                WHERE p.client_id = :clientId AND p.is_active
                  AND COALESCE((SELECT SUM(s.quantity_on_hand) FROM area_stock s
                                WHERE s.product_id = p.id AND s.area_id IN (:scopeAreaIds)), 0) <= 0
                """, AreaScope.bind(areas, new MapSqlParameterSource("clientId", clientId)), Long.class);
        long none = out == null ? 0 : out;
        return new long[] {0, none, none};
    }

    public boolean isShortUsed(UUID areaId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT short_is_used FROM areas WHERE id = :id", new MapSqlParameterSource("id", areaId), Boolean.class));
    }

    public boolean isAreaMode(UUID branchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT COUNT(*) > 1 FROM areas WHERE branch_id = :branchId",
                new MapSqlParameterSource("branchId", branchId), Boolean.class));
    }

    /**
     * The area a write at {@code branchId} is recorded in (D-E10). A named area must be this branch's,
     * open, and one that keeps stock. None named: the branch's only area that keeps stock; else the
     * delivery area for stock arriving or sent away by the system; else refused - a person's stock-out
     * at a branch with a Store and a Kitchen is never recorded at a guess, for the reason {@link
     * BranchRequiredException} gives.
     */
    public UUID resolveAreaForWrite(UUID branchId, UUID requestedAreaId, AreaUse use) {
        return resolveAreaForWrite(branchId, requestedAreaId, use, null);
    }

    /**
     * {@link #resolveAreaForWrite(UUID, UUID, AreaUse)} for a person's own write (STOCK_AREAS_PART_3.md,
     * D-E9): a named area must also be one they work in (refused otherwise, 403), and with none named only
     * the areas they work in are candidates - a cook's older app records in the Kitchen, their only one.
     *
     * @param userId who is writing; null for the system's own writes (waybills, marketplace), which follow
     *     the branch's rules alone.
     */
    public UUID resolveAreaForWrite(UUID branchId, UUID requestedAreaId, AreaUse use, UUID userId) {
        List<AreaRow> areas = areasOf(branchId);
        AreaAccessService.Limits limits = userId == null || areas.size() < 2 ? null : areaAccessService.limits(userId);
        if (requestedAreaId != null) {
            AreaRow area = areas.stream()
                    .filter(row -> row.id().equals(requestedAreaId))
                    .findFirst()
                    .orElseThrow(AreaNotFoundException::new);
            if (!area.active()) {
                throw new AreaClosedException(area.name());
            }
            if (!area.keepsStock()) {
                throw new AreaKeepsNoStockException(area.name());
            }
            if (limits != null && !limits.worksIn(branchId, area.id())) {
                throw new AreaNotAllowedException(area.name());
            }
            return area.id();
        }
        List<AreaRow> stocking = areas.stream()
                .filter(row -> row.active() && row.keepsStock())
                .filter(row -> limits == null || limits.worksIn(branchId, row.id()))
                .toList();
        if (stocking.size() == 1) {
            return stocking.getFirst().id();
        }
        if (use == AreaUse.TAKE) {
            throw new AreaRequiredException();
        }
        return stocking.stream()
                .filter(AreaRow::delivery)
                .map(AreaRow::id)
                .findFirst()
                .orElseThrow(AreaRequiredException::new);
    }

    /**
     * The area {@code movement} happened in: its own, or - for a row from before V60 - its branch's
     * original area, which holds that history (D-E3). Where a reversal puts the stock back.
     */
    public UUID areaOf(StockMovement movement) {
        return movement.getAreaId() != null ? movement.getAreaId() : originalAreaId(movement.getBranchId());
    }

    /** The area holding {@code branchId}'s history from before it had areas. */
    public UUID originalAreaId(UUID branchId) {
        return areasOf(branchId).stream()
                .filter(AreaRow::original)
                .map(AreaRow::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Branch " + branchId + " has no original area"));
    }

    /** Where {@code branchId}'s deliveries arrive, and stock sent to another branch leaves from. */
    public UUID deliveryAreaId(UUID branchId) {
        return areasOf(branchId).stream()
                .filter(AreaRow::delivery)
                .map(AreaRow::id)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Branch " + branchId + " has no delivery area"));
    }

    /**
     * {@code branchId}'s areas. V60's trigger gives every branch its Store, so a branch without one was
     * made by something that went around the database - a repair gives it the Store it should have had,
     * logged, like {@link #createMissingDefaultBranch}.
     */
    private List<AreaRow> areasOf(UUID branchId) {
        MapSqlParameterSource params = new MapSqlParameterSource("branchId", branchId);
        List<AreaRow> areas = jdbc.query(AREAS_OF_BRANCH, params, BranchStockService::areaRow);
        if (!areas.isEmpty()) {
            return areas;
        }
        log.warn("Branch {} had no area; creating its Store", branchId);
        jdbc.update("""
                INSERT INTO areas (client_id, branch_id, name, is_delivery, is_original)
                SELECT client_id, id, 'Store', TRUE, TRUE FROM branches WHERE id = :branchId
                ON CONFLICT DO NOTHING
                """, params);
        return jdbc.query(AREAS_OF_BRANCH, params, BranchStockService::areaRow);
    }

    private static AreaRow areaRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AreaRow(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("is_active"),
                rs.getBoolean("keeps_stock"),
                rs.getBoolean("is_delivery"),
                rs.getBoolean("is_original"));
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
