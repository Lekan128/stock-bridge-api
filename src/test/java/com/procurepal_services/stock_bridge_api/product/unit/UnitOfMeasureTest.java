package com.procurepal_services.stock_bridge_api.product.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Plain unit tests, no Spring context - UnitOfMeasure is a static enum with no
 * dependencies, the same reasoning EmailEligibilityTest gives for going
 * dependency-free where the thing under test has none.
 */
class UnitOfMeasureTest {

    @Test
    void exactlySpecifiedUnitsArePresent() {
        Set<String> codes = UnitOfMeasure.all().stream().map(UnitOfMeasure::code).collect(java.util.stream.Collectors.toSet());

        assertThat(codes)
                .containsExactlyInAnyOrder(
                        // COUNT
                        "PIECE", "PACK", "DOZEN", "BOX", "CARTON", "CASE", "CRATE", "BAG", "SACK", "BALE",
                        "BUNDLE", "DRUM", "KEG", "PALLET", "SET", "PAIR", "ROLL", "TRAY", "BASKET",
                        // WEIGHT
                        "MG", "G", "KG", "T",
                        // VOLUME
                        "ML", "LITER",
                        // LENGTH
                        "MM", "CM", "M");
    }

    @Test
    void everyUnitHasExactlyOneCategoryFromTheFixedSet() {
        assertThat(UnitOfMeasure.all())
                .allSatisfy(unit -> assertThat(unit.category()).isNotNull());

        long countUnits = UnitOfMeasure.all().stream()
                .filter(u -> u.category() == UnitOfMeasureCategory.COUNT)
                .count();
        long weightUnits = UnitOfMeasure.all().stream()
                .filter(u -> u.category() == UnitOfMeasureCategory.WEIGHT)
                .count();
        long volumeUnits = UnitOfMeasure.all().stream()
                .filter(u -> u.category() == UnitOfMeasureCategory.VOLUME)
                .count();
        long lengthUnits = UnitOfMeasure.all().stream()
                .filter(u -> u.category() == UnitOfMeasureCategory.LENGTH)
                .count();

        assertThat(countUnits).isEqualTo(19);
        assertThat(weightUnits).isEqualTo(4);
        assertThat(volumeUnits).isEqualTo(2);
        assertThat(lengthUnits).isEqualTo(3);
    }

    @Test
    void fromCodeIsCaseInsensitiveAndTrimsWhitespace() {
        assertThat(UnitOfMeasure.fromCode("KG")).contains(UnitOfMeasure.KILOGRAM);
        assertThat(UnitOfMeasure.fromCode("kg")).contains(UnitOfMeasure.KILOGRAM);
        assertThat(UnitOfMeasure.fromCode("  Kg  ")).contains(UnitOfMeasure.KILOGRAM);
        assertThat(UnitOfMeasure.fromCode("bag")).contains(UnitOfMeasure.BAG);
    }

    @Test
    void fromCodeReturnsEmptyRatherThanThrowingForAnUnknownOrBlankCode() {
        assertThat(UnitOfMeasure.fromCode("NOT-A-UNIT")).isEqualTo(Optional.empty());
        assertThat(UnitOfMeasure.fromCode("")).isEqualTo(Optional.empty());
        assertThat(UnitOfMeasure.fromCode("   ")).isEqualTo(Optional.empty());
        assertThat(UnitOfMeasure.fromCode(null)).isEqualTo(Optional.empty());
    }

    @Test
    void everyCodeIsUniqueAndUppercase() {
        Set<String> codes = UnitOfMeasure.all().stream().map(UnitOfMeasure::code).collect(java.util.stream.Collectors.toSet());
        assertThat(codes).hasSize(UnitOfMeasure.all().size());
        assertThat(UnitOfMeasure.all()).allSatisfy(unit -> assertThat(unit.code()).isEqualTo(unit.code().toUpperCase()));
    }

    @Test
    void everyUnitHasExactlyOneRoleFromTheFixedSet() {
        assertThat(UnitOfMeasure.all()).allSatisfy(unit -> assertThat(unit.role()).isNotNull());
    }

    /**
     * PIECE is the one COUNT-category constant reused as the generic BASE unit for uncounted
     * discrete goods (see its javadoc); the other 18 COUNT constants are all PACKAGING. All 9
     * WEIGHT/VOLUME/LENGTH constants are BASE - a product is never "packaged as a kilogram".
     */
    @Test
    void roleAssignmentMatchesTheDocumentedSplit() {
        assertThat(UnitOfMeasure.PIECE.role()).isEqualTo(UnitOfMeasureRole.BASE);

        Set<UnitOfMeasure> packagingCountUnits = Set.of(
                UnitOfMeasure.PACK, UnitOfMeasure.DOZEN, UnitOfMeasure.BOX, UnitOfMeasure.CARTON, UnitOfMeasure.CASE,
                UnitOfMeasure.CRATE, UnitOfMeasure.BAG, UnitOfMeasure.SACK, UnitOfMeasure.BALE, UnitOfMeasure.BUNDLE,
                UnitOfMeasure.DRUM, UnitOfMeasure.KEG, UnitOfMeasure.PALLET, UnitOfMeasure.SET, UnitOfMeasure.PAIR,
                UnitOfMeasure.ROLL, UnitOfMeasure.TRAY, UnitOfMeasure.BASKET);
        assertThat(packagingCountUnits).hasSize(18);
        assertThat(packagingCountUnits).allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.PACKAGING));

        Set<UnitOfMeasure> weightVolumeLengthUnits = Set.of(
                UnitOfMeasure.MILLIGRAM, UnitOfMeasure.GRAM, UnitOfMeasure.KILOGRAM, UnitOfMeasure.METRIC_TON,
                UnitOfMeasure.MILLILITER, UnitOfMeasure.LITER,
                UnitOfMeasure.MILLIMETER, UnitOfMeasure.CENTIMETER, UnitOfMeasure.METER);
        assertThat(weightVolumeLengthUnits).hasSize(9);
        assertThat(weightVolumeLengthUnits).allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.BASE));

        assertThat(UnitOfMeasure.baseUnits()).hasSize(10); // PIECE + the 9 weight/volume/length units
        assertThat(UnitOfMeasure.packagingUnits()).hasSize(18);
        assertThat(UnitOfMeasure.baseUnits().size() + UnitOfMeasure.packagingUnits().size())
                .isEqualTo(UnitOfMeasure.all().size());
    }

    @Test
    void baseUnitsAndPackagingUnitsPartitionAllUnits() {
        assertThat(UnitOfMeasure.baseUnits()).allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.BASE));
        assertThat(UnitOfMeasure.packagingUnits())
                .allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.PACKAGING));
        assertThat(UnitOfMeasure.baseUnits()).doesNotContainAnyElementsOf(UnitOfMeasure.packagingUnits());
    }
}
