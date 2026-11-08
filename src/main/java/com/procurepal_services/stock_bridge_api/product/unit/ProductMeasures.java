package com.procurepal_services.stock_bridge_api.product.unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The rules for a product's own units (UNITS_PLAN.md Part 1, D-U2, D-U3): the measures we suggest, the spellings an
 * import may use for them, and what makes a measure acceptable.
 *
 * <h2>Suggestions, not a fixed list</h2>
 * Derica, Mudu, Congo and the rest are how grains, garri and beans are measured in a Nigerian market or kitchen, and
 * are offered first. A company can name its own ("Small bowl", "Ladle"): a kitchen's own words matter more than ours
 * (D-U2). None comes with a size - a derica of rice and a derica of beans weigh different amounts, and a figure from
 * us would be wrong for somebody (D-U3). The kitchen weighs one and types it.
 */
public final class ProductMeasures {

    /** Longest name: {@code stock_movements.entered_unit} is varchar(32), and a phone button has to fit it. */
    public static final int MAX_NAME = 24;

    /** Places a measure's size is kept to: the ledger's own (V81), so a measure never asks for less than it holds. */
    public static final int SIZE_SCALE = Quantities.SCALE;

    /** At most this many on a product: they are buttons on a phone. */
    public static final int MAX_MEASURES = 8;

    /**
     * Suggested first, in this order, each with the other spellings people write it with. Plain "bucket" and "tin"
     * are left out of the aliases on purpose: they're as often a pack as a measure.
     */
    public static final Map<String, List<String>> SUGGESTED = suggested();

    private ProductMeasures() {
    }

    private static Map<String, List<String>> suggested() {
        Map<String, List<String>> list = new LinkedHashMap<>();
        list.put("Derica", List.of("Derika", "Derica tin"));
        list.put("Cup", List.of("Cups"));
        list.put("Mudu", List.of("Mudun", "Mudus"));
        list.put("Congo", List.of("Kongo"));
        list.put("Paint", List.of("Paint bucket", "Paint rubber"));
        list.put("Custard", List.of("Custard bucket", "Custard rubber"));
        list.put("Tiya", List.of("Tia"));
        list.put("Kwano", List.of("Kwanno"));
        list.put("Plate", List.of("Plates"));
        list.put("Bowl", List.of("Bowls"));
        list.put("Spoon", List.of("Spoons", "Cooking spoon"));
        list.put("Ladle", List.of("Ladles"));
        return java.util.Collections.unmodifiableMap(list);
    }

    /** "Derica tin" -> "Derica": a typed word as the suggestion it spells, for an import cell. Empty: not one of ours. */
    public static Optional<String> suggestionFor(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.trim();
        for (Map.Entry<String, List<String>> entry : SUGGESTED.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(wanted)
                    || entry.getValue().stream().anyMatch(alias -> alias.equalsIgnoreCase(wanted))) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    /**
     * The product's measure a typed word names - by its own name, or by one of ours it is a spelling of ("derika" is
     * the product's "Derica"). Hidden ones included only when asked for: an entry recorded before it was hidden still
     * has to read, and a phone that saved "5 cups" offline before the cup was hidden still has to send.
     */
    public static Optional<ProductMeasure> find(List<ProductMeasure> measures, String typed, boolean includeHidden) {
        if (measures == null || typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String wanted = typed.trim();
        Predicate<ProductMeasure> offered = measure -> includeHidden || !measure.hiddenNow();
        Optional<ProductMeasure> byName = measures.stream()
                .filter(offered)
                .filter(measure -> measure.name().equalsIgnoreCase(wanted))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }
        return suggestionFor(wanted).flatMap(suggestion -> measures.stream()
                .filter(offered)
                .filter(measure -> measure.name().equalsIgnoreCase(suggestion))
                .findFirst());
    }

    /** The measures new entries may be made in: the hidden ones left out. */
    public static List<ProductMeasure> offered(List<ProductMeasure> measures) {
        return measures == null ? List.of() : measures.stream().filter(measure -> !measure.hiddenNow()).toList();
    }

    /**
     * {@code requested} made fit to keep, or the reason it can't be - in words for the person who typed it. Names are
     * trimmed and their first letter capitalised ("derica" is kept as "Derica"); sizes keep at most three places.
     *
     * @param stockUnitCode the product's stock unit; a measure is a number of it, so there has to be one.
     * @param packCode the product's own pack ("BAG"): a measure called "Bag" would be read as the pack.
     */
    public static List<ProductMeasure> validate(List<ProductMeasure> requested, String stockUnitCode, String packCode) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (stockUnitCode == null || stockUnitCode.isBlank()) {
            throw new InvalidMeasureException("Choose what this product is counted in first: each unit is a number of it.");
        }
        if (requested.stream().filter(measure -> !measure.hiddenNow()).count() > MAX_MEASURES) {
            throw new InvalidMeasureException("A product can have up to " + MAX_MEASURES + " units of its own.");
        }
        List<ProductMeasure> kept = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (ProductMeasure measure : requested) {
            String name = measure.name() == null ? "" : measure.name().trim().replaceAll("\\s+", " ");
            if (name.isEmpty()) {
                throw new InvalidMeasureException("Give each unit a name, like Derica or Cup.");
            }
            if (name.length() > MAX_NAME) {
                throw new InvalidMeasureException("“" + name + "” is too long for a unit name. Keep it to " + MAX_NAME
                        + " letters.");
            }
            name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new InvalidMeasureException("“" + name + "” is on this product twice.");
            }
            if (isTakenByAUnit(name) || (packCode != null && name.equalsIgnoreCase(packCode))) {
                throw new InvalidMeasureException("“" + name + "” is already a unit or a pack here. Call it something else,"
                        + " like “" + name + " (kitchen)”.");
            }
            BigDecimal size = measure.size();
            if (size == null || size.signum() <= 0) {
                throw new InvalidMeasureException("Say how much one " + name + " holds.");
            }
            if (Decimals.plain(size).scale() > SIZE_SCALE) {
                throw new InvalidMeasureException("One " + name + " can be given to 3 decimal places, like 0.625.");
            }
            kept.add(new ProductMeasure(name, Decimals.plain(size), measure.hiddenNow() ? Boolean.TRUE : null));
        }
        return List.copyOf(kept);
    }

    /** A name a unit code or a unit's label already answers to - "KG", "Kilogram", "Bag" - so it would mean two things. */
    private static boolean isTakenByAUnit(String name) {
        return UnitOfMeasure.fromCodeOrLabel(name).isPresent() || UnitOfMeasureAliases.resolve(name).isPresent();
    }
}
