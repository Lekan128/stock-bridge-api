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
                        // COUNT - BASE role. Still only PIECE: a stock unit must be invariant
                        // across the containers a product arrives in, so a CONTAINER word is
                        // never one. See PACK_ENTRY_REDESIGN.md section 4.
                        "PIECE",
                        // COUNT - PACKAGING role. BOTTLE/SACHET/TIN/TUBE joined this list so that
                        // "Bottle of 750 ml" is expressible at all - it was not before, which is
                        // what pushed the 750 into the units-per-pack column.
                        "PACK", "DOZEN", "BOX", "CARTON", "CASE", "CRATE", "BAG", "SACK", "BALE",
                        "BUNDLE", "DRUM", "KEG", "BOTTLE", "SACHET", "TIN", "TUBE",
                        "PALLET", "SET", "PAIR", "ROLL", "TRAY", "BASKET",
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

        // 19 + the four sealed-retail CONTAINERS of PACK_ENTRY_REDESIGN.md section 4.
        assertThat(countUnits).isEqualTo(23);
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
     * discrete goods; the other 22 COUNT constants are all PACKAGING. All 9 WEIGHT/VOLUME/LENGTH
     * constants are BASE - a product is never "packaged as a kilogram".
     *
     * <p>BOTTLE/SACHET/TIN/TUBE are PACKAGING and that is load-bearing: PACK_ENTRY_REDESIGN.md
     * section 4 records why a container word must never be a stock unit. The same water bought as
     * a 750 ml bottle today and a 2 L keg tomorrow is ONE product with one balance, and
     * unitOfMeasure is immutable once stock moves - so a BOTTLE stock unit would strand the user
     * on a second product for the same water.
     */
    @Test
    void roleAssignmentMatchesTheDocumentedSplit() {
        assertThat(UnitOfMeasure.PIECE.role()).isEqualTo(UnitOfMeasureRole.BASE);
        assertThat(UnitOfMeasure.BOTTLE.role()).isEqualTo(UnitOfMeasureRole.PACKAGING);
        assertThat(UnitOfMeasure.SACHET.role()).isEqualTo(UnitOfMeasureRole.PACKAGING);
        assertThat(UnitOfMeasure.TIN.role()).isEqualTo(UnitOfMeasureRole.PACKAGING);
        assertThat(UnitOfMeasure.TUBE.role()).isEqualTo(UnitOfMeasureRole.PACKAGING);

        Set<UnitOfMeasure> packagingCountUnits = Set.of(
                UnitOfMeasure.PACK, UnitOfMeasure.DOZEN, UnitOfMeasure.BOX, UnitOfMeasure.CARTON, UnitOfMeasure.CASE,
                UnitOfMeasure.CRATE, UnitOfMeasure.BAG, UnitOfMeasure.SACK, UnitOfMeasure.BALE, UnitOfMeasure.BUNDLE,
                UnitOfMeasure.DRUM, UnitOfMeasure.KEG, UnitOfMeasure.PALLET, UnitOfMeasure.SET, UnitOfMeasure.PAIR,
                UnitOfMeasure.ROLL, UnitOfMeasure.TRAY, UnitOfMeasure.BASKET,
                UnitOfMeasure.BOTTLE, UnitOfMeasure.SACHET, UnitOfMeasure.TIN, UnitOfMeasure.TUBE);
        assertThat(packagingCountUnits).hasSize(22);
        assertThat(packagingCountUnits).allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.PACKAGING));

        Set<UnitOfMeasure> weightVolumeLengthUnits = Set.of(
                UnitOfMeasure.MILLIGRAM, UnitOfMeasure.GRAM, UnitOfMeasure.KILOGRAM, UnitOfMeasure.METRIC_TON,
                UnitOfMeasure.MILLILITER, UnitOfMeasure.LITER,
                UnitOfMeasure.MILLIMETER, UnitOfMeasure.CENTIMETER, UnitOfMeasure.METER);
        assertThat(weightVolumeLengthUnits).hasSize(9);
        assertThat(weightVolumeLengthUnits).allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.BASE));

        // PIECE + the 9 weight/volume/length units. UNCHANGED by section 4 - the stock-unit
        // picker must not grow with container words, which is that section's whole correction.
        assertThat(UnitOfMeasure.baseUnits()).hasSize(10);
        // 22 declared PACKAGING + PIECE, which is declared BASE but may serve as a pack: a
        // turmeric sold in 34 g pieces is G + PIECE + 34. See UnitOfMeasure.canServeAs.
        assertThat(UnitOfMeasure.packagingUnits()).hasSize(23);
    }

    /**
     * The two lists deliberately OVERLAP, and only in one direction.
     *
     * <p>They used to partition the enum, because role was a hard gate. That gate also made "a
     * turmeric sold in 34 g PIECEs" undescribable - role is a property of how a PRODUCT uses a
     * unit, not of the code. So every COUNT unit may serve as a pack, while the stock-unit list is
     * untouched: a Bag is still not something a product can be measured in.
     */
    @Test
    void everyCountUnitMayServeAsAPackButTheStockUnitListIsUnchanged() {
        assertThat(UnitOfMeasure.baseUnits())
                .allSatisfy(unit -> assertThat(unit.role()).isEqualTo(UnitOfMeasureRole.BASE));

        assertThat(UnitOfMeasure.packagingUnits()).contains(UnitOfMeasure.PIECE);
        assertThat(UnitOfMeasure.packagingUnits())
                .allSatisfy(unit -> assertThat(unit.category()).isEqualTo(UnitOfMeasureCategory.COUNT));

        // The one-directional part: no weight, volume or length unit is ever a container.
        assertThat(UnitOfMeasure.packagingUnits())
                .noneMatch(unit -> unit.category() != UnitOfMeasureCategory.COUNT);
        assertThat(UnitOfMeasure.baseUnits()).doesNotContain(UnitOfMeasure.BAG, UnitOfMeasure.CARTON);
    }
}
