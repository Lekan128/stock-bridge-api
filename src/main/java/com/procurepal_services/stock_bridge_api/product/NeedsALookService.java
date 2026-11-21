package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The small line on Products (BRANCH_PRICES_PLAN.md section 9.2; owner 2026-11-21): "3 products at Ikeja to look at ·
 * 2 to fix, 1 to check". Per branch the person has access to; someone with every branch sees them all.
 *
 * <ul>
 *   <li><b>To fix</b>, worked out live so it's never out of date: a made-here item with ingredients and no area to
 *       make it in at a branch with more than one ({@code NO_AREA}); a product with no price at a branch while another
 *       branch has one, with prices per branch on ({@code NO_PRICE}). Fixed means gone.</li>
 *   <li><b>To check</b>, stored ({@code branch_product_checks}): an ingredient the app added to a branch for an item
 *       made with it ({@code ADDED_AS_INGREDIENT}). Cleared by Looks right or by editing the product.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class NeedsALookService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccessService;

    public record Item(UUID productId, String productName, UUID branchId, String branchName, String kind, String reason,
                       String madeIn, UUID aboutProductId, String aboutName) {}

    public record Answer(List<Item> items, long toFix, long toCheck) {}

    /** At {@code branchId}, or every open branch the person sees; one product's only, with {@code productId}. */
    @Transactional(readOnly = true)
    public Answer list(UUID clientId, UUID userId, UUID branchId, UUID productId) {
        List<UUID> sees = branchAccessService.openViewBranchIds(userId);
        Collection<UUID> branches = branchId == null ? sees : sees.contains(branchId) ? List.of(branchId) : List.of();
        if (branches.isEmpty()) {
            return new Answer(List.of(), 0, 0);
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId)
                .addValue("branchIds", branches).addValue("productId", productId);
        // A leading space: a text block drops the one after "WHERE" before it.
        String forBranch = " " + """
                p.client_id = :clientId AND p.is_active AND (CAST(:productId AS uuid) IS NULL OR p.id = :productId)
                AND (p.for_all_branches OR EXISTS (SELECT 1 FROM product_branches pb WHERE pb.product_id = p.id AND pb.branch_id = b.id))
                """;
        List<Item> items = new ArrayList<>();
        // No area to make it in: none of its "Made in" name, none chosen there, and more than one to choose from.
        items.addAll(jdbc.query("""
                SELECT p.id, p.name, b.id AS branch_id, b.name AS branch_name, p.made_in_area_name
                FROM products p JOIN branches b ON b.client_id = p.client_id AND b.is_active AND b.id IN (:branchIds)
                WHERE """ + forBranch + """
                  AND p.made_here AND EXISTS (SELECT 1 FROM product_ingredients pi WHERE pi.product_id = p.id)
                  AND NOT EXISTS (SELECT 1 FROM areas a WHERE a.branch_id = b.id AND a.is_active
                                  AND lower(a.name) = lower(p.made_in_area_name))
                  AND NOT EXISTS (SELECT 1 FROM product_made_in m JOIN areas a ON a.id = m.area_id AND a.is_active
                                  WHERE m.product_id = p.id AND m.branch_id = b.id)
                  AND (SELECT COUNT(*) FROM areas a WHERE a.branch_id = b.id AND a.is_active) > 1
                ORDER BY b.name, p.name
                """, params, (rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), "FIX", "NO_AREA",
                        rs.getString("made_in_area_name"), null, null)));
        // No price at a branch, while another branch has one (prices per branch on).
        items.addAll(jdbc.query("""
                SELECT p.id, p.name, b.id AS branch_id, b.name AS branch_name
                FROM products p JOIN branches b ON b.client_id = p.client_id AND b.is_active AND b.id IN (:branchIds)
                JOIN clients c ON c.id = p.client_id AND c.branch_prices
                WHERE """ + forBranch + """
                  AND b.price_list_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM price_list_prices lp WHERE lp.price_list_id = b.price_list_id AND lp.product_id = p.id)
                  AND EXISTS (SELECT 1 FROM price_list_prices lp JOIN branches ob ON ob.price_list_id = lp.price_list_id AND ob.is_active
                              WHERE lp.product_id = p.id)
                ORDER BY b.name, p.name
                """, params, (rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), "FIX", "NO_PRICE", null, null, null)));
        // Added to a branch for an item made with it.
        items.addAll(jdbc.query("""
                SELECT p.id, p.name, b.id AS branch_id, b.name AS branch_name, k.reason, k.about_product_id, about.name AS about_name
                FROM branch_product_checks k
                JOIN products p ON p.id = k.product_id
                JOIN branches b ON b.id = k.branch_id AND b.is_active AND b.id IN (:branchIds)
                LEFT JOIN products about ON about.id = k.about_product_id
                WHERE k.checked_at IS NULL AND """ + forBranch + """
                ORDER BY b.name, p.name
                """, params, (rs, i) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("branch_id", UUID.class), rs.getString("branch_name"), "CHECK", rs.getString("reason"), null,
                        rs.getObject("about_product_id", UUID.class), rs.getString("about_name"))));
        long toFix = items.stream().filter(item -> item.kind().equals("FIX")).map(item -> item.productId() + ":" + item.branchId()).distinct().count();
        long toCheck = items.stream().filter(item -> item.kind().equals("CHECK")).map(item -> item.productId() + ":" + item.branchId()).distinct().count();
        return new Answer(items, toFix, toCheck);
    }

    /** "Looks right": the product's checks at the branch are done. */
    @Transactional
    public void looksRight(UUID clientId, UUID productId, UUID branchId, UUID userId) {
        jdbc.update("""
                UPDATE branch_product_checks SET checked_at = now(), checked_by = :userId
                WHERE client_id = :clientId AND product_id = :productId AND branch_id = :branchId AND checked_at IS NULL
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("productId", productId)
                        .addValue("branchId", branchId).addValue("userId", userId));
    }

    /** Someone edited the product: whatever it was there to be checked for has been seen. */
    @Transactional
    public void edited(UUID productId, UUID userId) {
        jdbc.update("UPDATE branch_product_checks SET checked_at = now(), checked_by = :userId WHERE product_id = :productId AND checked_at IS NULL",
                new MapSqlParameterSource().addValue("productId", productId).addValue("userId", userId));
    }
}
