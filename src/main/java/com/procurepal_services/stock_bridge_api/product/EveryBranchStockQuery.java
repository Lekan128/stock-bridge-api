package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The products list's LOW / OK filter in "All branches" (MULTI_BRANCH_BUILD_PLAN.md section 6, Phase 4):
 * LOW is "not out, and not fine at every branch the person sees" - the rule the phone's catalogue uses.
 * Fine at a branch is: holds some, above its level there (else the product's).
 *
 * <p>One query: the products fine everywhere are worked out once (a grouped pass over every branch's
 * figures) and the page and its total come back together. As a JPA specification it ran that pass
 * twice - once for the page, once for the count - 520 ms at 26 branches x 20,000 products, over the
 * section 9 target of 300 ms.
 */
@Component
@RequiredArgsConstructor
class EveryBranchStockQuery {

    /** The list's sortable fields, and their columns. Anything else falls back to the name. */
    private static final Map<String, String> COLUMNS = Map.of(
            "name", "p.name",
            "sku", "p.sku",
            "quantityOnHand", "p.quantity_on_hand",
            "unitPrice", "p.unit_price",
            "createdAt", "p.created_at",
            "updatedAt", "p.updated_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;

    Page<Product> page(
            UUID clientId, String search, Boolean active, UUID categoryId, StockStatus stockStatus, Boolean hasSellingPrice,
            Collection<UUID> scope, Pageable pageable) {
        // Planned for this company each time: a generic plan sizes "one company's rows" by an average
        // company, and a chain's are 100x that (Phase 4, measured at 26 branches x 20k products).
        jdbc.getJdbcTemplate().execute("SET LOCAL plan_cache_mode = force_custom_plan");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("clientId", clientId)
                .addValue("scope", scope.isEmpty() ? List.of(new UUID(0, 0)) : scope)
                .addValue("branches", scope.size());
        StringBuilder where = new StringBuilder("p.client_id = :clientId AND p.quantity_on_hand > 0");
        where.append(stockStatus == StockStatus.LOW ? " AND p.id NOT IN (SELECT product_id FROM fine)" : " AND p.id IN (SELECT product_id FROM fine)");
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(p.name) LIKE :search OR LOWER(p.sku) LIKE :search)");
            params.addValue("search", "%" + search.trim().toLowerCase() + "%");
        }
        if (active != null) {
            where.append(" AND p.is_active = :active");
            params.addValue("active", active);
        }
        if (categoryId != null) {
            where.append(" AND p.company_category_id = :categoryId");
            params.addValue("categoryId", categoryId);
        }
        if (hasSellingPrice != null) {
            where.append(hasSellingPrice ? " AND p.unit_price IS NOT NULL" : " AND p.unit_price IS NULL");
        }
        List<String> order = new ArrayList<>();
        for (Sort.Order sort : pageable.getSort()) {
            String column = COLUMNS.get(sort.getProperty());
            if (column != null) order.add(column + (sort.isAscending() ? " ASC" : " DESC"));
        }
        order.add("p.name ASC");
        order.add("p.id ASC");
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());

        List<UUID> ids = new ArrayList<>();
        long[] total = {0};
        jdbc.query("""
                WITH fine AS MATERIALIZED (
                    SELECT bs.product_id FROM branch_stock bs JOIN products own ON own.id = bs.product_id
                    WHERE bs.client_id = :clientId AND bs.branch_id IN (:scope) AND bs.quantity_on_hand > 0
                      AND (COALESCE(bs.low_stock_threshold, own.low_stock_threshold) IS NULL
                           OR bs.quantity_on_hand > COALESCE(bs.low_stock_threshold, own.low_stock_threshold))
                    GROUP BY bs.product_id HAVING COUNT(*) = :branches)
                SELECT p.id, COUNT(*) OVER () AS total FROM products p
                WHERE\s""" + where + " ORDER BY " + String.join(", ", order) + " LIMIT :limit OFFSET :offset",
                params, rs -> {
                    ids.add(rs.getObject("id", UUID.class));
                    total[0] = rs.getLong("total");
                });
        if (ids.isEmpty()) {
            // Past the last page the window has no row to carry the total; ask for it alone.
            return new PageImpl<>(List.of(), pageable, pageable.getOffset() == 0 ? 0 : countOnly(where.toString(), params));
        }
        Map<UUID, Product> byId = new HashMap<>();
        productRepository.findForSync(clientId, ids).forEach(product -> byId.put(product.getId(), product));
        List<Product> products = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        return new PageImpl<>(products, pageable, total[0]);
    }

    private long countOnly(String where, MapSqlParameterSource params) {
        return jdbc.queryForObject("""
                WITH fine AS MATERIALIZED (
                    SELECT bs.product_id FROM branch_stock bs JOIN products own ON own.id = bs.product_id
                    WHERE bs.client_id = :clientId AND bs.branch_id IN (:scope) AND bs.quantity_on_hand > 0
                      AND (COALESCE(bs.low_stock_threshold, own.low_stock_threshold) IS NULL
                           OR bs.quantity_on_hand > COALESCE(bs.low_stock_threshold, own.low_stock_threshold))
                    GROUP BY bs.product_id HAVING COUNT(*) = :branches)
                SELECT COUNT(*) FROM products p WHERE\s""" + where, params, Long.class);
    }
}
