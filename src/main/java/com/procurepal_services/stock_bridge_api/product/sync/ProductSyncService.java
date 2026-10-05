package com.procurepal_services.stock_bridge_api.product.sync;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.expected.ExpectedDeliveryService;
import com.procurepal_services.stock_bridge_api.product.ProductManagementService;
import com.procurepal_services.stock_bridge_api.product.StockStatus;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The on-device catalogue's feed (INVENTORY_OFFLINE_AND_CHARACTER_PLAN.md, A3): a full snapshot
 * once, then only what changed. See {@code V37__product_change_log.sql} for how changes are
 * recorded and why the feed never skips one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSyncService {

    public static final int DEFAULT_LIMIT = 2000;
    public static final int MAX_LIMIT = 5000;

    /** The oldest transaction still running; everything older has finished, one way or another. */
    private static final String HORIZON = "pg_snapshot_xmin(pg_current_snapshot())::text::bigint";

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final ProductManagementService productManagementService;
    private final ExpectedDeliveryService expectedDeliveryService;

    /**
     * One page of the full catalogue, in id order.
     *
     * <p>The first page also fixes where the change feed will pick up. The horizon is read BEFORE
     * the page, so every change that could be missing from these pages - made by a transaction
     * still running now, or by any later one - is at or after that cursor and will come through
     * {@link #changes}. Pages read later only ever see newer state, which the feed then confirms.
     */
    @Transactional(readOnly = true)
    public ProductSnapshotPage snapshot(UUID afterId, int limit) {
        UUID tenantId = requireTenantId();
        useCustomPlans();
        String cursor = afterId == null
                ? SyncCursor.beforeHorizon(jdbc.queryForObject("SELECT " + HORIZON, Map.of(), Long.class)).encode()
                : null;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", tenantId)
                .addValue("limit", limit + 1);
        String sql = "SELECT id FROM products WHERE client_id = :clientId";
        if (afterId != null) {
            sql += " AND id > :afterId";
            params.addValue("afterId", afterId);
        }
        List<UUID> ids = jdbc.queryForList(sql + " ORDER BY id LIMIT :limit", params, UUID.class);

        boolean hasMore = ids.size() > limit;
        List<UUID> pageIds = hasMore ? ids.subList(0, limit) : ids;
        List<ProductSyncRow> rows = toRows(tenantId, pageIds);
        UUID nextAfterId = hasMore ? pageIds.get(pageIds.size() - 1) : null;
        Long total = afterId == null
                ? jdbc.queryForObject("SELECT count(*) FROM products WHERE client_id = :clientId",
                        Map.of("clientId", tenantId), Long.class)
                : null;
        return new ProductSnapshotPage(rows, nextAfterId, hasMore, cursor, total);
    }

    /**
     * Every product that changed after {@code cursorText}, each once and in its current state.
     * Only changes from finished transactions are handed out - see the migration.
     */
    @Transactional(readOnly = true)
    public ProductChangesPage changes(String cursorText, int limit) {
        UUID tenantId = requireTenantId();
        useCustomPlans();
        SyncCursor cursor = SyncCursor.decode(cursorText);

        List<ChangeRow> changes = jdbc.query(
                "SELECT id, product_id, xid FROM product_changes "
                        + "WHERE client_id = :clientId AND (xid, id) > (:xid, :id) AND xid < " + HORIZON + " "
                        + "ORDER BY xid, id LIMIT :limit",
                new MapSqlParameterSource()
                        .addValue("clientId", tenantId)
                        .addValue("xid", cursor.xid())
                        .addValue("id", cursor.id())
                        .addValue("limit", limit + 1),
                (rs, rowNum) -> new ChangeRow(rs.getLong("id"), rs.getObject("product_id", UUID.class), rs.getLong("xid")));

        if (changes.isEmpty()) {
            return new ProductChangesPage(List.of(), List.of(), cursor.encode(), false);
        }
        boolean hasMore = changes.size() > limit;
        List<ChangeRow> page = hasMore ? changes.subList(0, limit) : changes;

        Set<UUID> changedIds = new LinkedHashSet<>();
        page.forEach(change -> changedIds.add(change.productId()));
        List<ProductSyncRow> rows = toRows(tenantId, new ArrayList<>(changedIds));
        Set<UUID> stillThere = new java.util.HashSet<>();
        rows.forEach(row -> stillThere.add(row.id()));
        List<UUID> removed = changedIds.stream().filter(id -> !stillThere.contains(id)).toList();

        ChangeRow last = page.get(page.size() - 1);
        return new ProductChangesPage(rows, removed, new SyncCursor(last.xid(), last.id()).encode(), hasMore);
    }

    /**
     * The derived fields come from the same code the Inventory list uses - pack shapes from
     * {@link ProductManagementService#hasMultiplePacksFor}, expected quantities from
     * {@link ExpectedDeliveryService#outstandingByProduct} - so a row on the phone and a row from
     * {@code GET /api/products} can never disagree.
     */
    private List<ProductSyncRow> toRows(UUID tenantId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Product> products = new ArrayList<>(productRepository.findForSync(tenantId, ids));
        products.sort(Comparator.comparing(Product::getId));
        Map<UUID, Boolean> multiplePacks = productManagementService.hasMultiplePacksFor(products);
        Map<UUID, BigDecimal> expected = expectedDeliveryService.outstandingByProduct(
                tenantId, products.stream().map(Product::getId).toList());

        return products.stream()
                .map(product -> new ProductSyncRow(
                        product.getId(),
                        product.getName(),
                        product.getSku(),
                        product.getBarcode(),
                        product.getImageUrl(),
                        product.getCompanyCategory() == null ? null : product.getCompanyCategory().getId(),
                        product.getCompanyCategory() == null ? null : product.getCompanyCategory().getName(),
                        product.getUnitOfMeasure(),
                        product.getPackagingUnit(),
                        product.getPackagingSize(),
                        multiplePacks.getOrDefault(product.getId(), false),
                        product.getUnitPrice(),
                        product.getQuantityOnHand(),
                        product.getIncomingQuantity(),
                        expected.get(product.getId()),
                        product.getLowStockThreshold(),
                        product.isActive(),
                        StockStatus.isLowStock(product.getQuantityOnHand(), product.getLowStockThreshold()),
                        StockStatus.of(product.getQuantityOnHand(), product.getLowStockThreshold())))
                .toList();
    }

    /**
     * Keeps each product's last change in feed order and drops the rest, so the log stays about the
     * size of the catalogue. Safe while phones are mid-sync: a dropped row always has a later row
     * for the same product that is still to be read, and the feed sends current state anyway.
     * Daily, and harmless to run on several instances at once.
     */
    @Scheduled(cron = "${app.products.sync.compaction-cron:0 45 3 * * *}", zone = "UTC")
    @Transactional
    public void compactChangeLog() {
        int deleted = jdbc.update(
                "DELETE FROM product_changes c USING product_changes n "
                        + "WHERE n.product_id = c.product_id AND (n.xid, n.id) > (c.xid, c.id)",
                Map.of());
        if (deleted > 0) {
            log.info("Compacted {} superseded product change rows", deleted);
        }
    }

    /**
     * Plans this transaction's statements for the values they are run with, never a cached generic
     * plan (Phase H, found at 100,000 products).
     *
     * <p>Each page fetches up to 5,000 products by id, and their packs and deliveries, with an
     * {@code IN} list. Planned for the actual ids, Postgres hashes that list. A generic plan - which
     * the driver's prepared statements switch to after a few executions - can't, and it is costed
     * for the average company, which has a handful of products: so it walked the big company's
     * whole {@code client_id} index comparing every row against all 2,000 ids, and a 2,000-row
     * page went from ~70 ms to 3 seconds, a full sync from 10 seconds to two minutes. Re-planning a
     * few statements per page costs a millisecond or two. {@code SET LOCAL} ends with the
     * transaction, so the pooled connection goes back as it came.
     */
    private void useCustomPlans() {
        jdbc.getJdbcTemplate().execute("SET LOCAL plan_cache_mode = force_custom_plan");
    }

    private static UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }

    private record ChangeRow(long id, UUID productId, long xid) {
    }
}
