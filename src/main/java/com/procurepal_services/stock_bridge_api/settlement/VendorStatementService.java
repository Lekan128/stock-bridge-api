package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntry;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorLedgerEntryRepository;
import com.procurepal_services.stock_bridge_api.settlement.dto.MaturingTranche;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchSummary;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorEscrowPosition;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementLine;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementMovements;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementResponse;
import com.procurepal_services.stock_bridge_api.vendor.VendorGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vendor's account statement: fees accumulated, what has been paid, what is
 * still in escrow, and what they will be paid next.
 *
 * <h2>Three gates, and the permission is the weakest of them</h2>
 * Exactly the arrangement {@code VendorSalesAnalyticsService} works out at length,
 * and reproduced here rather than reinvented:
 * <ol>
 *   <li>The controller accepts
 *       {@code hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')}.
 *       It has to accept EITHER, because ProcurePal's staff hold only the second
 *       and ProcurePal is a seller; requiring only the first would lock the platform
 *       owner out of its own selling surface, which {@code VendorGuard}'s Javadoc
 *       names as the single most likely mistake in this feature.</li>
 *   <li>{@link VendorGuard#requireSeller()} - not {@code requireVendor()} - proves
 *       the caller's COMPANY sells. This is what refuses a buying company's OWNER,
 *       who also holds VIEW_MARKETPLACE_ANALYTICS.</li>
 *   <li>A {@code seller_client_id} predicate on every query, bound from the id the
 *       guard returned. Nothing here takes a seller id from a request, and there is
 *       no overload that could - {@link #statementFor(OffsetDateTime, OffsetDateTime)}
 *       has no seller parameter at all.</li>
 * </ol>
 * The third is the one actually keeping vendors apart: {@code vendor_ledger_entries}
 * is not tenant-scoped, so there is no Hibernate filter behind the predicate as a
 * safety net. See {@code VendorLedgerEntry}.
 *
 * <h2>Why ProcurePal gets an empty statement rather than a 403</h2>
 * The platform owner sells, so it passes every gate; it simply has no ledger,
 * because the platform charging itself commission would be a debt to itself. The
 * response says so with {@code ledgerBearing = false} rather than erroring. A 403
 * would be the {@code requireVendor()} trap by another route.
 *
 * <h2>The maturing bucket (M9), and why it is a fourth position and not a caveat</h2>
 * Money now accrues at buyer confirmation and becomes PAYABLE a configurable hold
 * later. That gap is real money in a real state, so it gets its own figure on
 * {@code VendorEscrowPosition} - {@code maturing} - together with the dates each
 * slice ripens on and the run that will carry it. Burying it inside "owed to you"
 * would leave a vendor with two numbers that do not explain the distance between
 * them, which is the "where is my money" email this screen exists to prevent.
 *
 * <p>Note what maturity does NOT touch here: the statement's WINDOW, its opening and
 * closing balances, and its line ordering are all still {@code occurredAt}. A
 * statement is dated by when the money was EARNED, because that is what a vendor
 * reconciles against their own delivery records. Maturity is a fact about when it
 * becomes payable, not about when it happened, and dating the statement on it would
 * move a December sale into a January statement for no reason a vendor could follow.
 *
 * <h2>The reconciliation guarantee</h2>
 * {@code openingBalance + netMovement = closingBalance}, exactly, with no rounding
 * slack: opening and closing are the same {@code SUM(amount)} over the same column
 * with different bounds, and the movements are a partition of the rows between
 * them. The only figure in this module that was ever computed rather than copied is
 * commission, and it was rounded once, at accrual, by {@link VendorCommission}.
 *
 * <h2>Export</h2>
 * CSV, and deliberately not PDF. A PDF needs a library, a font, a layout and a
 * rendering test, and none of that makes the numbers any easier to check - whereas
 * CSV opens in the spreadsheet a small Nigerian business's bookkeeper is already
 * using, and re-adds the column for them. The screen is also print-styled, which
 * covers "I need something to file".
 */
@Service
@RequiredArgsConstructor
public class VendorStatementService {

    /**
     * Two years, matching every other dated surface in this application so the three
     * screens refuse the same ranges. Rejected rather than clamped - see
     * {@link InvalidAnalyticsRangeException}, reused here for the same reason
     * {@code SuperAdminExceptionHandler} reuses it: the range a screen will refuse
     * should not depend on which screen it is.
     */
    private static final Duration MAX_RANGE = Duration.ofDays(731);

    /**
     * Carried in the payload so the statement, its CSV and the screen all say the
     * same thing about what proceeds mean, without the frontend re-deriving it.
     */
    private static final String PROCEEDS_BASIS =
            "Sales proceeds are the goods value of each order line, credited when delivery is confirmed. "
                    + "Delivery fees are charged by ProcurePaddy for logistics and are not part of a vendor's "
                    + "proceeds. Commission is line total x rate, rounded half-up to the kobo, per line.";

    private static final String CURRENCY = "NGN";

    private final VendorGuard vendorGuard;
    private final VendorLedgerEntryRepository ledgerEntryRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClientRepository clientRepository;
    private final VendorLedgerService vendorLedgerService;
    private final VendorSettlementService vendorSettlementService;
    private final EscrowHoldPolicy escrowHoldPolicy;

    // ---------------------------------------------------------------------------------
    // The caller's own statement
    // ---------------------------------------------------------------------------------

    /** The signed-in seller's statement. Takes no seller id, and must never grow one. */
    @Transactional(readOnly = true)
    public VendorStatementResponse statementFor(OffsetDateTime from, OffsetDateTime to) {
        Client seller = vendorGuard.requireSeller();
        // readOwnSales, because this call runs under the SELLER's tenant filter and the
        // statement has to join into orders, which are scoped to their BUYERS.
        return build(seller, Window.resolve(from, to), vendorGuard::readOwnSales);
    }

    /**
     * The same statement, for the operator, about a named vendor.
     *
     * <p>The seller id arrives as a parameter here and that is safe for exactly one
     * reason: the route is under {@code /api/superadmin/**}, which
     * {@code SecurityConfig} gates on the super admin audience before any handler
     * runs, and a super admin legitimately reads across every tenant. The vendor
     * method above and this one are kept apart rather than sharing an
     * "optional seller id" parameter, because that parameter would be one missing
     * null-check away from letting a vendor read another vendor's statement.
     */
    @Transactional(readOnly = true)
    public VendorStatementResponse statementForSeller(UUID sellerClientId, OffsetDateTime from, OffsetDateTime to) {
        Client seller = clientRepository.findById(sellerClientId).orElse(null);
        if (seller == null) {
            throw new SettlementNotAllowedException("No client " + sellerClientId + " to build a statement for.");
        }
        // No escape hatch, and taking one here would BREAK this route rather than secure it:
        // readOwnSales asserts that the CALLER's tenant may sell, and a super admin has no
        // tenant at all, so it would refuse every operator with a 403 that read as "your
        // account does not sell". There is also nothing to lift - TenantResolutionFilter
        // never enables the Hibernate filter for a super admin principal - which is exactly
        // the situation PlatformRevenueService documents. Supplier::get is the honest
        // spelling of "no scoping change".
        return build(seller, Window.resolve(from, to), Supplier::get);
    }

    // ---------------------------------------------------------------------------------
    // CSV
    // ---------------------------------------------------------------------------------

    /**
     * The caller's own statement as CSV.
     *
     * <p>Header block first (who, what period, opening and closing balance), then the
     * lines, then the totals - so the file is a document rather than a data dump, and
     * a bookkeeper opening it can see what it is without being told.
     *
     * <p>Every field is quoted and every embedded quote doubled, per RFC 4180: memos
     * are free text a human typed and will eventually contain a comma. Formulas are
     * not a concern for the numeric columns, but the text ones are escaped by
     * {@link #csv(String)} the same way regardless, because a memo beginning with
     * {@code =} would otherwise be evaluated by a spreadsheet.
     */
    @Transactional(readOnly = true)
    public String statementCsvFor(OffsetDateTime from, OffsetDateTime to) {
        VendorStatementResponse statement = statementFor(from, to);
        return toCsv(statement);
    }

    static String toCsv(VendorStatementResponse statement) {
        DateTimeFormatter stamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        StringBuilder out = new StringBuilder();

        out.append("ProcurePaddy vendor statement\n");
        out.append("Vendor,").append(csv(statement.sellerName())).append('\n');
        out.append("Currency,").append(csv(statement.currency())).append('\n');
        out.append("Period from,").append(csv(stamp.format(statement.from()))).append('\n');
        out.append("Period to (exclusive),").append(csv(stamp.format(statement.to()))).append('\n');
        out.append("Generated at,").append(csv(stamp.format(statement.generatedAt()))).append('\n');
        out.append("Opening balance,").append(statement.openingBalance().toPlainString()).append('\n');
        out.append("Closing balance,").append(statement.closingBalance().toPlainString()).append('\n');
        out.append('\n');

        out.append("Date,Type,Order,Product,Quantity,Basis,Rate,Amount,Running balance,Memo\n");
        for (VendorStatementLine line : statement.lines()) {
            out.append(csv(stamp.format(line.occurredAt()))).append(',');
            out.append(csv(line.type().name())).append(',');
            out.append(csv(line.orderNumber())).append(',');
            out.append(csv(line.productName())).append(',');
            out.append(line.quantity() == null ? "" : String.valueOf(line.quantity())).append(',');
            out.append(line.basisAmount() == null ? "" : line.basisAmount().toPlainString()).append(',');
            out.append(line.commissionRate() == null ? "" : line.commissionRate().toPlainString()).append(',');
            out.append(line.amount().toPlainString()).append(',');
            out.append(line.runningBalance().toPlainString()).append(',');
            out.append(csv(line.memo())).append('\n');
        }

        out.append('\n');
        out.append("Sales proceeds,").append(statement.movements().salesProceeds().toPlainString()).append('\n');
        out.append("Commission,").append(statement.movements().commission().toPlainString()).append('\n');
        out.append("Reversals,").append(statement.movements().reversals().toPlainString()).append('\n');
        out.append("Payouts,").append(statement.movements().payouts().toPlainString()).append('\n');
        out.append("Net movement,").append(statement.movements().netMovement().toPlainString()).append('\n');
        out.append('\n');
        out.append("Held (owed to you now),")
                .append(statement.escrow().heldBalance().toPlainString())
                .append('\n');
        // The three parts of "held", in the order they happen to a vendor's money:
        // still in its hold, then available, then on an instruction somebody is about
        // to pay. A bookkeeper re-adding this block should get the line above it.
        out.append("  of which still maturing,")
                .append(statement.escrow().maturing().toPlainString())
                .append('\n');
        out.append("  of which payable at next run,")
                .append(statement.escrow().payableNow().toPlainString())
                .append('\n');
        out.append("  of which already in a payout batch,")
                .append(statement.escrow().inFlight().toPlainString())
                .append('\n');
        out.append("Escrow hold (days),")
                .append(statement.escrow().escrowHoldDays())
                .append('\n');
        for (MaturingTranche tranche : statement.escrow().maturingTranches()) {
            out.append("  becomes payable,")
                    .append(csv(stamp.format(tranche.maturesAt())))
                    .append(',')
                    .append(tranche.amount().toPlainString())
                    .append(",paid on or after,")
                    .append(csv(stamp.format(tranche.payableOnRunAfter())))
                    .append('\n');
        }
        out.append("In escrow (paid by buyer, not yet delivered),")
                .append(statement.escrow().pendingNet().toPlainString())
                .append('\n');
        out.append("Next payout cutoff,")
                .append(csv(stamp.format(statement.escrow().nextPayoutCutoff())))
                .append('\n');
        out.append('\n');
        out.append(csv(statement.salesProceedsBasis())).append('\n');
        return out.toString();
    }

    /** RFC 4180 quoting. Always quotes, so no caller has to decide whether a value needs it. */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    // ---------------------------------------------------------------------------------
    // Assembly
    // ---------------------------------------------------------------------------------

    /**
     * @param crossTenantScope how to run the reads that join into {@code orders}. The two
     *     callers need genuinely different answers - a seller must lift its own tenant
     *     filter, an operator has none to lift and must not assert one - so the difference
     *     is a parameter rather than a boolean flag inside the method. It is a
     *     {@code UnaryOperator}-shaped function and not a "boolean isSuperAdmin", because a
     *     boolean would eventually be defaulted wrong by a third caller.
     */
    private VendorStatementResponse build(
            Client seller,
            Window window,
            Function<Supplier<Object>, Object> crossTenantScope) {
        UUID sellerId = seller.getId();
        OffsetDateTime now = OffsetDateTime.now();

        if (!seller.isVendor()) {
            // ProcurePal. Every gate passed; there is simply no ledger. See the class doc.
            return emptyStatement(seller, window, now);
        }

        BigDecimal opening = ledgerEntryRepository.balanceBefore(sellerId, window.from());
        List<VendorLedgerEntry> entries = ledgerEntryRepository.findWindow(sellerId, window.from(), window.to());

        // The join into orders is a join into rows scoped to their BUYERS, so it
        // matches nothing under the seller's own tenant filter. readOwnSales lifts
        // layer 1 after proving the caller may sell; the seller predicate is supplied
        // by the ids, which came from this seller's own ledger rows and cannot
        // therefore name anybody else's order.
        @SuppressWarnings("unchecked")
        Map<UUID, Object[]> context = (Map<UUID, Object[]>) crossTenantScope.apply(() -> loadContext(entries));

        List<VendorStatementLine> lines = StatementLines.render(entries, opening, context);
        VendorStatementMovements movements = movementsOf(entries);
        BigDecimal closing = opening.add(movements.netMovement());

        VendorLedgerService.EscrowPending pending = (VendorLedgerService.EscrowPending)
                crossTenantScope.apply(() -> vendorLedgerService.pendingEscrowFor(sellerId));

        VendorEscrowPosition escrow = escrowPositionFor(sellerId, pending, now);

        List<PayoutBatchSummary> payouts = vendorSettlementService.batchesForSeller(sellerId).stream()
                .filter(batch -> !batch.periodEnd().isBefore(window.from())
                        && batch.periodEnd().isBefore(window.to()))
                .toList();

        return new VendorStatementResponse(
                sellerId,
                seller.getName(),
                true,
                CURRENCY,
                window.from(),
                window.to(),
                now,
                VendorCommission.money(opening),
                movements,
                VendorCommission.money(closing),
                escrow,
                PROCEEDS_BASIS,
                lines,
                payouts);
    }

    /**
     * ProcurePal's statement. Zeroes rather than nulls throughout, so a client never
     * has to distinguish "no ledger" from "field missing" - which matters because
     * {@code default-property-inclusion: non_null} would drop a null outright.
     */
    private VendorStatementResponse emptyStatement(Client seller, Window window, OffsetDateTime now) {
        BigDecimal zero = VendorCommission.zero();
        return new VendorStatementResponse(
                seller.getId(),
                seller.getName(),
                false,
                CURRENCY,
                window.from(),
                window.to(),
                now,
                zero,
                new VendorStatementMovements(zero, zero, zero, zero, zero),
                zero,
                new VendorEscrowPosition(
                        zero,
                        zero,
                        zero,
                        0,
                        zero,
                        zero,
                        zero,
                        zero,
                        escrowHoldPolicy.holdDays(),
                        null,
                        List.of(),
                        PayoutCadence.nextCutoffAfter(now)),
                PROCEEDS_BASIS,
                List.of(),
                List.of());
    }

    /**
     * The four money positions, the hold that produced them, and the dates that make
     * the maturing one legible.
     *
     * <h2>Why every figure comes from its own indexed sum rather than one query</h2>
     * Held, maturing, payable and in-flight are four different predicates over the
     * same column, and computing three of them in Java from a loaded list would need
     * every one of a long-lived vendor's rows in memory to answer a question about
     * the handful that are unsettled. The statement's LINES are deliberately summed
     * in Java (see {@link #movementsOf}) for the opposite reason - those numbers must
     * agree with the rows printed beside them - and the difference between the two
     * cases is worth keeping straight: this record is a position, not a
     * reconciliation, and nothing on the screen re-adds it.
     *
     * <h2>All four are measured at one {@code now}</h2>
     * The caller's instant is threaded through rather than each query calling
     * {@code now()} for itself. Two reads a few milliseconds apart could otherwise
     * straddle a maturity boundary and report a tranche as both maturing and payable,
     * or as neither - a statement whose own buckets do not add up to its own balance,
     * which is the single thing this response must never do.
     */
    private VendorEscrowPosition escrowPositionFor(
            UUID sellerId, VendorLedgerService.EscrowPending pending, OffsetDateTime now) {
        List<MaturingTranche> tranches = new ArrayList<>();
        for (Object[] row : ledgerEntryRepository.findMaturingTranches(sellerId, now)) {
            OffsetDateTime maturesAt = (OffsetDateTime) row[0];
            tranches.add(new MaturingTranche(
                    maturesAt,
                    VendorCommission.money((BigDecimal) row[1]),
                    // The first fortnight boundary strictly after this instant, which
                    // is exactly the first cutoff whose "matures_at < cutoff" test this
                    // tranche can pass. Derived from the cadence rather than from the
                    // maturity, because they are two clocks and this field is the one
                    // place a vendor gets to see them joined up.
                    PayoutCadence.nextCutoffAfter(maturesAt)));
        }

        return new VendorEscrowPosition(
                pending.proceeds(),
                pending.projectedCommission(),
                pending.projectedNet(),
                pending.orderCount(),
                VendorCommission.money(ledgerEntryRepository.balanceFor(sellerId)),
                VendorCommission.money(ledgerEntryRepository.maturingBalanceFor(sellerId, now)),
                VendorCommission.money(ledgerEntryRepository.maturedUnclaimedBalanceFor(sellerId, now)),
                VendorCommission.money(ledgerEntryRepository.inFlightBalanceFor(sellerId)),
                escrowHoldPolicy.holdDays(),
                tranches.isEmpty() ? null : tranches.getFirst().maturesAt(),
                tranches,
                PayoutCadence.nextCutoffAfter(now));
    }

    private Map<UUID, Object[]> loadContext(List<VendorLedgerEntry> entries) {
        List<UUID> itemIds = entries.stream()
                .map(VendorLedgerEntry::getOrderItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, Object[]> context = new HashMap<>();
        if (itemIds.isEmpty()) {
            return context;
        }
        for (Object[] row : orderItemRepository.findStatementContext(itemIds)) {
            context.put((UUID) row[0], row);
        }
        return context;
    }

    /**
     * The five ledger kinds folded into the four columns a vendor reads, with
     * {@code netMovement} as their plain sum.
     *
     * <p>Summed from the SAME list the lines are rendered from, deliberately, rather
     * than from a second aggregate query. Two queries could disagree - a row
     * committed between them would appear in one and not the other - and the whole
     * value of this response is that its own numbers add up.
     */
    private static VendorStatementMovements movementsOf(List<VendorLedgerEntry> entries) {
        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal reversals = BigDecimal.ZERO;
        BigDecimal payouts = BigDecimal.ZERO;

        for (VendorLedgerEntry entry : entries) {
            BigDecimal amount = entry.getAmount();
            if (entry.getEntryType() == VendorLedgerEntryType.SALE_PROCEEDS) {
                proceeds = proceeds.add(amount);
            } else if (entry.getEntryType() == VendorLedgerEntryType.COMMISSION) {
                commission = commission.add(amount);
            } else if (entry.getEntryType() == VendorLedgerEntryType.PAYOUT) {
                payouts = payouts.add(amount);
            } else {
                reversals = reversals.add(amount);
            }
        }

        BigDecimal net = proceeds.add(commission).add(reversals).add(payouts);
        return new VendorStatementMovements(
                VendorCommission.money(proceeds),
                VendorCommission.money(commission),
                VendorCommission.money(reversals),
                VendorCommission.money(payouts),
                VendorCommission.money(net));
    }

    /**
     * A validated, half-open {@code [from, to)} range.
     *
     * <p>Defaults and bounds are identical to {@code VendorSalesAnalyticsService}'s -
     * month-to-date when either bound is omitted - so a vendor's sales screen and
     * their statement open on the same period and a figure quoted from one can be
     * looked for in the other.
     */
    private record Window(OffsetDateTime from, OffsetDateTime to) {

        static Window resolve(OffsetDateTime from, OffsetDateTime to) {
            OffsetDateTime effectiveTo = to != null ? to : OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime effectiveFrom = from != null
                    ? from
                    : effectiveTo.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();

            if (effectiveFrom.isAfter(effectiveTo)) {
                throw new InvalidAnalyticsRangeException("'from' must not be after 'to'.");
            }
            if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_RANGE) > 0) {
                throw new InvalidAnalyticsRangeException(
                        "Date range is too wide - " + MAX_RANGE.toDays() + " days is the maximum.");
            }
            return new Window(effectiveFrom, effectiveTo);
        }
    }
}
