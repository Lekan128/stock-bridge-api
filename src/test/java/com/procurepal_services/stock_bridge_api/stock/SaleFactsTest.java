package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.entity.CostBasis;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The money rules of a sale line (SELLING_PRICE_PLAN.md section 4.3), without a database. */
class SaleFactsTest {

    @Test
    void aCartonSoldAtFiveThousandIsFiveThousandNotTwentyFourTimesItsPiecePrice() {
        StockOutRequest oneCarton = out(1, "5000", null, "CARTON", null, null);

        assertThat(SaleFacts.saleAmount(oneCarton)).isEqualByComparingTo("5000.00");
        // The trap this rule exists for: per piece it is 208.33, and 24 of those is 4,999.92.
        assertThat(new BigDecimal("208.33").multiply(BigDecimal.valueOf(24))).isNotEqualByComparingTo("5000.00");
    }

    @Test
    void aTypedLineTotalIsKeptExactlyAndThePriceEachIsDerivedForDisplay() {
        StockOutRequest sixForTenThousand = out(6, null, null, null, null, "10000");

        assertThat(SaleFacts.saleAmount(sixForTenThousand)).isEqualByComparingTo("10000.00");
        assertThat(SaleFacts.enteredPrice(sixForTenThousand)).isEqualByComparingTo("1666.67");
    }

    @Test
    void aPriceOrASaleIdMakesASaleAndNothingElseGuessesAReason() {
        assertThat(SaleFacts.reasonOf(out(1, "100", null, null, null, null))).isEqualTo(OutReason.SALE);
        assertThat(SaleFacts.reasonOf(out(1, null, null, null, UUID.randomUUID(), null))).isEqualTo(OutReason.SALE);
        assertThat(SaleFacts.reasonOf(out(1, null, null, null, null, "50"))).isEqualTo(OutReason.SALE);
        assertThat(SaleFacts.reasonOf(out(1, null, null, null, null, null))).isNull();
        // An explicit reason wins, even with a price beside it.
        assertThat(SaleFacts.reasonOf(out(1, "100", OutReason.EXPIRED, null, null, null))).isEqualTo(OutReason.EXPIRED);
    }

    @Test
    void costIsEachDeliverysOwnPriceWhenEveryUnitIsAccountedFor() {
        SaleFacts.Cost cost = SaleFacts.costOf(
                List.of(draw("100.00", 10), draw("120.00", 5)), 15, new BigDecimal("106.67"));

        assertThat(cost.amount()).isEqualByComparingTo("1600.00");
        assertThat(cost.basis()).isEqualTo(CostBasis.FIFO);
    }

    @Test
    void aDeliveryWithNoPriceOrStockOlderThanTheLedgerIsCostedAtTheAverage() {
        SaleFacts.Cost unpricedLot = SaleFacts.costOf(List.of(draw("100.00", 10), draw(null, 5)), 15, new BigDecimal("100.00"));
        assertThat(unpricedLot.amount()).isEqualByComparingTo("1500.00");
        assertThat(unpricedLot.basis()).isEqualTo(CostBasis.AVERAGE);

        SaleFacts.Cost shortfall = SaleFacts.costOf(List.of(draw("100.00", 10)), 12, new BigDecimal("90.00"));
        assertThat(shortfall.amount()).isEqualByComparingTo("1180.00");
        assertThat(shortfall.basis()).isEqualTo(CostBasis.AVERAGE);
    }

    @Test
    void withNoCostAnywhereTheWholeIsUnknownNotZero() {
        assertThat(SaleFacts.costOf(List.of(draw("100.00", 10), draw(null, 5)), 15, null)).isEqualTo(SaleFacts.Cost.UNKNOWN);
        assertThat(SaleFacts.costOf(List.of(), 3, null)).isEqualTo(SaleFacts.Cost.UNKNOWN);
    }

    private static StockOutRequest out(
            int quantity, String unitPrice, OutReason reason, String unit, UUID saleId, String lineTotal) {
        return new StockOutRequest(
                quantity,
                unitPrice == null ? null : new BigDecimal(unitPrice),
                null,
                unit,
                null,
                null,
                null,
                reason,
                saleId,
                lineTotal == null ? null : new BigDecimal(lineTotal));
    }

    private static SaleFacts.Draw draw(String lotPrice, int quantity) {
        StockMovement lot = StockMovement.builder().unitPriceAtTime(lotPrice == null ? null : new BigDecimal(lotPrice)).build();
        return new SaleFacts.Draw(lot, quantity);
    }
}
