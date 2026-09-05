package com.procurepal_services.stock_bridge_api.product.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * UNIT_UX_CONTRACT.md section 2.1's algorithm and section 2.2's factors, tested at the level they
 * are defined at - no Spring, no database. Every assertion below is traceable to a line of the
 * contract rather than to the implementation, so this file is the thing that fails if the
 * algorithm drifts from the frozen seam the other six modules were briefed on.
 */
class UnitOptionsTest {

    // ------------------------------------------------------------------ section 2.2: factors

    @Test
    void baseUnitsCarryTheContractsFactorsAndPackagingUnitsCarryNone() {
        assertThat(UnitOfMeasure.MILLIGRAM.factorToCanonical()).isEqualByComparingTo("0.000001");
        assertThat(UnitOfMeasure.GRAM.factorToCanonical()).isEqualByComparingTo("0.001");
        assertThat(UnitOfMeasure.KILOGRAM.factorToCanonical()).isEqualByComparingTo("1");
        assertThat(UnitOfMeasure.METRIC_TON.factorToCanonical()).isEqualByComparingTo("1000");
        assertThat(UnitOfMeasure.MILLILITER.factorToCanonical()).isEqualByComparingTo("0.001");
        assertThat(UnitOfMeasure.LITER.factorToCanonical()).isEqualByComparingTo("1");
        assertThat(UnitOfMeasure.MILLIMETER.factorToCanonical()).isEqualByComparingTo("0.001");
        assertThat(UnitOfMeasure.CENTIMETER.factorToCanonical()).isEqualByComparingTo("0.01");
        assertThat(UnitOfMeasure.METER.factorToCanonical()).isEqualByComparingTo("1");
        assertThat(UnitOfMeasure.PIECE.factorToCanonical()).isEqualByComparingTo("1");

        // "PACKAGING-role constants have no factor - a Bag is not a fixed amount of anything."
        // Asserted on the DECLARED role, because packagingUnits() now also offers PIECE, which is
        // declared BASE and does carry a factor - see UnitOfMeasure.canServeAs.
        assertThat(UnitOfMeasure.packagingUnits())
                .isNotEmpty()
                .filteredOn(unit -> unit.role() == UnitOfMeasureRole.PACKAGING)
                .allSatisfy(unit -> assertThat(unit.factorToCanonical()).isNull());
        assertThat(UnitOfMeasure.PIECE.factorToCanonical()).isEqualByComparingTo("1");
    }

    @Test
    void crossBaseFactorConvertsWithinACategoryAndRefusesAcrossOne() {
        assertThat(UnitOfMeasure.METRIC_TON.factorTo(UnitOfMeasure.KILOGRAM)).contains(new BigDecimal("1000"));
        assertThat(UnitOfMeasure.GRAM.factorTo(UnitOfMeasure.KILOGRAM)).contains(new BigDecimal("0.001"));

        // Cross-category is not a conversion and must never be offered: kg to litres needs a
        // density, which is a property of the goods rather than of the units.
        assertThat(UnitOfMeasure.KILOGRAM.factorTo(UnitOfMeasure.LITER)).isEmpty();
        // A container has no factor, so nothing converts to or from it.
        assertThat(UnitOfMeasure.BAG.factorTo(UnitOfMeasure.KILOGRAM)).isEmpty();
    }

    @Test
    void factorsNeverReachTheWireInScientificNotation() {
        // stripTrailingZeros turns 1000 into 1E+3, which Jackson would write verbatim into a
        // select value and a spreadsheet cell.
        assertThat(UnitOfMeasure.METRIC_TON.factorTo(UnitOfMeasure.KILOGRAM).orElseThrow().toPlainString())
                .isEqualTo("1000");
        assertThat(UnitOfMeasure.METRIC_TON.factorTo(UnitOfMeasure.KILOGRAM).orElseThrow().toString())
                .doesNotContain("E");
    }

    @Test
    void symbolIsTheParentheticalOrTheWholeLabel() {
        // Section 2.1 step 1 pins both halves of this rule by example.
        assertThat(UnitOfMeasure.KILOGRAM.symbol()).isEqualTo("kg");
        assertThat(UnitOfMeasure.PIECE.symbol()).isEqualTo("Piece");
        assertThat(UnitOfMeasure.KILOGRAM.hasSymbolAbbreviation()).isTrue();
        assertThat(UnitOfMeasure.PIECE.hasSymbolAbbreviation()).isFalse();
    }

    // ------------------------------------------------------------- section 2.1: the algorithm

