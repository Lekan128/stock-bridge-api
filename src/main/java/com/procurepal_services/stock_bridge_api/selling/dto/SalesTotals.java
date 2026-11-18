package com.procurepal_services.stock_bridge_api.selling.dto;

import java.math.BigDecimal;

/**
 * Sales over a set of sale lines (SELLING_PRICE_PLAN.md section 4.7). Cancelled lines are counted
 * in {@code cancelledLineCount} and left out of every other figure.
 *
 * @param salesAmount what every priced line went for.
 * @param profitKnownSalesAmount what the lines with BOTH a price and a cost went for - profit is
 *     only ever worked out over these.
 * @param costAmount what the stock of those same lines had cost.
 * @param grossProfit {@code profitKnownSalesAmount - costAmount}.
 * @param margin profit as a share of what those lines sold for (0.16 is 16%); absent with no such
 *     lines. "Margin", not markup - the plan's section 6.4 says why the UI shows naira first.
 * @param unpricedLineCount lines with no price - "8 sales have no price".
 * @param unknownCostLineCount priced lines whose cost is unknown - "3 have no cost".
 */
public record SalesTotals(
        long saleCount,
        long lineCount,
        BigDecimal salesAmount,
        BigDecimal profitKnownSalesAmount,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal margin,
        long unpricedLineCount,
        long unknownCostLineCount,
        long cancelledLineCount,
        long belowUsualPriceLineCount,
        long belowCostLineCount,
        /** What came in by each way of paying (selling till Step 1). */
        ByPayment byPayment) {

    /**
     * The sales amount split by how the customer paid, with how many sales each. "Not recorded" is a
     * sale from before payments were recorded - never guessed as cash.
     */
    public record ByPayment(
            BigDecimal cashAmount,
            long cashSales,
            BigDecimal transferAmount,
            long transferSales,
            BigDecimal cardAmount,
            long cardSales,
            BigDecimal notRecordedAmount,
            long notRecordedSales,
            // Step 12 (V96): transfers by the account they came into, most first; absent where none was named.
            java.util.List<AccountTotal> transferAccounts) {

        public ByPayment(BigDecimal cashAmount, long cashSales, BigDecimal transferAmount, long transferSales,
                BigDecimal cardAmount, long cardSales, BigDecimal notRecordedAmount, long notRecordedSales) {
            this(cashAmount, cashSales, transferAmount, transferSales, cardAmount, cardSales, notRecordedAmount,
                    notRecordedSales, null);
        }

        public ByPayment withTransferAccounts(java.util.List<AccountTotal> accounts) {
            return new ByPayment(cashAmount, cashSales, transferAmount, transferSales, cardAmount, cardSales,
                    notRecordedAmount, notRecordedSales, accounts == null || accounts.isEmpty() ? null : accounts);
        }
    }

    /** Transfers into one account: "GTBank", how much and in how many sales. */
    public record AccountTotal(java.util.UUID accountId, String bankName, String accountNumber, BigDecimal amount, long sales) {
    }

    /**
     * The same figures with everything about cost taken out - for a person's own till total, which
     * staff without analytics see at the counter. What the stock cost is the owner's business.
     */
    public SalesTotals withByPayment(ByPayment payment) {
        return new SalesTotals(saleCount, lineCount, salesAmount, profitKnownSalesAmount, costAmount, grossProfit, margin,
                unpricedLineCount, unknownCostLineCount, cancelledLineCount, belowUsualPriceLineCount, belowCostLineCount, payment);
    }

    public SalesTotals withoutCost() {
        return new SalesTotals(saleCount, lineCount, salesAmount, null, null, null, null,
                unpricedLineCount, 0, cancelledLineCount, belowUsualPriceLineCount, 0, byPayment);
    }
}
