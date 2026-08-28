package com.procurepal_services.stock_bridge_api.product.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The forgiving lookup, covered generously - it is pure logic with no dependencies, so coverage is
 * cheap, and every case that passes here is an error row a customer never sees. Plain JUnit, no
 * Spring, for the reason {@link UnitOfMeasureTest} already states.
 *
 * <p>Table-driven on purpose: the interesting property is not that any one spelling resolves, it is
 * that the whole vocabulary a Nigerian B2B user might type does. A list is also the shape a future
 * maintainer can extend without reading anything.
 */
class UnitOfMeasureAliasTest {

    /**
     * The vocabulary. Grouped by unit, and every entry is something somebody has plausibly typed
     * into a spreadsheet cell - trade abbreviations off a waybill, ERP export codes, the display
     * label the frontend picker hands out, and the plurals and casings people write without
     * thinking.
     */
    private static Map<String, UnitOfMeasure> vocabulary() {
        Map<String, UnitOfMeasure> table = new LinkedHashMap<>();

        // Codes, the base case, in every casing.
        table.put("KG", UnitOfMeasure.KILOGRAM);
        table.put("kg", UnitOfMeasure.KILOGRAM);
        table.put("Kg", UnitOfMeasure.KILOGRAM);
        table.put("  kg  ", UnitOfMeasure.KILOGRAM);

        // Display labels, exactly as the units endpoint and the frontend picker hand them out.
        table.put("Kilogram (kg)", UnitOfMeasure.KILOGRAM);
        table.put("kilogram (KG)", UnitOfMeasure.KILOGRAM);
        table.put("Liter (L)", UnitOfMeasure.LITER);
        table.put("Metric Ton (t)", UnitOfMeasure.METRIC_TON);
        table.put("Piece", UnitOfMeasure.PIECE);
        table.put("Carton", UnitOfMeasure.CARTON);

        // Bare labels.
        table.put("kilogram", UnitOfMeasure.KILOGRAM);
        table.put("milliliter", UnitOfMeasure.MILLILITER);
        table.put("centimeter", UnitOfMeasure.CENTIMETER);

        // Weight - the aliases a market trader actually writes.
        table.put("kgs", UnitOfMeasure.KILOGRAM);
        table.put("KGS", UnitOfMeasure.KILOGRAM);
        table.put("kilo", UnitOfMeasure.KILOGRAM);
        table.put("kilos", UnitOfMeasure.KILOGRAM);
        table.put("kilogramme", UnitOfMeasure.KILOGRAM);
        table.put("kilograms", UnitOfMeasure.KILOGRAM);
        table.put("K.G", UnitOfMeasure.KILOGRAM);
        table.put("g", UnitOfMeasure.GRAM);
        table.put("gm", UnitOfMeasure.GRAM);
        table.put("grams", UnitOfMeasure.GRAM);
        table.put("gramme", UnitOfMeasure.GRAM);
        table.put("mg", UnitOfMeasure.MILLIGRAM);
        table.put("milligrams", UnitOfMeasure.MILLIGRAM);
        // MT is how cement, fertiliser and grain are quoted here, and it is the UN/CEFACT code.
        table.put("MT", UnitOfMeasure.METRIC_TON);
        table.put("mt", UnitOfMeasure.METRIC_TON);
        table.put("ton", UnitOfMeasure.METRIC_TON);
        table.put("tons", UnitOfMeasure.METRIC_TON);
        table.put("tonne", UnitOfMeasure.METRIC_TON);
        table.put("tonnes", UnitOfMeasure.METRIC_TON);
        table.put("metric ton", UnitOfMeasure.METRIC_TON);
        table.put("metric-ton", UnitOfMeasure.METRIC_TON);

        // Volume.
        table.put("l", UnitOfMeasure.LITER);
        table.put("L", UnitOfMeasure.LITER);
        table.put("ltr", UnitOfMeasure.LITER);
        table.put("ltrs", UnitOfMeasure.LITER);
        table.put("litre", UnitOfMeasure.LITER);
        table.put("litres", UnitOfMeasure.LITER);
        table.put("liters", UnitOfMeasure.LITER);
        table.put("ml", UnitOfMeasure.MILLILITER);
        table.put("mls", UnitOfMeasure.MILLILITER);

        // Length.
        table.put("m", UnitOfMeasure.METER);
        table.put("mtr", UnitOfMeasure.METER);
        table.put("metre", UnitOfMeasure.METER);
        table.put("metres", UnitOfMeasure.METER);
        table.put("mm", UnitOfMeasure.MILLIMETER);
        table.put("cm", UnitOfMeasure.CENTIMETER);
        table.put("cms", UnitOfMeasure.CENTIMETER);

        // Count and packaging - the trade words.
        table.put("bag", UnitOfMeasure.BAG);
        table.put("bags", UnitOfMeasure.BAG);
        table.put("BAGS", UnitOfMeasure.BAG);
        table.put("bg", UnitOfMeasure.BAG);
        table.put("bgs", UnitOfMeasure.BAG);
        table.put("sack", UnitOfMeasure.SACK);
        table.put("sacks", UnitOfMeasure.SACK);
        table.put("ctn", UnitOfMeasure.CARTON);
        table.put("ctns", UnitOfMeasure.CARTON);
        table.put("carton", UnitOfMeasure.CARTON);
        table.put("cartons", UnitOfMeasure.CARTON);
        table.put("crate", UnitOfMeasure.CRATE);
        table.put("crates", UnitOfMeasure.CRATE);
        table.put("crt", UnitOfMeasure.CRATE);
        table.put("pcs", UnitOfMeasure.PIECE);
        table.put("PCS", UnitOfMeasure.PIECE);
        table.put("pc", UnitOfMeasure.PIECE);
        table.put("piece", UnitOfMeasure.PIECE);
        table.put("pieces", UnitOfMeasure.PIECE);
        table.put("each", UnitOfMeasure.PIECE);
        table.put("ea", UnitOfMeasure.PIECE);
        table.put("unit", UnitOfMeasure.PIECE);
        table.put("units", UnitOfMeasure.PIECE);
        table.put("nos", UnitOfMeasure.PIECE);
        table.put("pack", UnitOfMeasure.PACK);
        table.put("packs", UnitOfMeasure.PACK);
        table.put("pkt", UnitOfMeasure.PACK);
        table.put("sachet", UnitOfMeasure.PACK);
        table.put("sachets", UnitOfMeasure.PACK);
        table.put("doz", UnitOfMeasure.DOZEN);
        table.put("dozen", UnitOfMeasure.DOZEN);
        table.put("dozens", UnitOfMeasure.DOZEN);
        table.put("boxes", UnitOfMeasure.BOX);
        table.put("drum", UnitOfMeasure.DRUM);
        table.put("drums", UnitOfMeasure.DRUM);
        table.put("barrel", UnitOfMeasure.DRUM);
        // A keg of oil is a jerrycan is (locally) a gallon - all the same plastic container.
        table.put("keg", UnitOfMeasure.KEG);
        table.put("kegs", UnitOfMeasure.KEG);
        table.put("jerrycan", UnitOfMeasure.KEG);
        table.put("jerry can", UnitOfMeasure.KEG);
        table.put("jerry-can", UnitOfMeasure.KEG);
        table.put("gallon", UnitOfMeasure.KEG);
        table.put("gallons", UnitOfMeasure.KEG);
        table.put("pallet", UnitOfMeasure.PALLET);
        table.put("pallets", UnitOfMeasure.PALLET);
        table.put("bundle", UnitOfMeasure.BUNDLE);
        table.put("bundles", UnitOfMeasure.BUNDLE);
        table.put("roll", UnitOfMeasure.ROLL);
        table.put("rolls", UnitOfMeasure.ROLL);
        table.put("tray", UnitOfMeasure.TRAY);
        table.put("trays", UnitOfMeasure.TRAY);
        table.put("basket", UnitOfMeasure.BASKET);
        table.put("baskets", UnitOfMeasure.BASKET);
        table.put("pair", UnitOfMeasure.PAIR);
        table.put("pairs", UnitOfMeasure.PAIR);
        table.put("set", UnitOfMeasure.SET);
        table.put("sets", UnitOfMeasure.SET);
        table.put("case", UnitOfMeasure.CASE);
        table.put("cases", UnitOfMeasure.CASE);
        table.put("bale", UnitOfMeasure.BALE);
        table.put("bales", UnitOfMeasure.BALE);
        return table;
    }