    @Test
    void aKilogramBagProductsSetIsStockUnitThenPackThenSameCategoryBaseUnits() {
        List<UnitOption> options = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50.00"));

        assertThat(options).extracting(UnitOption::code).containsExactly("KG", "BAG", "MG", "G", "T");

        UnitOption kg = options.get(0);
        assertThat(kg.label()).isEqualTo("kg");
        assertThat(kg.factorToStockUnit()).isEqualByComparingTo("1");
        assertThat(kg.isStockUnit()).isTrue();
        assertThat(kg.isDefault()).isFalse();

        UnitOption bag = options.get(1);
        assertThat(bag.label()).isEqualTo("Bag of 50 kg");
        assertThat(bag.factorToStockUnit()).isEqualByComparingTo("50");
        assertThat(bag.isStockUnit()).isFalse();
        // "isDefault is the product's own pack if it has one, else the stock unit."
        assertThat(bag.isDefault()).isTrue();

        // Step 4 never carries the default.
        assertThat(options.subList(2, options.size())).allSatisfy(o -> assertThat(o.isDefault()).isFalse());
        assertThat(options.stream().filter(UnitOption::isDefault)).hasSize(1);
    }

    @Test
    void aProductWithNoPackDefaultsToItsStockUnit() {
        List<UnitOption> options = UnitOptions.forProduct("KG", null, null);
        assertThat(UnitOptions.defaultOption(options).orElseThrow().code()).isEqualTo("KG");
        assertThat(options).extracting(UnitOption::code).doesNotContain("BAG");
    }

    @Test
    void aProductWithNoStockUnitGetsTheSingleEntryUnitsSet() {
        // Section 2.1's last paragraph: the pre-V17 product that never got a unit.
        List<UnitOption> options = UnitOptions.forProduct(null, null, null);
        assertThat(options).hasSize(1);
        UnitOption only = options.get(0);
        assertThat(only.code()).isEmpty();
        assertThat(only.label()).isEqualTo("units");
        assertThat(only.factorToStockUnit()).isEqualByComparingTo("1");
        assertThat(only.isStockUnit()).isTrue();
        assertThat(only.isDefault()).isTrue();
    }

    @Test
    void aSupplierPackMatchingTheProductsOwnExactlyIsNotDuplicated() {
        // Same container AND same size really is the same unit - MULTI_PACK_PER_VENDOR_DESIGN.md
        // section 5. Dedup key is (code, size), not code alone.
        List<UnitOption> options = UnitOptions.forProductAndSupplier(
                "KG", "BAG", new BigDecimal("50"), List.of(new UnitOptions.PackSpec("BAG", new BigDecimal("50"))));
        assertThat(options).extracting(UnitOption::code).containsExactly("KG", "BAG", "MG", "G", "T");
        assertThat(options.get(1).label()).isEqualTo("Bag of 50 kg");
    }

    @Test
    void aSupplierPackSharingTheProductsContainerCodeButADifferentSizeSurvivesAsItsOwnOption() {
        // The bug this whole feature fixes (UNIT_UX_REMEDIATION_PLAN.md section 11.1): a supplier
        // pack sharing a CODE with the product's own pack but differing in SIZE used to be
        // silently dropped. Dedup key is now (code, size), so both survive, distinguished by
        // their label - "Bag of 50 kg" vs "Bag of 25 kg" - which already states the size.
        List<UnitOption> options = UnitOptions.forProductAndSupplier(
                "KG", "BAG", new BigDecimal("50"), List.of(new UnitOptions.PackSpec("BAG", new BigDecimal("25"))));
        assertThat(options).extracting(UnitOption::label)
                .containsExactly("kg", "Bag of 50 kg", "Bag of 25 kg", "mg", "g", "t");

        UnitOption productsOwnBag = options.get(1);
        assertThat(productsOwnBag.factorToStockUnit()).isEqualByComparingTo("50");
        assertThat(productsOwnBag.isDefault()).isTrue();

        UnitOption suppliersBag = options.get(2);
        assertThat(suppliersBag.code()).isEqualTo("BAG");
        assertThat(suppliersBag.factorToStockUnit()).isEqualByComparingTo("25");
        assertThat(suppliersBag.isDefault()).isFalse();
        assertThat(suppliersBag.isPack()).isTrue();

        // Exactly one default in the set regardless - section 2.1's closing rule, unaffected by
        // how many packs share a code.
        assertThat(options.stream().filter(UnitOption::isDefault)).hasSize(1);
    }

    @Test
    void aVendorWithMoreThanOnePackContributesEveryOneOfThem() {
        // The actual feature: a vendor is no longer limited to one pack.
        List<UnitOption> options = UnitOptions.forProductAndSupplier(
                "KG", null, null, List.of(
                        new UnitOptions.PackSpec("BAG", new BigDecimal("50")),
                        new UnitOptions.PackSpec("BAG", new BigDecimal("25"))));
        assertThat(options).extracting(UnitOption::label).contains("Bag of 50 kg", "Bag of 25 kg");
        // Neither of a supplier's packs ever becomes the default when the product has none of its
        // own - the stock unit wins, per section 2.1.
        assertThat(UnitOptions.defaultOption(options).orElseThrow().code()).isEqualTo("KG");
    }

