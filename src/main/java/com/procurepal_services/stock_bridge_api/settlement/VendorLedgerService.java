package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntry;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderItemRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorLedgerEntryRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only thing in this application that writes a ledger row.
 *
 * <h2>WHEN money accrues, and why it is RECEIVED and not DELIVERED</h2>
 * The stakeholder's rule is that funds sit in escrow "until delivery is fully
 * confirmed - so we don't pay them for what has not been delivered". The order
 * lifecycle offers two candidate moments and they are not equivalent:
 *
 * <ul>
 *   <li>{@link OrderStatus#DELIVERED} is the SELLER's assertion that the goods
 *       arrived. On a vendor's own order the vendor is the one who sets it. Paying
 *       a vendor on their own say-so is precisely the thing escrow exists to
 *       prevent, and it would make this module a slower version of the Monnify
 *       sub-account split that VENDOR_RESEARCH already rejected.</li>
 *   <li>{@link OrderStatus#RECEIVED} is the BUYER's assertion that the goods are in
 *       their store. It is the only independent evidence the platform has, it is
 *       the transition {@code OrderStatus.isBuyerDriven()} exists to protect, and
 *       it already means "ALL of it" - partial receipt deliberately leaves an order
 *       at DELIVERED with the remainder still incoming
 *       ({@code OrderService.receive}). "Fully confirmed" and "RECEIVED" are
 *       therefore the same sentence.</li>
 * </ul>
 *
 * So accrual hangs off {@code OrderLifecycleService.markReceived}, which is the
 * single funnel that transition passes through.
 *
 * <h2>ACCRUAL IS NOT ELIGIBILITY (M9)</h2>
 * Before M9 a {@code SALE_PROCEEDS} row was settle-eligible the moment it was
 * written. It is not any more. The owner's rule is that money is "locked for 7
 * days to protect the buyer and us from fraud" after the buyer confirms, so an
 * entry accrues at confirmation exactly as it always did and becomes PAYOUT-
 * ELIGIBLE at {@code occurredAt + hold}, stamped onto
 * {@link VendorLedgerEntry#getMaturesAt()} by {@link EscrowHoldPolicy}.
 *
 * <p>Two independent clocks, and this is the sentence to remember: <b>maturity
 * decides WHETHER a line may be paid, the cadence decides WHEN the run happens.</b>
 * {@link PayoutCadence}'s fortnight is untouched by M9. A line that matures on day
 * 8 waits for the next fortnightly run like everything else.
 *
 * <p>The hold in force is read ONCE per accrual and applied to every row of that
 * accrual, so a settings change landing mid-loop cannot stamp two different
 * maturities on one event. And because the ledger is append-only, a stamped
 * maturity can never be restamped - which is what makes <b>a settings change apply
 * to future accruals only</b>. See {@code VendorSettlementSettingsService}.
 *
 * <h2>The buyer who never confirms, and why money cannot sit in escrow forever</h2>
 * The obvious hole in choosing RECEIVED is that a buyer can simply never press the
 * button, and a vendor who delivered in good faith would never be paid. The
 * mechanism for that is here and is <b>off by default</b>:
 *
 * <blockquote>
 * An order that has been DELIVERED for {@value #ESCROW_RELEASE_DAYS} days without
 * the buyer confirming receipt is treated as confirmed FOR SETTLEMENT PURPOSES,
 * and accrues - <em>when {@code app.vendor-settlement.escrow-release.enabled} is
 * true, or when an operator triggers the sweep by hand.</em>
 * </blockquote>
 *
 * <p><b>M9 keeps that default OFF, deliberately.</b> The owner's instruction is
 * "money stays in escrow until the buyer confirms", full stop, and with the sweep
 * disabled that is literally what the system does. The mechanism is not deleted -
 * it stays available, tested and one property away, for the day the owner decides
 * an unconfirmed delivery should eventually pay - but nothing releases escrow
 * without a buyer's confirmation on a default deployment.
 *
 * <p><b>The operational consequence, stated rather than discovered:</b> with the
 * sweep off, a buyer who never presses "confirm receipt" strands their vendor's
 * money indefinitely. Nothing expires it, nothing escalates it, and the vendor's
 * statement will show it as "in escrow" forever. That WILL generate support load -
 * a vendor who shipped in good faith chasing a payment that has no scheduled date -
 * and the only levers today are chasing the buyer or an operator running
 * {@code POST /api/superadmin/settlement/escrow/release} by hand. M9 does not solve
 * this; it makes sure nobody is surprised by it.
 *
 * <p>Fourteen days is deliberately the same length as a payout fortnight, so that
 * when the sweep IS enabled a delivery is either confirmed or auto-released before
 * the run that would have paid it. Note that the hold applies on top: a swept
 * release matures at {@code deliveredAt + 14 + hold}, because the sweep asserts
 * confirmation and the hold runs from confirmation however it was arrived at.
 *
 * <h2>What accrues, and what does not</h2>
 * Only {@link ClientType#VENDOR} sellers. ProcurePal sells on its own marketplace,
 * but the platform paying itself commission is a debt to itself, and posting it
 * would put a number in the ledger that means nothing and would have to be
 * excluded from every total afterwards. ProcurePal's statement is therefore
 * legitimately, permanently empty - see {@code VendorStatementService}.
 *
 * <p>The DELIVERY FEE never accrues. It is the buyer's payment for logistics the
 * platform arranged, not part of what the vendor sold, so the proceeds row is the
 * order LINE total and the order's {@code deliveryFee} is not in this ledger at
 * all. Stated here because "the vendor's share of the order" is otherwise an
 * ambiguous phrase and a reader could reasonably assume either.
 *
 * <h2>Idempotency</h2>
 * Check first, then let the index catch the race - the posture
 * {@code CompanyVendorLinkService} and {@code OrderNumberAllocator} both take. Every
 * write here derives a deterministic {@link VendorLedgerKeys} key from the thing it
 * is about, so a repeat is a no-op and a genuine simultaneous double-post is a
 * loud {@code DataIntegrityViolationException} rather than a silently doubled
 * balance. Nothing catches it: swallowing a constraint violation inside the
 * caller's transaction would mark that transaction rollback-only anyway, turning a
 * duplicate ledger row into a failed order confirmation.
 *
 * <h2>Tenant context</h2>
 * None is taken and none is needed. {@link VendorLedgerEntry} is not a
 * TenantAwareEntity, so there is no {@code @PrePersist} to satisfy and no filter to
 * re-point - which is what lets the accrual run unchanged from the buyer's request
 * thread (confirming receipt) and from a scheduler thread with no tenant at all
 * (the sweep).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorLedgerService {

    /**
     * How long a DELIVERED order waits for the buyer before its money is released
     * anyway. See the class doc for the rule and for why it is one fortnight.
     */
    public static final int ESCROW_RELEASE_DAYS = 14;

    /**
     * Ceiling on one sweep pass. A sweep is a background job with no user waiting,
     * so it is better for it to make steady progress every run than to attempt an
     * unbounded backlog in one transaction on the morning somebody re-enables it
     * after a month.
     */
    private static final int SWEEP_BATCH_SIZE = 200;

    private static final String CURRENCY = "NGN";

    private final VendorLedgerEntryRepository ledgerEntryRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ClientRepository clientRepository;
    private final EscrowHoldPolicy escrowHoldPolicy;

    /**
     * Off by default, and enabled per environment rather than in code. A job that
     * writes money rows should be something an operator turned on knowingly; and
     * every test drives {@link #releaseUnconfirmedDeliveries()} directly, exactly as
     * {@code PaymentReconciliationService} exposes its sweep for the same reason,
     * so the flag never hides behaviour from the suite.
     */
    @Value("${app.vendor-settlement.escrow-release.enabled:false}")
    private boolean escrowReleaseEnabled;

    // ---------------------------------------------------------------------------------
    // Accrual
    // ---------------------------------------------------------------------------------

    /**
     * The moment a vendor's money becomes theirs. Called from
     * {@code OrderLifecycleService.markReceived} - the buyer confirming - and from
     * {@link #releaseUnconfirmedDeliveries()} - the timeout above. Nowhere else.
     *
     * <p>Posts, per order line: one {@code SALE_PROCEEDS} for the line total, and
     * one {@code COMMISSION} for the platform's fee, computed by
     * {@link VendorCommission}. A line whose {@code commissionRate} is null gets no
     * commission row at all, because null means "no commission applies" and a zero
     * row would assert an agreement nobody made (V11).
     *
     * <p>Silently does nothing for a non-vendor seller, an order that has already
     * accrued, or an order with no lines. All three are ordinary rather than
     * exceptional, and throwing on any of them would fail a buyer's receipt
     * confirmation over a bookkeeping detail they cannot see or act on.
     *
     * @param occurredAt the business moment the money is earned - the confirmation
     *     or the release, not "now". The statement is windowed on it.
     * @param actingUserId the human who confirmed, when there was one. Null for the
     *     sweep, which has no human; inventing one would be a false audit record.
     * @return how many ledger rows were written. Zero means "already done", which
     *     is the successful outcome of a repeat.
     */
    @Transactional
    public int accrueForConfirmedDelivery(
            Order order, OffsetDateTime occurredAt, String reason, UUID actingUserId) {
        if (!isLedgerBearing(order)) {
            return 0;
        }

        List<OrderItem> items = orderItemRepository.findAllByOrderIdOrderByCreatedAtAsc(order.getId());
        List<VendorLedgerEntry> toPost = new ArrayList<>();

        // Read ONCE, outside the loop, and used for every row of this accrual.
        // Re-reading per row could straddle a super admin changing the setting
        // mid-loop and stamp two different maturities on one order - splitting a
        // proceeds row from its own commission across two payout batches, which is a
        // batch whose lines do not explain its total.
        int holdDays = escrowHoldPolicy.holdDays();
        OffsetDateTime maturesAt =
                escrowHoldPolicy.maturityFor(VendorLedgerEntryType.SALE_PROCEEDS, occurredAt, holdDays);

        for (OrderItem item : items) {
            String proceedsKey = VendorLedgerKeys.accrualProceeds(item.getId());
            if (ledgerEntryRepository.existsByIdempotencyKey(proceedsKey)) {
                // This line has accrued. Checked per line rather than per order so a
                // line added to an order after a partial accrual could still be picked
                // up - which should not happen, but a per-order check would make it
                // permanently invisible if it ever did.
                continue;
            }

            toPost.add(VendorLedgerEntry.builder()
                    .sellerClientId(order.getSellerClientId())
                    .entryType(VendorLedgerEntryType.SALE_PROCEEDS)
                    .amount(VendorLedgerEntryType.SALE_PROCEEDS.normalise(VendorCommission.money(item.getLineTotal())))
                    .currency(CURRENCY)
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .occurredAt(occurredAt)
                    // Both rows of this accrual carry the SAME maturity - see above.
                    .maturesAt(maturesAt)
                    .memo(reason)
                    .idempotencyKey(proceedsKey)
                    .createdBy(actingUserId)
                    .build());

            BigDecimal commission = VendorCommission.on(item.getLineTotal(), item.getCommissionRate());
            if (commission != null) {
                toPost.add(VendorLedgerEntry.builder()
                        .sellerClientId(order.getSellerClientId())
                        .entryType(VendorLedgerEntryType.COMMISSION)
                        .amount(VendorLedgerEntryType.COMMISSION.normalise(commission))
                        .currency(CURRENCY)
                        .orderId(order.getId())
                        .orderItemId(item.getId())
                        .commissionRate(item.getCommissionRate())
                        .basisAmount(VendorCommission.money(item.getLineTotal()))
                        .occurredAt(occurredAt)
                        .maturesAt(maturesAt)
                        .memo(reason)
                        .idempotencyKey(VendorLedgerKeys.accrualCommission(item.getId()))
                        .createdBy(actingUserId)
                        .build());
            }
        }

        if (toPost.isEmpty()) {
            return 0;
        }
        ledgerEntryRepository.saveAll(toPost);
        log.info(
                "Accrued {} ledger entries for order {} (seller {})",
                toPost.size(),
                order.getOrderNumber(),
                order.getSellerClientId());
        return toPost.size();
    }

    // ---------------------------------------------------------------------------------
    // Reversal
    // ---------------------------------------------------------------------------------

    /**
     * Undoes an order's accrual, in full, as new rows.
     *
     * <p>This is the refund / return / late-cancellation path, and it is the thing
     * VENDOR_RESEARCH.md Section C item 6 says breaks first if it is missing: a
     * return has to reverse the proceeds AND the commission, or the platform has
     * charged a vendor a fee on a sale that did not happen. Both are posted
     * together here, and neither has a code path that posts one without the other.
     *
     * <p>Nothing is deleted or edited. Each reversal row names the entry it
     * corrects in {@code reversesEntryId}, so a statement can show the original,
     * the correction, and the resulting balance rather than a hole where a sale
     * used to be.
     *
     * <h2>What happens if the money was already paid out</h2>
     * Nothing special, and that is the design. The reversal posts as ordinary
     * negative entries, the vendor's balance goes negative, and no batch is created
     * for them until new sales bring it back above zero -
     * {@code chk_vendor_payout_batches_net_positive} makes that structural rather
     * than a rule somebody has to remember. That is VENDOR_RESEARCH Section A's
     * "refund clawback / negative balance" behaviour, and it needs no extra state.
     *
     * @return how many rows were written. Zero means the order never accrued, which
     *     the caller should surface rather than swallow - reversing an order that
     *     earned nothing is almost always somebody acting on the wrong order.
     */
    @Transactional
    public int reverseAccrual(Order order, String reason, UUID actingUserId, OffsetDateTime occurredAt) {
        List<VendorLedgerEntry> existing =
                ledgerEntryRepository.findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(order.getId());
        if (existing.isEmpty()) {
            return 0;
        }

        List<VendorLedgerEntry> toPost = new ArrayList<>();
        for (VendorLedgerEntry entry : existing) {
            if (entry.getEntryType() == VendorLedgerEntryType.SALE_PROCEEDS) {
                String key = VendorLedgerKeys.reversalProceeds(entry.getOrderItemId());
                if (ledgerEntryRepository.existsByIdempotencyKey(key)) {
                    continue;
                }
                toPost.add(VendorLedgerEntry.builder()
                        .sellerClientId(entry.getSellerClientId())
                        .entryType(VendorLedgerEntryType.SALE_REVERSAL)
                        .amount(VendorLedgerEntryType.SALE_REVERSAL.normalise(entry.getAmount().abs()))
                        .currency(CURRENCY)
                        .orderId(entry.getOrderId())
                        .orderItemId(entry.getOrderItemId())
                        .reversesEntryId(entry.getId())
                        .occurredAt(occurredAt)
                        // Immediately mature, and this is the clause with teeth. A
                        // refund inside the hold window is exactly what the hold
                        // exists to catch; a reversal that had to serve its own hold
                        // could land AFTER the payout of the sale it reverses, which
                        // is that failure arriving through the mechanism meant to
                        // prevent it. chk_vendor_ledger_entries_correction_is_immediate
                        // makes it structural rather than a habit.
                        .maturesAt(occurredAt)
                        .memo(reason)
                        .idempotencyKey(key)
                        .createdBy(actingUserId)
                        .build());
            } else if (entry.getEntryType() == VendorLedgerEntryType.COMMISSION) {
                String key = VendorLedgerKeys.reversalCommission(entry.getOrderItemId());
                if (ledgerEntryRepository.existsByIdempotencyKey(key)) {
                    continue;
                }
                // The reversal repeats the ORIGINAL row's amount rather than
                // recomputing it from the rate. Recomputation would be a second
                // application of the rounding rule and could differ by a kobo from
                // what was charged, leaving a residue on the vendor's balance that no
                // sale explains.
                toPost.add(VendorLedgerEntry.builder()
                        .sellerClientId(entry.getSellerClientId())
                        .entryType(VendorLedgerEntryType.COMMISSION_REVERSAL)
                        .amount(VendorLedgerEntryType.COMMISSION_REVERSAL.normalise(
                                entry.getAmount().abs()))
                        .currency(CURRENCY)
                        .orderId(entry.getOrderId())
                        .orderItemId(entry.getOrderItemId())
                        .reversesEntryId(entry.getId())
                        .commissionRate(entry.getCommissionRate())
                        .basisAmount(entry.getBasisAmount())
                        .occurredAt(occurredAt)
                        // Immediately mature, travelling with its SALE_REVERSAL. See above.
                        .maturesAt(occurredAt)
                        .memo(reason)
                        .idempotencyKey(key)
                        .createdBy(actingUserId)
                        .build());
            }
            // SALE_REVERSAL, COMMISSION_REVERSAL and PAYOUT are skipped: reversing a
            // reversal would re-create the sale, and a payout belongs to a batch
            // rather than to this order.
        }

        if (toPost.isEmpty()) {
            // Every line was already reversed. A repeat, not a failure.
            return 0;
        }
        ledgerEntryRepository.saveAll(toPost);
        log.info("Reversed {} ledger entries for order {}", toPost.size(), order.getOrderNumber());
        return toPost.size();
    }

    /**
     * The cancellation hook, wired into {@code OrderLifecycleService.transition}.
     *
     * <p>In today's state machine this can never find anything to reverse:
     * {@link OrderStatus} makes CANCELLED unreachable from OUT_FOR_DELIVERY onwards,
     * so an order that accrued cannot subsequently be cancelled. It is hooked
     * anyway, and quietly, for the same reason every other consequence lives in
     * that service: the day somebody widens the state machine - a post-delivery
     * cancellation, a return that cancels rather than refunds - this is already
     * correct, and the alternative is a vendor being paid for goods that came back.
     */
    @Transactional
    public int reverseForCancellation(Order order, String reason, UUID actingUserId) {
        return reverseAccrual(
                order,
                reason == null || reason.isBlank() ? "Order cancelled" : "Order cancelled: " + reason.trim(),
                actingUserId,
                OffsetDateTime.now());
    }

    // ---------------------------------------------------------------------------------
    // Escrow release for deliveries the buyer never confirmed
    // ---------------------------------------------------------------------------------

    /**
     * Accrues for every vendor order that has been DELIVERED longer than
     * {@value #ESCROW_RELEASE_DAYS} days without the buyer confirming receipt.
     *
     * <p>Public and callable directly so tests can drive it deterministically
     * instead of waiting for a scheduler - the same arrangement, for the same
     * reason, as {@code PaymentReconciliationService.reconcilePendingPayments}.
     *
     * <p>The order is deliberately left at DELIVERED. See the class doc: releasing
     * escrow and receiving stock are different facts, and only the buyer may assert
     * the second.
     *
     * @return how many orders were released
     */
    @Transactional
    public int releaseUnconfirmedDeliveries() {
        OffsetDateTime threshold = OffsetDateTime.now().minusDays(ESCROW_RELEASE_DAYS);
        List<Order> candidates = orderRepository.findVendorOrdersAwaitingEscrowRelease(
                OrderStatus.DELIVERED, threshold, ClientType.VENDOR, PageRequest.of(0, SWEEP_BATCH_SIZE));

        int released = 0;
        for (Order order : candidates) {
            // occurredAt is the moment escrow ACTUALLY matured - delivery plus the
            // window - not now. A sweep that has been switched off for a month must
            // not date a month of accruals to the morning somebody switched it back
            // on, or every one of those statements would be wrong.
            OffsetDateTime maturedAt = order.getDeliveredAt().plusDays(ESCROW_RELEASE_DAYS);
            int posted = accrueForConfirmedDelivery(
                    order,
                    maturedAt,
                    "Escrow released " + ESCROW_RELEASE_DAYS + " days after delivery without buyer confirmation",
                    null);
            if (posted > 0) {
                released++;
            }
        }
        if (released > 0) {
            log.info("Escrow release: accrued {} delivered-but-unconfirmed vendor orders", released);
        }
        return released;
    }

    /** Whether the scheduled sweep should run. See the field for why it is off by default. */
    public boolean isEscrowReleaseEnabled() {
        return escrowReleaseEnabled;
    }

    // ---------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------

    /** What the platform owes this vendor right now: one SUM over their whole ledger. */
    @Transactional(readOnly = true)
    public BigDecimal balanceFor(UUID sellerClientId) {
        return VendorCommission.money(ledgerEntryRepository.balanceFor(sellerClientId));
    }

    /**
     * Money the buyer has paid that the vendor has NOT yet earned - the "still at
     * risk" figure, and the one escrow state that has no ledger rows because
     * nothing has happened to the vendor's money yet.
     *
     * <p>Computed from the order lines rather than stored, and the projected
     * commission on it goes through {@link VendorCommission} like every other fee,
     * so the number a vendor sees as "coming" is arrived at by exactly the
     * arithmetic that will later be charged.
     */
    @Transactional(readOnly = true)
    public EscrowPending pendingEscrowFor(UUID sellerClientId) {
        List<Object[]> lines = orderItemRepository.findLinesAwaitingAccrual(
                sellerClientId, OrderStatus.CANCELLED, PaymentStatus.PAID);

        BigDecimal proceeds = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        Set<UUID> orders = new HashSet<>();
        for (Object[] line : lines) {
            BigDecimal lineTotal = (BigDecimal) line[1];
            BigDecimal rate = (BigDecimal) line[2];
            proceeds = proceeds.add(lineTotal);
            BigDecimal fee = VendorCommission.on(lineTotal, rate);
            if (fee != null) {
                commission = commission.add(fee);
            }
            orders.add((UUID) line[0]);
        }
        return new EscrowPending(
                VendorCommission.money(proceeds),
                VendorCommission.money(commission),
                VendorCommission.money(proceeds.subtract(commission)),
                orders.size());
    }

    /**
     * Money the buyer has paid, held against goods not yet confirmed delivered.
     *
     * @param proceeds what the vendor would be credited
     * @param projectedCommission what the platform would charge - a PROJECTION, not
     *     an accrual, and nothing in the ledger corresponds to it yet
     * @param projectedNet the two netted, which is what the vendor would actually receive
     * @param orderCount how many orders are behind it
     */
    public record EscrowPending(
            BigDecimal proceeds, BigDecimal projectedCommission, BigDecimal projectedNet, int orderCount) {
    }

    /**
     * Whether this order's money belongs in a vendor ledger at all.
     *
     * <p>The seller must be a {@link ClientType#VENDOR}. ProcurePal's own sales are
     * excluded here rather than filtered out downstream, so there is exactly one
     * place that decides it and no query has to remember to exclude the platform
     * owner.
     */
    private boolean isLedgerBearing(Order order) {
        if (order == null || order.getSellerClientId() == null) {
            return false;
        }
        return clientRepository
                .findById(order.getSellerClientId())
                .map(Client::isVendor)
                .orElse(false);
    }
}
