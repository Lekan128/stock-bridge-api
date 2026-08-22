package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntry;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns ledger rows into statement lines, running balance and all.
 *
 * <h2>Why this is shared between the vendor statement and the operator's batch view</h2>
 * Because an operator investigating a payment dispute should be reading the exact
 * rows the vendor is looking at, not a parallel operator-only rendering of them. If
 * the two surfaces formatted their own lines, the first serious disagreement would
 * be about which screen was right rather than about the money.
 *
 * <h2>The running balance means different things in the two contexts, on purpose</h2>
 * It is always "the cumulative total after this line, starting from the opening
 * figure you passed in". On a statement that opening figure is the vendor's balance
 * before the window, so the column is their account balance. On a batch it is zero,
 * so the column is the batch's own subtotal and the last row equals the batch's net.
 * Both are what the reader of that screen wants, and both are the same arithmetic.
 */
final class StatementLines {

    private StatementLines() {
    }

    /**
     * @param entries in the order they should print - oldest first. The running
     *     balance is meaningless in any other order, so callers use the repository's
     *     ordered finders rather than sorting afterwards.
     * @param openingBalance what the running balance starts from.
     * @param context {@code orderItemId -> [orderNumber, productName, quantity]},
     *     from {@code OrderItemRepository.findStatementContext}. A missing entry
     *     yields nulls rather than failing: a payout line legitimately has no order,
     *     and a statement should still print if one order line has since been purged.
     */
    static List<VendorStatementLine> render(
            List<VendorLedgerEntry> entries, BigDecimal openingBalance, Map<UUID, Object[]> context) {
        List<VendorStatementLine> lines = new ArrayList<>(entries.size());
        BigDecimal running = openingBalance == null ? BigDecimal.ZERO : openingBalance;

        for (VendorLedgerEntry entry : entries) {
            running = running.add(entry.getAmount());
            Object[] ctx = entry.getOrderItemId() == null ? null : context.get(entry.getOrderItemId());

            lines.add(new VendorStatementLine(
                    entry.getId(),
                    entry.getOccurredAt(),
                    entry.getEntryType(),
                    entry.getOrderId(),
                    ctx == null ? null : (String) ctx[1],
                    entry.getOrderItemId(),
                    ctx == null ? null : (String) ctx[2],
                    ctx == null ? null : ((Number) ctx[3]).intValue(),
                    entry.getBasisAmount() == null ? null : VendorCommission.money(entry.getBasisAmount()),
                    entry.getCommissionRate(),
                    VendorCommission.money(entry.getAmount()),
                    VendorCommission.money(running),
                    entry.getMemo(),
                    entry.getReversesEntryId()));
        }
        return lines;
    }
}
