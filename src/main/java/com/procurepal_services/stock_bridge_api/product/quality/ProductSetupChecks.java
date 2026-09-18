package com.procurepal_services.stock_bridge_api.product.quality;

import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureCategory;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOptions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Sense checks on how a product is set up (BULK_IMPORT_CX_PLAN.md tasks 1.7 and 1.8). Each value
 * on its own is legal - "mm" is a real unit, "12 mg" a real size - so nothing else catches them,
 * and each has turned up in a real catalog: rice counted in millimetres, pawpaw in bags of 12 mg,
 * tomatoes at N5,940,000 a bag.
 *
 * <p>They only ever produce a warning. A cable really is sold by the metre; a price really can
 * treble. The point is that someone looks before it is saved.
 */
public final class ProductSetupChecks {

    /** A price this many times the last one, or this fraction of it, is worth a second look. */
    private static final BigDecimal PRICE_RATIO = new BigDecimal("5");

    /** Containers that hold a lot: a bag or a drum of less than 100 g / 100 ml is almost certainly a slip. */
    private static final Set<String> BULK_CONTAINERS = Set.of(
            "BAG", "SACK", "BASKET", "DRUM", "KEG", "CRATE", "BALE", "PALLET", "CARTON", "CASE", "BOX", "TRAY");

    private static final BigDecimal BULK_MINIMUM = new BigDecimal("0.1");
    private static final BigDecimal ANY_MINIMUM = new BigDecimal("0.001");

    /** Words that make a length unit expected rather than suspicious. */
    private static final Pattern LENGTH_GOODS = Pattern.compile(
            "\\b(cable|wire|rope|cord|fabric|cloth|lace|ribbon|tape|pipe|hose|tube|chain|thread|yarn|"
                    + "carpet|rug|mesh|net|foil|film|sheet|roll|curtain|trim|string|twine|belt|rod|bar)s?\\b",
            Pattern.CASE_INSENSITIVE);

    /** A code an older spreadsheet reader damaged: 28 read as the number 28.0. */
    private static final Pattern DAMAGED_SKU = Pattern.compile("^\\d+\\.0+$");

    private ProductSetupChecks() {
    }

    /** "Rice is measured in mm - a length." Empty for a product whose name says it is sold by length. */
    public static Optional<String> lengthUnitWarning(String productName, String stockUnitCode) {
        Optional<UnitOfMeasure> unit = UnitOfMeasure.fromCode(stockUnitCode);
        if (unit.isEmpty() || unit.get().category() != UnitOfMeasureCategory.LENGTH) {
            return Optional.empty();
        }
        if (productName != null && LENGTH_GOODS.matcher(productName).find()) {
            return Optional.empty();
        }
        return Optional.of("%s is counted in %s, which is a length. Did you mean a weight (kg) or a volume (ml)?"
                .formatted(name(productName), UnitOptions.symbolOf(stockUnitCode)));
    }

    /** "A bag of 12 mg is very small - did you mean 12 kg?" Empty when the size is believable. */
    public static Optional<String> packSizeWarning(
            String productName, String stockUnitCode, String packagingUnitCode, BigDecimal packagingSize) {
        if (packagingUnitCode == null || packagingSize == null || packagingSize.signum() <= 0) {
            return Optional.empty();
        }
        Optional<UnitOfMeasure> unit = UnitOfMeasure.fromCode(stockUnitCode);
        if (unit.isEmpty() || unit.get().factorToCanonical() == null
                || (unit.get().category() != UnitOfMeasureCategory.WEIGHT
                        && unit.get().category() != UnitOfMeasureCategory.VOLUME)) {
            return Optional.empty();
        }
        String container = UnitOfMeasure.fromCodeOrLabel(packagingUnitCode).map(UnitOfMeasure::code)
                .orElse(packagingUnitCode.toUpperCase(Locale.ROOT));
        BigDecimal canonical = packagingSize.multiply(unit.get().factorToCanonical());
        BigDecimal minimum = BULK_CONTAINERS.contains(container) ? BULK_MINIMUM : ANY_MINIMUM;
        if (canonical.compareTo(minimum) >= 0) {
            return Optional.empty();
        }
        String containerWord = UnitOfMeasure.fromCode(container)
                .map(found -> found.label().toLowerCase(Locale.ROOT))
                .orElse(container.toLowerCase(Locale.ROOT));
        String symbol = UnitOptions.symbolOf(stockUnitCode);
        String bigger = unit.get().category() == UnitOfMeasureCategory.WEIGHT ? "kg" : "L";
        return Optional.of("A %s of %s %s is very small for %s. Did you mean %s %s?".formatted(
                containerWord, ImportCopy.count(packagingSize), symbol, name(productName),
                ImportCopy.count(packagingSize), bigger));
    }

    /**
     * "N5,940,000 a bag is more than 5 times the N42,000 you last paid." Both prices per stock
     * unit, so a bag and a kg compare; the words are about {@code perWhat} ("a bag").
     *
     * @param enteredPerOne the price as typed, for the message.
     * @param lastPerOne the last price in the same terms, for the message.
     */
    public static Optional<String> priceWarning(
            BigDecimal newPerStockUnit, BigDecimal lastPerStockUnit,
            BigDecimal enteredPerOne, BigDecimal lastPerOne, String perWhat) {
        if (newPerStockUnit == null || lastPerStockUnit == null
                || newPerStockUnit.signum() <= 0 || lastPerStockUnit.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal ratio = newPerStockUnit.divide(lastPerStockUnit, 6, RoundingMode.HALF_UP);
        String entered = ImportCopy.money(enteredPerOne) + " " + perWhat;
        String last = ImportCopy.money(lastPerOne);
        if (ratio.compareTo(PRICE_RATIO) > 0) {
            return Optional.of("%s is more than %s times the %s you paid last time. Check it is the price of one, "
                    .formatted(entered, PRICE_RATIO, last) + "not the total for the line.");
        }
        if (ratio.multiply(PRICE_RATIO).compareTo(BigDecimal.ONE) < 0) {
            return Optional.of("%s is less than a fifth of the %s you paid last time. Check it is the price of one %s."
                    .formatted(entered, last, perWhat.replaceFirst("^(a|an|per) ", "")));
        }
        return Optional.empty();
    }

    public static boolean isDamagedSku(String sku) {
        return sku != null && DAMAGED_SKU.matcher(sku.trim()).matches();
    }

    /**
     * A readable code for a product that has none it can keep - "RICE-50KG", then "-2", "-3" if
     * taken. Used when a company does not have code generation switched on.
     */
    public static String codeFromName(String name, Predicate<String> taken) {
        String stem = name == null ? "" : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-|-$", "");
        stem = stem.isEmpty() ? "ITEM" : stem.substring(0, Math.min(stem.length(), 12)).replaceAll("-$", "");
        String candidate = stem;
        for (int attempt = 2; taken.test(candidate); attempt++) {
            candidate = stem + "-" + attempt;
        }
        return candidate;
    }

    private static String name(String productName) {
        return productName == null || productName.isBlank() ? "This product" : productName.trim();
    }
}