    @Test
    void aSupplierPackWithADifferentContainerIsAddedAndNeverBecomesTheDefault() {
        List<UnitOption> differentContainer = UnitOptions.forProductAndSupplier(
                "KG", "BAG", new BigDecimal("50"), List.of(new UnitOptions.PackSpec("CARTON", new BigDecimal("12"))));
        assertThat(differentContainer).extracting(UnitOption::code).containsExactly("KG", "BAG", "CARTON", "MG", "G", "T");
        UnitOption carton = differentContainer.get(2);
        assertThat(carton.label()).isEqualTo("Carton of 12 kg");
        assertThat(carton.isDefault()).isFalse();
        assertThat(UnitOptions.defaultOption(differentContainer).orElseThrow().code()).isEqualTo("BAG");
    }

    // --------------------------------------------------- section 3.1: extension and resolution

    @Test
    void aPerRequestPackReplacesTheSameContainerInPlaceKeepingItsDefault() {
        List<UnitOption> base = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50"));
        List<UnitOption> extended = UnitOptions.extendedWith(base, "BAG", new BigDecimal("25"), "KG");

        assertThat(extended).extracting(UnitOption::code).containsExactlyElementsOf(
                base.stream().map(UnitOption::code).toList());
        UnitOption bag = extended.get(1);
        assertThat(bag.label()).isEqualTo("Bag of 25 kg");
        assertThat(bag.factorToStockUnit()).isEqualByComparingTo("25");
        assertThat(bag.isDefault()).isTrue();
    }

    @Test
    void aPerRequestPackWithANewContainerIsAppendedAndIsNeverTheDefault() {
        List<UnitOption> base = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50"));
        List<UnitOption> extended = UnitOptions.extendedWith(base, "CARTON", new BigDecimal("12"), "KG");

        assertThat(extended).hasSize(base.size() + 1);
        UnitOption carton = extended.get(extended.size() - 1);
        assertThat(carton.code()).isEqualTo("CARTON");
        assertThat(carton.label()).isEqualTo("Carton of 12 kg");
        assertThat(carton.isDefault()).isFalse();
        assertThat(UnitOptions.defaultOption(extended).orElseThrow().code()).isEqualTo("BAG");
    }

    @Test
    void aPackWithNoSizeIsNotAConversionAndExtendsNothing() {
        List<UnitOption> base = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50"));
        assertThat(UnitOptions.extendedWith(base, "CARTON", null, "KG")).isEqualTo(base);
        assertThat(UnitOptions.extendedWith(base, "CARTON", BigDecimal.ZERO, "KG")).isEqualTo(base);
        assertThat(UnitOptions.extendedWith(base, null, new BigDecimal("12"), "KG")).isEqualTo(base);
    }

    @Test
    void aBlankUnitResolvesToTheStockUnitAndAnUnknownOneResolvesToNothing() {
        List<UnitOption> options = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50"));

        // Non-negotiable 8: absent means the stock unit, factor 1, exactly as before.
        assertThat(UnitOptions.resolve(options, null).orElseThrow().code()).isEqualTo("KG");
        assertThat(UnitOptions.resolve(options, "  ").orElseThrow().code()).isEqualTo("KG");
        // Case-insensitive: "kg" and "KG" are the same answer.
        assertThat(UnitOptions.resolve(options, "bag").orElseThrow().code()).isEqualTo("BAG");
        assertThat(UnitOptions.resolve(options, "CARTON")).isEmpty();
    }

    // --------------------------------------------------------- section 3.1/3.2: the arithmetic

    @Test
    void quantityAndPriceAreConvertedByTheOneFactor() {
        UnitOption bag = UnitOptions.resolve(
                        UnitOptions.forProduct("KG", "BAG", new BigDecimal("50")), "BAG")
                .orElseThrow();

        // The complaint, in one assertion: 20 bags at N45,000/bag is 1,000 kg at N900/kg.
        assertThat(bag.toStockUnitQuantity(20)).isEqualTo(1000);
        assertThat(bag.toStockUnitPrice(new BigDecimal("45000"))).isEqualByComparingTo("900");
    }

    @Test
    void aFactorOfOneReturnsThePriceObjectUntouched() {
        // Non-negotiable 8 made mechanical - a rescaled copy is a difference an equals-based
        // assertion can see, so the identity has to be an identity.
        UnitOption kg =
                UnitOptions.resolve(UnitOptions.forProduct("KG", null, null), null).orElseThrow();
        BigDecimal typed = new BigDecimal("2.50");
        assertThat(kg.toStockUnitPrice(typed)).isSameAs(typed);
        assertThat(kg.toStockUnitQuantity(7)).isEqualTo(7);
        assertThat(kg.toStockUnitPrice(null)).isNull();
    }

