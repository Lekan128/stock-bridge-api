package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which branches a product is for (V92, owner 2026-11-17): every branch ({@code for_all_branches}), or the ones in
 * {@code product_branches}. A product not for a branch does not show there, and nothing is sold, counted or received
 * for it there - the server refuses, so an old phone can't get round a screen that hides it.
 */
@Service
@RequiredArgsConstructor
public class ProductBranchService {

    private final NamedParameterJdbcTemplate jdbc;

    /** The SQL test "this product (alias {@code p}) is for one of :branchIds" - for list and figure queries. */
    public static String forBranchesSql(String alias) {
        return "(" + alias + ".for_all_branches OR EXISTS (SELECT 1 FROM product_branches pbx WHERE pbx.product_id = "
                + alias + ".id AND pbx.branch_id IN (:forBranchIds)))";
    }

    public boolean isFor(Product product, UUID branchId) {
        if (product.isForAllBranches() || branchId == null) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM product_branches WHERE product_id = :productId AND branch_id = :branchId)",
                new MapSqlParameterSource().addValue("productId", product.getId()).addValue("branchId", branchId),
                Boolean.class));
    }

    /** Selling, counting, receiving at a branch the product isn't for: refused, saying how to fix it. */
    public void requireFor(Product product, UUID branchId) {
        if (!isFor(product, branchId)) {
            throw new ProductNotForBranchException(product.getName(), branchName(branchId));
        }
    }

    /** The product's own branches (empty when it is for all of them). */
    @Transactional(readOnly = true)
    public List<UUID> branchesOf(UUID productId) {
        return jdbc.queryForList("SELECT branch_id FROM product_branches WHERE product_id = :productId ORDER BY created_at",
                new MapSqlParameterSource("productId", productId), UUID.class);
    }

    /** Each product's own branches, for a page of them at once. */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> branchesOf(Collection<UUID> productIds) {
        Map<UUID, List<UUID>> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        jdbc.query("SELECT product_id, branch_id FROM product_branches WHERE product_id IN (:ids) ORDER BY created_at",
                new MapSqlParameterSource("ids", productIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getObject("branch_id", UUID.class));
                });
        return result;
    }

    /**
     * Sets which branches the product is for: all of them, or exactly {@code branchIds} (this company's, at least
     * one). Rows for branches no longer chosen go.
     */
    @Transactional
    public void set(Product product, boolean allBranches, Collection<UUID> branchIds) {
        product.setForAllBranches(allBranches);
        if (allBranches) {
            return;
        }
        Set<UUID> wanted = new LinkedHashSet<>(branchIds == null ? List.of() : branchIds);
        List<UUID> theirs = jdbc.queryForList("SELECT id FROM branches WHERE client_id = :clientId AND id IN (:ids)",
                new MapSqlParameterSource().addValue("clientId", product.getClientId())
                        .addValue("ids", wanted.isEmpty() ? List.of(new UUID(0, 0)) : wanted),
                UUID.class);
        if (theirs.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one branch this product is for.");
        }
        jdbc.update("DELETE FROM product_branches WHERE product_id = :productId AND branch_id NOT IN (:keep)",
                new MapSqlParameterSource().addValue("productId", product.getId()).addValue("keep", theirs));
        for (UUID branchId : theirs) {
            add(product, branchId);
        }
    }

    /** Makes the product for one more branch - a waybill delivering it there, opening stock recorded there. */
    @Transactional
    public void add(Product product, UUID branchId) {
        if (product.isForAllBranches() || branchId == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO product_branches (product_id, branch_id, client_id) VALUES (:productId, :branchId, :clientId)
                ON CONFLICT DO NOTHING
                """,
                new MapSqlParameterSource().addValue("productId", product.getId()).addValue("branchId", branchId)
                        .addValue("clientId", product.getClientId()));
    }

    /** The company's active products the branch doesn't have yet, matching {@code search}, by category then name. */
    @Transactional(readOnly = true)
    public BranchProductsController.ToAddResponse toAdd(UUID clientId, UUID branchId, String search, Boolean madeHere, int limit) {
        requireBranch(clientId, branchId);
        String notThere = """
                FROM products p LEFT JOIN company_categories c ON c.id = p.company_category_id
                WHERE p.client_id = :clientId AND p.is_active AND NOT p.for_all_branches
                  AND NOT EXISTS (SELECT 1 FROM product_branches pb WHERE pb.product_id = p.id AND pb.branch_id = :branchId)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId)
                .addValue("limit", limit);
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + notThere, params, Long.class);
        StringBuilder where = new StringBuilder(notThere);
        if (search != null && !search.isBlank()) {
            where.append(" AND (LOWER(p.name) LIKE :search OR LOWER(p.sku) LIKE :search)");
            params.addValue("search", "%" + search.trim().toLowerCase() + "%");
        }
        if (madeHere != null) {
            where.append(" AND p.made_here = :madeHere");
            params.addValue("madeHere", madeHere);
        }
        Long matching = jdbc.queryForObject("SELECT COUNT(*) " + where, params, Long.class);
        List<BranchProductsController.ProductToAdd> products = jdbc.query(
                "SELECT p.id, p.name, p.sku, c.id AS category_id, c.name AS category_name, p.made_here " + where
                        + " ORDER BY c.name NULLS LAST, p.name, p.id LIMIT :limit",
                params,
                (rs, i) -> new BranchProductsController.ProductToAdd(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sku"),
                        rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getBoolean("made_here")));
        return new BranchProductsController.ToAddResponse(total == null ? 0 : total, matching == null ? 0 : matching, products);
    }

    /** Makes products for the branch - every active one, or these of the company's. No stock moves. */
    @Transactional
    public int addToBranch(UUID clientId, UUID branchId, boolean all, Collection<UUID> productIds) {
        requireBranch(clientId, branchId);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId);
        String chosen;
        if (all) {
            chosen = "";
        } else {
            if (productIds == null || productIds.isEmpty()) {
                return 0;
            }
            chosen = " AND p.id IN (:ids)";
            params.addValue("ids", productIds);
        }
        return jdbc.update("""
                INSERT INTO product_branches (product_id, branch_id, client_id)
                SELECT p.id, :branchId, :clientId FROM products p
                WHERE p.client_id = :clientId AND p.is_active AND NOT p.for_all_branches""" + chosen + "\n" + """
                ON CONFLICT DO NOTHING
                """, params);
    }

    private void requireBranch(UUID clientId, UUID branchId) {
        Boolean theirs = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM branches WHERE id = :branchId AND client_id = :clientId AND is_active)",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("branchId", branchId), Boolean.class);
        if (!Boolean.TRUE.equals(theirs)) {
            throw new com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException();
        }
    }

    private String branchName(UUID branchId) {
        return jdbc.queryForList("SELECT name FROM branches WHERE id = :id", new MapSqlParameterSource("id", branchId), String.class)
                .stream().findFirst().orElse("this branch");
    }
}
