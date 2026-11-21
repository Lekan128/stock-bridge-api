package com.procurepal_services.stock_bridge_api.selling;

import com.procurepal_services.stock_bridge_api.branch.BranchAccessService;
import com.procurepal_services.stock_bridge_api.entity.Product;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
 * Prices per branch (BRANCH_PRICES_PLAN.md; owner 2026-11-21). With "Each branch can have its own prices" on, every
 * branch has prices: a price list of its own, or the same list as another branch. No branch is special. A product
 * with no row in a branch's list has no price there yet.
 *
 * <ul>
 *   <li>Turning it on: every branch shares one copy of today's prices (the product's one price).</li>
 *   <li>A branch taking its own prices starts with a copy of the ones it used, then they're its alone.</li>
 *   <li>Turning it off: every branch uses the prices of the branch chosen; each list is kept for when it's on again,
 *       and prices changed while it was off go back into the chosen branch's.</li>
 * </ul>
 *
 * {@code products.unit_price} keeps a copy of the main branch's price (else any branch's) for what doesn't read branch
 * prices yet - nobody types into it while this is on.
 */
@Service
@RequiredArgsConstructor
public class BranchPriceService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SellingPriceService sellingPriceService;
    private final BranchAccessService branchAccessService;

    /** How a branch is priced, as its page says it. MAIN is "the same as the main branch", for older callers. */
    public enum Uses { MAIN, OWN, SAME_AS }

    /** One product's price in one list: per stock unit, and as typed with its unit when typed per a pack. */
    public record ListPrice(UUID priceListId, BigDecimal unitPrice, BigDecimal unitPriceEntered, String unitPriceEnteredUnit) {}

    public boolean isOn(UUID clientId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT branch_prices FROM clients WHERE id = :id",
                new MapSqlParameterSource("id", clientId), Boolean.class));
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** Each branch's list (also while the switch is off, for what comes back when it's on). */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> chosenListsByBranch(UUID clientId) {
        Map<UUID, UUID> lists = new HashMap<>();
        jdbc.query("SELECT id, price_list_id FROM branches WHERE client_id = :clientId AND price_list_id IS NOT NULL",
                new MapSqlParameterSource("clientId", clientId),
                rs -> {
                    lists.put(rs.getObject("id", UUID.class), rs.getObject("price_list_id", UUID.class));
                });
        return lists;
    }

    /** How many active products have a price in each list. */
    @Transactional(readOnly = true)
    public Map<UUID, Long> priceCounts(UUID clientId) {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("""
                SELECT lp.price_list_id, COUNT(*) AS n FROM price_list_prices lp JOIN products p ON p.id = lp.product_id AND p.is_active
                WHERE lp.client_id = :clientId GROUP BY lp.price_list_id
                """, new MapSqlParameterSource("clientId", clientId),
                rs -> {
                    counts.put(rs.getObject("price_list_id", UUID.class), rs.getLong("n"));
                });
        return counts;
    }

    /** The products' prices in the lists branches use now; empty while the switch is off. */
    @Transactional(readOnly = true)
    public Map<UUID, List<ListPrice>> pricesOf(UUID clientId, Collection<UUID> productIds) {
        return pricesOf(clientId, productIds, null);
    }

    /**
     * As {@link #pricesOf(UUID, Collection)}, only the lists used at {@code branchIds} - what reaches a phone is
     * limited to the branches its person sees (D-U3). Null: every branch.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ListPrice>> pricesOf(UUID clientId, Collection<UUID> productIds, Collection<UUID> branchIds) {
        Map<UUID, List<ListPrice>> result = new HashMap<>();
        if (productIds.isEmpty() || !isOn(clientId) || (branchIds != null && branchIds.isEmpty())) {
            return result;
        }
        jdbc.query("""
                SELECT lp.product_id, lp.price_list_id, lp.unit_price, lp.unit_price_entered, lp.unit_price_entered_unit
                FROM price_list_prices lp
                WHERE lp.client_id = :clientId AND lp.product_id IN (:ids)
                  AND EXISTS (SELECT 1 FROM branches b WHERE b.price_list_id = lp.price_list_id AND b.is_active
                              AND (:anyBranch OR b.id IN (:branchIds)))
                """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("ids", productIds)
                        .addValue("anyBranch", branchIds == null)
                        .addValue("branchIds", branchIds == null ? List.of(new UUID(0, 0)) : branchIds),
                rs -> {
                    result.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new ArrayList<>()).add(new ListPrice(
                            rs.getObject("price_list_id", UUID.class), rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("unit_price_entered"), rs.getString("unit_price_entered_unit")));
                });
        return result;
    }

    // ------------------------------------------------------------------------------------------------ the switch

    /**
     * Turned on. The first time: one list holding today's prices, shared by every branch. Again after being off:
     * the prices changed meanwhile go into the list every branch used then, and each branch's own come back. A branch
     * with none yet (added while off) uses the main branch's.
     */
    @Transactional
    public void switchedOn(UUID clientId) {
        UUID offList = jdbc.queryForObject("SELECT branch_prices_off_list_id FROM clients WHERE id = :id",
                new MapSqlParameterSource("id", clientId), UUID.class);
        if (offList != null) {
            copyOnePrices(clientId, offList);
        }
        UUID mainList = listOfMain(clientId);
        if (mainList == null) {
            if (offList != null) {
                mainList = offList;
            } else {
                mainList = newList(clientId);
                copyOnePrices(clientId, mainList);
            }
        }
        jdbc.update("UPDATE branches SET price_list_id = :list WHERE client_id = :clientId AND price_list_id IS NULL",
                new MapSqlParameterSource().addValue("list", mainList).addValue("clientId", clientId));
        jdbc.update("UPDATE clients SET branch_prices_off_list_id = NULL WHERE id = :id", new MapSqlParameterSource("id", clientId));
        refreshOnePrice(clientId, null, null);
    }

    /** Turned off: every branch uses {@code branchId}'s prices (the main branch's when null). Each list is kept. */
    @Transactional
    public void switchedOff(UUID clientId, UUID branchId) {
        UUID chosen = branchId == null ? listOfMain(clientId) : branchList(clientId, branchId);
        if (chosen == null) {
            return;
        }
        refreshOnePrice(clientId, chosen, null);
        jdbc.update("UPDATE clients SET branch_prices_off_list_id = :list WHERE id = :id",
                new MapSqlParameterSource().addValue("list", chosen).addValue("id", clientId));
    }

    // ------------------------------------------------------------------------------------------------ branches

    /** A branch choosing its prices: its own (a copy of what it used, then its alone), or another branch's. */
    @Transactional
    public void choose(UUID clientId, UUID branchId, Uses uses, UUID sameAsBranchId) {
        requireOn(clientId);
        UUID current = branchList(clientId, branchId);
        UUID next;
        switch (uses) {
            case OWN -> {
                if (current != null && sharers(clientId, current) == 1) {
                    return;
                }
                next = newList(clientId);
                if (current != null) {
                    jdbc.update("""
                            INSERT INTO price_list_prices (price_list_id, product_id, client_id, unit_price, unit_price_entered,
                                                           unit_price_entered_unit, updated_at, updated_by)
                            SELECT :next, product_id, client_id, unit_price, unit_price_entered, unit_price_entered_unit, now(), updated_by
                            FROM price_list_prices WHERE price_list_id = :current
                            """, new MapSqlParameterSource().addValue("next", next).addValue("current", current));
                }
            }
            case SAME_AS, MAIN -> {
                UUID target = uses == Uses.MAIN ? mainBranch(clientId) : sameAsBranchId;
                if (target == null || target.equals(branchId)) {
                    throw new BranchPriceException("Choose the branch whose prices it uses.");
                }
                next = branchList(clientId, target);
                if (next == null) {
                    throw new BranchPriceException("That branch has no prices yet.");
                }
            }
            default -> throw new IllegalStateException();
        }
        jdbc.update("UPDATE branches SET price_list_id = :list WHERE id = :branchId AND client_id = :clientId",
                new MapSqlParameterSource().addValue("list", next).addValue("branchId", branchId).addValue("clientId", clientId));
        if (branchId.equals(mainBranch(clientId))) {
            refreshOnePrice(clientId, null, null);
        }
        logAll(clientId);
    }

    /** A branch added or reopened while the switch is on: it starts with the main branch's prices. */
    @Transactional
    public void branchOpened(UUID clientId, UUID branchId) {
        if (!isOn(clientId) || branchList(clientId, branchId) != null) {
            return;
        }
        UUID mainList = listOfMain(clientId);
        if (mainList != null) {
            jdbc.update("UPDATE branches SET price_list_id = :list WHERE id = :id",
                    new MapSqlParameterSource().addValue("list", mainList).addValue("id", branchId));
        }
    }

    /** Another branch made main: the product's one price follows its prices. */
    @Transactional
    public void mainChanged(UUID clientId) {
        if (isOn(clientId)) {
            refreshOnePrice(clientId, null, null);
            logAll(clientId);
        }
    }

    // ------------------------------------------------------------------------------------------------ prices

    /**
     * A product's price at a branch, typed per {@code unit} (null: the stock unit) the way a product's price is. Null
     * takes it off: no price there yet. Every branch sharing the list gets it.
     */
    @Transactional
    public void setAtBranch(Product product, UUID branchId, BigDecimal price, String unit, UUID userId) {
        UUID clientId = product.getClientId();
        requireOn(clientId);
        UUID list = branchList(clientId, branchId);
        if (list == null) {
            throw new BranchPriceException(branchName(branchId) + " has no prices yet.");
        }
        requireMayChange(clientId, list, userId);
        SellingPriceService.Snapshot before = current(list, product.getId());
        if (price == null) {
            jdbc.update("DELETE FROM price_list_prices WHERE price_list_id = :list AND product_id = :productId",
                    new MapSqlParameterSource().addValue("list", list).addValue("productId", product.getId()));
            sellingPriceService.recordListChange(product, list, before, null, userId);
        } else {
            if (price.signum() < 0) {
                throw new BranchPriceException("A price can't be below 0.");
            }
            SellingPriceService.Snapshot after = sellingPriceService.convert(product, price, unit);
            jdbc.update("""
                    INSERT INTO price_list_prices (price_list_id, product_id, client_id, unit_price, unit_price_entered,
                                                   unit_price_entered_unit, updated_at, updated_by)
                    VALUES (:list, :productId, :clientId, :unitPrice, :entered, :enteredUnit, now(), :userId)
                    ON CONFLICT (price_list_id, product_id) DO UPDATE SET unit_price = EXCLUDED.unit_price,
                        unit_price_entered = EXCLUDED.unit_price_entered, unit_price_entered_unit = EXCLUDED.unit_price_entered_unit,
                        updated_at = now(), updated_by = EXCLUDED.updated_by
                    """, new MapSqlParameterSource().addValue("list", list).addValue("productId", product.getId())
                            .addValue("clientId", clientId).addValue("unitPrice", after.unitPrice()).addValue("entered", after.entered())
                            .addValue("enteredUnit", after.enteredUnit()).addValue("userId", userId));
            sellingPriceService.recordListChange(product, list, before, after, userId);
        }
        refreshOnePrice(clientId, null, product.getId());
        logChange(clientId, product.getId());
    }

    /**
     * Whether a price sent with a product save is what the branch already has - the form sends every box back, so
     * only a different one is a change. Anything while the switch is off is "current": there's nothing to change.
     */
    @Transactional(readOnly = true)
    public boolean isCurrentAt(Product product, UUID branchId, BigDecimal price, String unit) {
        if (!isOn(product.getClientId())) {
            return true;
        }
        UUID list = branchList(product.getClientId(), branchId);
        if (list == null) {
            return price == null;
        }
        SellingPriceService.Snapshot now = current(list, product.getId());
        if (price == null) {
            return now == null;
        }
        return now != null && now.sameAs(sellingPriceService.convert(product, price, unit));
    }

    /** The main branch, for older callers that send one price: it lands at the main branch. */
    @Transactional(readOnly = true)
    public UUID mainBranch(UUID clientId) {
        return jdbc.queryForList("SELECT id FROM branches WHERE client_id = :clientId AND is_default",
                new MapSqlParameterSource("clientId", clientId), UUID.class).stream().findFirst().orElse(null);
    }

    /** The product's one price as it now stands (the copy of the main branch's), for an entity about to be saved. */
    @Transactional(readOnly = true)
    public SellingPriceService.Snapshot onePrice(UUID productId) {
        return jdbc.query("SELECT unit_price, unit_price_entered, unit_price_entered_unit FROM products WHERE id = :id",
                new MapSqlParameterSource("id", productId),
                (rs, i) -> new SellingPriceService.Snapshot(rs.getBigDecimal("unit_price"), rs.getBigDecimal("unit_price_entered"),
                        rs.getString("unit_price_entered_unit"))).stream().findFirst().orElse(null);
    }

    /** D3 (owner, 2026-11-21): a price can be changed by someone who sees every branch it is used at. */
    public void requireMayChange(UUID clientId, UUID list, UUID userId) {
        if (userId == null || list == null) {
            return;
        }
        Set<UUID> sees = new HashSet<>(branchAccessService.openViewBranchIds(userId));
        List<Map<String, Object>> usedAt = jdbc.queryForList(
                "SELECT id, name FROM branches WHERE client_id = :clientId AND is_active AND price_list_id = :list",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("list", list));
        for (Map<String, Object> branch : usedAt) {
            if (!sees.contains((UUID) branch.get("id"))) {
                throw new BranchPriceException("This price is also used at " + branch.get("name")
                        + ". Someone who works there or at every branch can change it.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ inside

    /**
     * products.unit_price from the branch lists: {@code preferred}'s price, else the main branch's, else the latest
     * any branch has. One product, or all of the company's. A single product with no branch price left has none.
     */
    private void refreshOnePrice(UUID clientId, UUID preferred, UUID productId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("clientId", clientId)
                .addValue("preferred", preferred).addValue("main", listOfMain(clientId)).addValue("productId", productId);
        String one = productId == null ? "" : " AND lp.product_id = :productId";
        jdbc.update("""
                UPDATE products p SET unit_price = src.unit_price, unit_price_entered = src.unit_price_entered,
                                      unit_price_entered_unit = src.unit_price_entered_unit
                FROM (SELECT DISTINCT ON (lp.product_id) lp.product_id, lp.unit_price, lp.unit_price_entered, lp.unit_price_entered_unit
                      FROM price_list_prices lp
                      WHERE lp.client_id = :clientId""" + one + """

                        AND EXISTS (SELECT 1 FROM branches b WHERE b.price_list_id = lp.price_list_id)
                      ORDER BY lp.product_id, (lp.price_list_id = CAST(:preferred AS uuid)) IS TRUE DESC,
                               (lp.price_list_id = CAST(:main AS uuid)) IS TRUE DESC, lp.updated_at DESC) src
                WHERE p.id = src.product_id
                """, params);
        if (productId != null) {
            jdbc.update("""
                    UPDATE products p SET unit_price = NULL, unit_price_entered = NULL, unit_price_entered_unit = NULL
                    WHERE p.id = :productId AND NOT EXISTS (
                        SELECT 1 FROM price_list_prices lp JOIN branches b ON b.price_list_id = lp.price_list_id
                        WHERE lp.product_id = p.id)
                    """, params);
        }
    }

    /** The one price of every product into {@code list} (the first time on, and after being off). */
    private void copyOnePrices(UUID clientId, UUID list) {
        jdbc.update("""
                INSERT INTO price_list_prices (price_list_id, product_id, client_id, unit_price, unit_price_entered,
                                               unit_price_entered_unit, updated_at)
                SELECT :list, p.id, p.client_id, p.unit_price, p.unit_price_entered, p.unit_price_entered_unit, now()
                FROM products p WHERE p.client_id = :clientId AND p.unit_price IS NOT NULL
                ON CONFLICT (price_list_id, product_id) DO UPDATE SET unit_price = EXCLUDED.unit_price,
                    unit_price_entered = EXCLUDED.unit_price_entered, unit_price_entered_unit = EXCLUDED.unit_price_entered_unit,
                    updated_at = now()
                """, new MapSqlParameterSource().addValue("list", list).addValue("clientId", clientId));
    }

    private void requireOn(UUID clientId) {
        if (!isOn(clientId)) {
            throw new BranchPriceException("Each branch having its own prices is off for your company.");
        }
    }

    private SellingPriceService.Snapshot current(UUID list, UUID productId) {
        List<SellingPriceService.Snapshot> rows = jdbc.query("""
                SELECT unit_price, unit_price_entered, unit_price_entered_unit FROM price_list_prices
                WHERE price_list_id = :list AND product_id = :productId
                """, new MapSqlParameterSource().addValue("list", list).addValue("productId", productId),
                (rs, i) -> new SellingPriceService.Snapshot(rs.getBigDecimal("unit_price"), rs.getBigDecimal("unit_price_entered"),
                        rs.getString("unit_price_entered_unit")));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UUID listOfMain(UUID clientId) {
        List<UUID> found = jdbc.queryForList("SELECT price_list_id FROM branches WHERE client_id = :clientId AND is_default",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
        return found.isEmpty() ? null : found.get(0);
    }

    private UUID branchList(UUID clientId, UUID branchId) {
        List<UUID> found = jdbc.queryForList(
                "SELECT price_list_id FROM branches WHERE id = :branchId AND client_id = :clientId",
                new MapSqlParameterSource().addValue("branchId", branchId).addValue("clientId", clientId), UUID.class);
        if (found.isEmpty()) {
            throw new com.procurepal_services.stock_bridge_api.branch.BranchNotFoundException();
        }
        return found.get(0);
    }

    private long sharers(UUID clientId, UUID list) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM branches WHERE client_id = :clientId AND price_list_id = :list",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("list", list), Long.class);
        return n == null ? 0 : n;
    }

    private UUID newList(UUID clientId) {
        return jdbc.queryForObject("INSERT INTO price_lists (client_id) VALUES (:clientId) RETURNING id",
                new MapSqlParameterSource("clientId", clientId), UUID.class);
    }

    private String branchName(UUID branchId) {
        return jdbc.queryForList("SELECT name FROM branches WHERE id = :id", new MapSqlParameterSource("id", branchId), String.class)
                .stream().findFirst().orElse("That branch");
    }

    /** Phones hear of a new price on their next sync (V37's log). */
    private void logChange(UUID clientId, UUID productId) {
        jdbc.queryForList("SELECT log_product_change(:clientId, :productId)",
                new MapSqlParameterSource().addValue("clientId", clientId).addValue("productId", productId));
    }

    private void logAll(UUID clientId) {
        jdbc.queryForList("SELECT log_product_change(client_id, id) FROM products WHERE client_id = :id AND is_active",
                new MapSqlParameterSource("id", clientId));
    }
}