    @Test
    void aConversionThatRoundsToZeroReturnsZeroForTheCallerToRefuse() {
        UnitOption gram = UnitOptions.resolve(UnitOptions.forProduct("KG", null, null), "G").orElseThrow();
        // 1 g is 0.001 kg. The zero is real; refusing it is StockManagementService's job.
        assertThat(gram.toStockUnitQuantity(1)).isZero();
        assertThat(gram.toStockUnitQuantity(1000)).isEqualTo(1);
        // HALF_UP, so 500 g is one whole kg rather than none - the rounding rule is the
        // column's shape, not a judgement about the delivery.
        assertThat(gram.toStockUnitQuantity(500)).isEqualTo(1);
        assertThat(gram.toStockUnitQuantity(499)).isZero();
    }

    @Test
    void priceArithmeticIsDoneAtScaleSixBeforeAnyColumnRoundsIt() {
        UnitOption bag = UnitOptions.resolve(
                        UnitOptions.forProduct("KG", "BAG", new BigDecimal("33")), "BAG")
                .orElseThrow();
        // N45,000 per bag of 33 is N1,363.636363... per kg. Rounding that to the column's two
        // decimals before it is multiplied back out by a four-digit quantity moves the total by
        // naira, which is why section 3.2 pins the intermediate scale.
        assertThat(bag.toStockUnitPrice(new BigDecimal("45000"))).isEqualByComparingTo("1363.636364");
    }

    // ------------------------------------------------------------------ the sentence fragments

    @Test
    void spokenPhrasesReadAsEnglishRatherThanAsPickerLabels() {
        List<UnitOption> options = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50"));

        // A symbol abbreviated in parentheses never pluralises and never lower-cases; a word
        // does both, and in a pack phrase only the container pluralises.
        assertThat(UnitOptions.spokenPhrase(options.get(0))).isEqualTo("kg");
        assertThat(UnitOptions.spokenPhrase(options.get(1))).isEqualTo("bags of 50 kg");
        assertThat(UnitOptions.spokenPhrase(UnitOfMeasure.CARTON)).isEqualTo("cartons");
        assertThat(UnitOptions.spokenPhrase(UnitOfMeasure.PIECE)).isEqualTo("pieces");

        assertThat(UnitOptions.countedInPhrase(options.subList(0, 2))).isEqualTo("kg or bags of 50 kg");
    }

    @Test
    void anUnrecognisedUnitIsQuotedBackRatherThanEchoedAsIfUnderstood() {
        assertThat(UnitOptions.spokenPhraseOfSubmitted("CARTON")).isEqualTo("cartons");
        // The forgiving lookup handles near-misses itself - "cartns" is a trailing-plural,
        // punctuation-insensitive match for CARTON - so quoting is reserved for a value this
        // catalog genuinely does not recognise, which is exactly when echoing it back unquoted
        // would read as if we had understood it.
        assertThat(UnitOptions.spokenPhraseOfSubmitted("cartns")).isEqualTo("cartons");
        assertThat(UnitOptions.spokenPhraseOfSubmitted("wheelbarrow")).contains("wheelbarrow").contains("“");
    }

    /**
     * The frozen seam itself. {@code UnitOption} is the one new abstraction of
     * UNIT_UX_CONTRACT.md section 2 and four other modules render from these exact key names -
     * a record component silently renamed, or a boolean accessor that Jackson decides to publish
     * as {@code stockUnit} rather than {@code isStockUnit}, breaks all of them at once and only
     * at runtime. This test is the thing that fails first instead.
     */
    @Test
    void theWireShapeMatchesTheContractsPinnedKeyNames() throws Exception {
        UnitOption bag = UnitOptions.forProduct("KG", "BAG", new BigDecimal("50.00")).get(1);
        String json = new ObjectMapper().writeValueAsString(bag);

        assertThat(json)
                .isEqualTo("{\"code\":\"BAG\",\"label\":\"Bag of 50 kg\",\"factorToStockUnit\":50,"
                        + "\"isStockUnit\":false,\"isDefault\":true,\"isPack\":true}");
    }

    @Test
    void packLabelsAreWrittenTheWayAPersonWritesThem() {
        // Section 1 locks a pack's rendering to one phrase, and 50.00 as stored in a
        // numeric(14,2) column must not surface as "Bag of 50.00 kg".
        assertThat(UnitOptions.packLabel("Bag", new BigDecimal("50.00"), "KG")).isEqualTo("Bag of 50 kg");
        assertThat(UnitOptions.packLabel("Keg", new BigDecimal("0.50"), "LITER")).isEqualTo("Keg of 0.5 L");
    }
}
