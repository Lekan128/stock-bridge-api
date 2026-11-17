package com.procurepal_services.stock_bridge_api.product;

import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOption;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
 * "Made with" and "Made in" on a made-here item (V95, selling till Step 5; owner 2026-11-17). The ingredients are
 * kept-in-stock products, each for one item in any of its units (1 Derica of rice = 0.6 kg). "Made in" is an area by
 * name, chosen per item: each branch's own area of that name, and for a branch without one, an area chosen for that
 * branch - never a default. Ingredients are optional; an item with none takes nothing when sold.
 */
@Service
@RequiredArgsConstructor
public class MadeWithService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductRepository productRepository;
    private final ProductBranchService productBranchService;

    /** One ingredient as sent: how much of which product for one, in one of its units ("Derica"; null = its stock unit). */
    public record IngredientInput(UUID productId, BigDecimal quantity, String unit) {}

    /** A branch without an area named like "Made in": the area chosen for it there. */
    public record BranchArea(UUID branchId, UUID areaId) {}

    public record MadeWithRequest(String madeIn, List<BranchArea> madeInAt, List<IngredientInput> ingredients) {}

    /** One ingredient as shown: as typed, in its stock unit, and what that costs at its cost price. */
    public record IngredientLine(
            UUID productId, String name, BigDecimal quantity, String unit, BigDecimal stockQuantity, String stockUnit,
            BigDecimal costPrice, BigDecimal cost, List<UnitOption> unitOptions) {}

    /** A branch the item is for, and where it is made there; {@code areaId} null when that branch still needs a choice. */
    public record MadeInAt(UUID branchId, String branchName, UUID areaId, String areaName, boolean chosenForBranch) {}

    /**
     * The whole of it, for the form and the product page. {@code costToMake} is the ingredients at their cost prices,
     * null while any has none; {@code areaNames} every open area name the company has, for "Made in".
     */
    public record MadeWithResponse(
            String madeIn, List<MadeInAt> branches, List<IngredientLine> ingredients, BigDecimal costToMake,
            List<String> areaNames) {}

    @Transactional(readOnly = true)
    public MadeWithResponse get(Product item) {
        List<IngredientLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean allCosted = true;
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT i.ingredient_id, i.quantity, i.entered_unit, i.entered_quantity
                FROM product_ingredients i WHERE i.product_id = :id ORDER BY i.position, i.id
                """, new MapSqlParameterSource("id", item.getId()))) {
            Product ingredient = productRepository.findById((UUID) row.get("ingredient_id")).orElse(null);
            if (ingredient == null) {
                continue;
            }
            BigDecimal stockQuantity = (BigDecimal) row.get("quantity");
            BigDecimal cost = ingredient.getCostPrice() == null
                    ? null
                    : ingredient.getCostPrice().multiply(stockQuantity).setScale(2, RoundingMode.HALF_UP);
            if (cost == null) {
                allCosted = false;
            } else {
                total = total.add(cost);
            }
            BigDecimal entered = (BigDecimal) row.get("entered_quantity");
            lines.add(new IngredientLine(
                    ingredient.getId(), ingredient.getName(),
                    entered != null ? entered : stockQuantity,
                    (String) row.get("entered_unit"),
                    stockQuantity, ingredient.getUnitOfMeasure(), ingredient.getCostPrice(), cost,
                    UnitOptions.forRecording(ingredient)));
        }
        return new MadeWithResponse(
                item.getMadeInAreaName(), madeInAt(item), lines,
                lines.isEmpty() || !allCosted ? null : total, areaNames(item.getClientId()));
    }

    /** Replaces the item's "Made in" and ingredients with these. */
    @Transactional
    public MadeWithResponse set(Product item, MadeWithRequest request) {
        if (!item.isMadeHere()) {
            throw new MadeWithException("Only something made here has ingredients.");
        }
        List<IngredientInput> inputs = request.ingredients() == null ? List.of() : request.ingredients();
        String madeIn = request.madeIn() == null || request.madeIn().isBlank() ? null : request.madeIn().trim();
        UUID clientId = item.getClientId();

        // The ingredients: this company's kept-in-stock products, each once, in a unit it has.
        Set<UUID> seen = new HashSet<>();
        List<Object[]> rows = new ArrayList<>();
        List<Product> ingredients = new ArrayList<>();
        for (IngredientInput input : inputs) {
            if (input.productId() == null || !seen.add(input.productId())) {
                continue;
            }
            Product ingredient = productRepository.findById(input.productId())
                    .filter(found -> found.getClientId().equals(clientId))
                    .orElseThrow(ProductNotFoundException::new);
            if (ingredient.isMadeHere() || ingredient.getId().equals(item.getId())) {
                throw new MadeWithException(ingredient.getName() + " is made here, so it can't be an ingredient.");
            }
            if (input.quantity() == null || input.quantity().signum() <= 0) {
                throw new MadeWithException("Say how much " + ingredient.getName() + " goes into one.");
            }
            List<UnitOption> options = UnitOptions.forRecording(ingredient);
            UnitOption option = UnitOptions.resolve(options, input.unit())
                    .orElseThrow(() -> new MadeWithException(
                            ingredient.getName() + " isn't measured in " + input.unit() + "."));
            BigDecimal stockQuantity = option.exactStockUnits(input.quantity()).setScale(3, RoundingMode.HALF_UP);
            if (stockQuantity.signum() <= 0) {
                throw new MadeWithException("That's too little " + ingredient.getName() + " to record.");
            }
            rows.add(new Object[] {ingredient.getId(), stockQuantity, option.isStockUnit() ? null : option.code(), input.quantity()});
            ingredients.add(ingredient);
        }
        if (!rows.isEmpty() && madeIn == null) {
            throw new MadeWithException("Choose where it is made.");
        }

        // Where it is made at each branch it is for: the area of that name, or the one chosen for that branch.
        Map<UUID, UUID> chosen = new HashMap<>();
        if (request.madeInAt() != null) {
            for (BranchArea at : request.madeInAt()) {
                if (at.branchId() != null && at.areaId() != null) {
                    chosen.put(at.branchId(), at.areaId());
                }
            }
        }
        item.setMadeInAreaName(madeIn);
        productRepository.save(item);
        jdbc.update("DELETE FROM product_made_in WHERE product_id = :id", new MapSqlParameterSource("id", item.getId()));
        List<UUID> branches = branchesFor(item);
        if (madeIn != null) {
            for (UUID branchId : branches) {
                if (areaNamed(branchId, madeIn) != null) {
                    continue;
                }
                UUID areaId = chosen.get(branchId);
                if (areaId == null || !isOpenAreaOf(areaId, branchId)) {
                    if (!rows.isEmpty()) {
                        throw new MadeWithException(
                                branchName(branchId) + " has no " + madeIn + ". Choose where it is made there.");
                    }
                    continue;
                }
                jdbc.update("""
                        INSERT INTO product_made_in (product_id, branch_id, area_id, client_id)
                        VALUES (:productId, :branchId, :areaId, :clientId)
                        """, new MapSqlParameterSource().addValue("productId", item.getId()).addValue("branchId", branchId)
                        .addValue("areaId", areaId).addValue("clientId", clientId));
            }
        }

        jdbc.update("DELETE FROM product_ingredients WHERE product_id = :id", new MapSqlParameterSource("id", item.getId()));
        int position = 0;
        for (Object[] row : rows) {
            jdbc.update("""
                    INSERT INTO product_ingredients
                        (client_id, product_id, ingredient_id, quantity, entered_unit, entered_quantity, position)
                    VALUES (:clientId, :productId, :ingredientId, :quantity, :unit, :entered, :position)
                    """, new MapSqlParameterSource().addValue("clientId", clientId).addValue("productId", item.getId())
                    .addValue("ingredientId", row[0]).addValue("quantity", row[1]).addValue("unit", row[2])
                    .addValue("entered", row[3]).addValue("position", position++));
        }
        // Ingredients follow the item to its branches, at 0 where there is none (owner, 2026-11-17).
        for (Product ingredient : ingredients) {
            for (UUID branchId : branches) {
                productBranchService.add(ingredient, branchId);
            }
        }
        return get(item);
    }

    /** One ingredient to take for a sale: how much for one, from which area at the branch it was sold at. */
    public record Use(UUID ingredientId, BigDecimal perOne, UUID areaId) {}

    /**
     * What one of {@code item} takes at {@code branchId}: each ingredient from the area it is made in there. Empty for
     * an item with no ingredients, or made in an area that uses what it's given (it holds no figure - Step 6).
     */
    @Transactional(readOnly = true)
    public List<Use> usesAt(Product item, UUID branchId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ingredient_id, quantity FROM product_ingredients WHERE product_id = :id ORDER BY position, id",
                new MapSqlParameterSource("id", item.getId()));
        if (rows.isEmpty() || branchId == null) {
            return List.of();
        }
        UUID areaId = areaAt(item, branchId);
        if (areaId == null || !keepsStock(areaId)) {
            return List.of();
        }
        List<Use> uses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            uses.add(new Use((UUID) row.get("ingredient_id"), (BigDecimal) row.get("quantity"), areaId));
        }
        return uses;
    }

    /** Where {@code item} is made at {@code branchId}: the area of its "Made in" name, else the one chosen there. */
    private UUID areaAt(Product item, UUID branchId) {
        if (item.getMadeInAreaName() != null) {
            UUID named = areaNamed(branchId, item.getMadeInAreaName());
            if (named != null) {
                return named;
            }
        }
        return jdbc.queryForList("""
                SELECT m.area_id FROM product_made_in m JOIN areas a ON a.id = m.area_id
                WHERE m.product_id = :productId AND m.branch_id = :branchId AND a.is_active
                """, new MapSqlParameterSource().addValue("productId", item.getId()).addValue("branchId", branchId),
                UUID.class).stream().findFirst().orElse(null);
    }

    private List<MadeInAt> madeInAt(Product item) {
        List<MadeInAt> result = new ArrayList<>();
        for (UUID branchId : branchesFor(item)) {
            UUID named = item.getMadeInAreaName() == null ? null : areaNamed(branchId, item.getMadeInAreaName());
            UUID areaId = named != null ? named : areaAt(item, branchId);
            result.add(new MadeInAt(branchId, branchName(branchId), areaId, areaId == null ? null : areaName(areaId),
                    named == null && areaId != null));
        }
        return result;
    }

    /** The open branches the item is for. */
    private List<UUID> branchesFor(Product item) {
        return jdbc.queryForList("SELECT id FROM branches WHERE client_id = :clientId AND is_active ORDER BY is_default DESC, created_at",
                new MapSqlParameterSource("clientId", item.getClientId()), UUID.class)
                .stream().filter(branchId -> productBranchService.isFor(item, branchId)).toList();
    }

    private UUID areaNamed(UUID branchId, String name) {
        return jdbc.queryForList(
                "SELECT id FROM areas WHERE branch_id = :branchId AND is_active AND lower(name) = lower(:name)",
                new MapSqlParameterSource().addValue("branchId", branchId).addValue("name", name), UUID.class)
                .stream().findFirst().orElse(null);
    }

    private boolean isOpenAreaOf(UUID areaId, UUID branchId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM areas WHERE id = :areaId AND branch_id = :branchId AND is_active)",
                new MapSqlParameterSource().addValue("areaId", areaId).addValue("branchId", branchId), Boolean.class));
    }

    private boolean keepsStock(UUID areaId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT keeps_stock FROM areas WHERE id = :id",
                new MapSqlParameterSource("id", areaId), Boolean.class));
    }

    private List<String> areaNames(UUID clientId) {
        return jdbc.queryForList("""
                SELECT MIN(a.name) FROM areas a JOIN branches b ON b.id = a.branch_id
                WHERE a.client_id = :clientId AND a.is_active AND b.is_active
                GROUP BY lower(a.name) ORDER BY MIN(a.sort_order), MIN(a.name)
                """, new MapSqlParameterSource("clientId", clientId), String.class);
    }

    private String branchName(UUID branchId) {
        return jdbc.queryForObject("SELECT name FROM branches WHERE id = :id", new MapSqlParameterSource("id", branchId), String.class);
    }

    private String areaName(UUID areaId) {
        return jdbc.queryForObject("SELECT name FROM areas WHERE id = :id", new MapSqlParameterSource("id", areaId), String.class);
    }
}