    private static Stream<Arguments> everySpelling() {
        return vocabulary().entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    @ParameterizedTest(name = "\"{0}\" resolves to {1}")
    @MethodSource("everySpelling")
    void everySpellingResolvesToItsUnit(String spelling, UnitOfMeasure expected) {
        assertThat(UnitOfMeasure.fromCodeOrLabel(spelling)).contains(expected);
    }

    /**
     * The invisible characters a spreadsheet leaves behind must not defeat the lookup - a cell
     * holding "KG" plus a zero-width space is visually identical to one holding "KG", and a user
     * shown "'KG' is not a recognized unit" about it would reasonably conclude the software is
     * broken.
     */
    @ParameterizedTest
    @ValueSource(strings = {"​KG", "KG ", "﻿kg", "kg​​"})
    void invisibleCharactersAreStripped(String spelling) {
        assertThat(UnitOfMeasure.fromCodeOrLabel(spelling)).contains(UnitOfMeasure.KILOGRAM);
    }

    @Test
    void blankAndNullAreSimplyNotProvided() {
        assertThat(UnitOfMeasure.fromCodeOrLabel(null)).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("")).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("   ")).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("​")).isEmpty();
    }

    @Test
    void genuinelyUnknownValuesStillComeBackEmpty() {
        // Forgiving is not the same as credulous: a value nobody could mean has to stay
        // unresolved, or the review screen would never get the chance to ask.
        assertThat(UnitOfMeasure.fromCodeOrLabel("NOT-A-UNIT")).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("widgets")).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("50kg bag")).isEmpty();
    }

    /**
     * The role filter is what keeps "bags" out of a product's base-unit slot and reintroducing the
     * "50kg bag" ambiguity {@link UnitOfMeasureRole} exists to remove.
     */
    @Test
    void roleFilteredLookupRefusesTheOtherRole() {
        assertThat(UnitOfMeasure.fromCodeOrLabel("bags", UnitOfMeasureRole.PACKAGING)).contains(UnitOfMeasure.BAG);
        assertThat(UnitOfMeasure.fromCodeOrLabel("bags", UnitOfMeasureRole.BASE)).isEmpty();
        assertThat(UnitOfMeasure.fromCodeOrLabel("kilos", UnitOfMeasureRole.BASE)).contains(UnitOfMeasure.KILOGRAM);
        assertThat(UnitOfMeasure.fromCodeOrLabel("kilos", UnitOfMeasureRole.PACKAGING)).isEmpty();
    }

    /**
     * Every code and every display label the API hands out must be accepted back verbatim. This is
     * the property that stops the alias table drifting from the enum: add a unit, and this fails
     * until the label form resolves.
     */
    @Test
    void everyCodeAndLabelRoundTripsThroughTheForgivingLookup() {
        assertThat(UnitOfMeasure.all()).allSatisfy(unit -> {
            assertThat(UnitOfMeasure.fromCodeOrLabel(unit.code()))
                    .as("code %s", unit.code())
                    .contains(unit);
            assertThat(UnitOfMeasure.fromCodeOrLabel(unit.label()))
                    .as("label %s", unit.label())
                    .contains(unit);
            assertThat(UnitOfMeasure.fromCodeOrLabel(unit.code(), unit.role()))
                    .as("role-filtered code %s", unit.code())
                    .contains(unit);
        });
    }

    /**
     * The strict gate the REST API depends on must not have been loosened - the whole reason
     * {@code fromCodeOrLabel} is a second method rather than a rewrite of the first.
     */
    @Test
    void strictFromCodeIsUnchanged() {
        assertThat(UnitOfMeasure.fromCode("kg")).contains(UnitOfMeasure.KILOGRAM);
        assertThat(UnitOfMeasure.fromCode("kilo")).isEmpty();
        assertThat(UnitOfMeasure.fromCode("kgs")).isEmpty();
        assertThat(UnitOfMeasure.fromCode("Kilogram (kg)")).isEmpty();
        assertThat(UnitOfMeasure.fromCode("bags")).isEmpty();
    }

    /**
     * Two units claiming the same alias would mean one of them silently stopped resolving. The
     * table throws at class-initialization time if that ever happens; touching it here is what
     * makes the failure land in this test rather than in production.
     */
    @Test
    void noTwoUnitsClaimTheSameSpelling() {
        assertThat(UnitOfMeasure.fromCodeOrLabel("KG")).isPresent();
    }
}
