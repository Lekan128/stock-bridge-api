package com.procurepal_services.stock_bridge_api.product.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The sense checks of BULK_IMPORT_CX_PLAN.md task 1.7, on the real slips found in a real catalog. */
class ProductSetupChecksTest {

    @Test
    void aLengthUnitOnGroceriesIsQuestionedButNotOnCable() {
        assertThat(ProductSetupChecks.lengthUnitWarning("Rice", "MM"))
                .hasValueSatisfying(message -> assertThat(message).contains("Rice").contains("mm").contains("length"));
        assertThat(ProductSetupChecks.lengthUnitWarning("Copper cable 2.5mm", "M")).isEmpty();
        assertThat(ProductSetupChecks.lengthUnitWarning("Ankara fabric", "M")).isEmpty();
        assertThat(ProductSetupChecks.lengthUnitWarning("Rice", "KG")).isEmpty();
    }

    @Test
    void aTinyPackIsQuestioned() {
        assertThat(ProductSetupChecks.packSizeWarning("Pawpaw", "MG", "BAG", new BigDecimal("12")))
                .hasValueSatisfying(message -> assertThat(message)
                        .isEqualTo("A bag of 12 mg is very small for Pawpaw. Did you mean 12 kg?"));
        assertThat(ProductSetupChecks.packSizeWarning("CinematoG", "G", "CARTON", new BigDecimal("12"))).isPresent();
        assertThat(ProductSetupChecks.packSizeWarning("Red oil", "LITER", "KEG", new BigDecimal("30"))).isEmpty();
        assertThat(ProductSetupChecks.packSizeWarning("Water", "ML", "BOTTLE", new BigDecimal("750"))).isEmpty();
        assertThat(ProductSetupChecks.packSizeWarning("Seasoning", "G", "SACHET", new BigDecimal("4"))).isEmpty();
        assertThat(ProductSetupChecks.packSizeWarning("Biro", "PIECE", "PACK", new BigDecimal("10"))).isEmpty();
        assertThat(ProductSetupChecks.packSizeWarning("Rice", "KG", null, null)).isEmpty();
    }

    @Test
    void aPriceFarFromTheLastOneIsQuestionedInBothDirections() {
        assertThat(ProductSetupChecks.priceWarning(new BigDecimal("5940000"), new BigDecimal("42000"),
                        new BigDecimal("5940000"), new BigDecimal("42000"), "a bag"))
                .hasValueSatisfying(message -> assertThat(message).contains("more than 5 times").contains("total"));
        assertThat(ProductSetupChecks.priceWarning(new BigDecimal("3000"), new BigDecimal("42000"),
                        new BigDecimal("3000"), new BigDecimal("42000"), "a bag"))
                .hasValueSatisfying(message -> assertThat(message).contains("less than a fifth").contains("one bag"));
        assertThat(ProductSetupChecks.priceWarning(new BigDecimal("45000"), new BigDecimal("42000"),
                new BigDecimal("45000"), new BigDecimal("42000"), "a bag")).isEmpty();
        assertThat(ProductSetupChecks.priceWarning(new BigDecimal("45000"), null, null, null, "a bag")).isEmpty();
    }

    @Test
    void aDamagedCodeIsRecognisedAndACodeCanBeMadeFromAName() {
        assertThat(ProductSetupChecks.isDamagedSku("28.0")).isTrue();
        assertThat(ProductSetupChecks.isDamagedSku("50.00")).isTrue();
        assertThat(ProductSetupChecks.isDamagedSku("28")).isFalse();
        assertThat(ProductSetupChecks.isDamagedSku("AL-10/09-07")).isFalse();
        assertThat(ProductSetupChecks.isDamagedSku("2.5")).isFalse();

        assertThat(ProductSetupChecks.codeFromName("Bag of Rice", code -> false)).isEqualTo("BAG-OF-RICE");
        assertThat(ProductSetupChecks.codeFromName("Yam", Set.of("YAM", "YAM-2")::contains)).isEqualTo("YAM-3");
        assertThat(ProductSetupChecks.codeFromName("", code -> false)).isEqualTo("ITEM");
    }
}
