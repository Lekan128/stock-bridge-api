package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.entity.CostBasis;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The arithmetic of a stock-out's sale facts (V46, SELLING_PRICE_PLAN.md section 4.3) - kept out of
 * {@link StockManagementService} so the money rules can be tested one by one, without a database.
 */
final class SaleFacts {

    private SaleFacts() {
    }

    /**
     * Why the stock went out. An explicit reason wins. Without one, a request carrying a price, a
     * sale id or a line total is a {@link OutReason#SALE} - a priced stock-out from a client that
     * predates reasons meant a sale, and still does. Anything else stays null: "No reason given".
     */
    static OutReason reasonOf(StockOutRequest request) {
        if (request.reason() != null) {
            return request.reason();
        }
        if (request.unitPrice() != null || request.saleId() != null || request.lineTotal() != null) {
            return OutReason.SALE;
        }
        return null;
    }

    /**
     * The price per ONE entered unit, as {@code resolveEntry} expects it. A typed price wins; with
     * only a line total, it is the total over the quantity, to the kobo - a display figure, since the
     * sale amount itself is the total exactly ({@link #saleAmount}).
     */
    static BigDecimal enteredPrice(StockOutRequest request) {
        if (request.unitPrice() != null) {
            return request.unitPrice();
        }
        if (request.lineTotal() != null && request.quantity() != null && request.quantity() > 0) {
            return request.lineTotal().divide(BigDecimal.valueOf(request.quantity()), 2, RoundingMode.HALF_UP);
        }
        return null;
    }

    /**
     * What this line was sold for, exactly as charged: the typed line total when there is one, else
     * the quantity AS TYPED times the price AS TYPED - "3 cartons at 5,000" is 15,000, computed in
     * cartons. Never through the per-stock-unit price, which is rounded to kobo: 24 x 208.33 is
     * 4,999.92, and the customer paid 5,000. Null when no price was given.
     */
    static BigDecimal saleAmount(StockOutRequest request) {
        if (request.lineTotal() != null) {
            return request.lineTotal().setScale(2, RoundingMode.HALF_UP);
        }
        if (request.unitPrice() != null && request.quantity() != null) {
            return request.unitPrice().multiply(BigDecimal.valueOf(request.quantity())).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    /** A stock-out's cost and how it was arrived at; {@code amount} is null exactly when UNKNOWN. */
    record Cost(BigDecimal amount, CostBasis basis) {
        static final Cost UNKNOWN = new Cost(null, CostBasis.UNKNOWN);
    }

    /** One delivery a stock-out drew on and how much - the input {@link #costOf} needs. */
    record Draw(StockMovement lot, int quantity) {
    }

    /**
     * The cost of the stock a stock-out used: each unit at the price of the delivery it came from
     * (FIFO). A delivery with no price, or units no delivery accounts for (stock older than the
     * ledger), are costed at the product's average cost at this moment - {@link CostBasis#AVERAGE}.
     * If any part has no cost at all the whole is {@link Cost#UNKNOWN}: half a cost is not a cost.
     *
     * @param draws the deliveries drawn on, in stock units.
     * @param quantity the whole stock-out, in stock units - may exceed the draws (see the shortfall
     *     note on {@code StockManagementService.resolveFifoAllocations}).
     * @param averageCost the product's weighted-average cost per stock unit now; may be null.
     */
    static Cost costOf(List<Draw> draws, int quantity, BigDecimal averageCost) {
        BigDecimal total = BigDecimal.ZERO;
        boolean usedAverage = false;
        int drawn = 0;
        for (Draw draw : draws) {
            BigDecimal price = draw.lot().getUnitPriceAtTime();
            if (price == null) {
                if (averageCost == null) {
                    return Cost.UNKNOWN;
                }
                price = averageCost;
                usedAverage = true;
            }
            total = total.add(price.multiply(BigDecimal.valueOf(draw.quantity())));
            drawn += draw.quantity();
        }
        int shortfall = quantity - drawn;
        if (shortfall > 0) {
            if (averageCost == null) {
                return Cost.UNKNOWN;
            }
            total = total.add(averageCost.multiply(BigDecimal.valueOf(shortfall)));
            usedAverage = true;
        }
        return new Cost(total.setScale(2, RoundingMode.HALF_UP), usedAverage ? CostBasis.AVERAGE : CostBasis.FIFO);
    }
}
