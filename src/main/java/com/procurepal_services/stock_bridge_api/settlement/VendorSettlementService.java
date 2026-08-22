package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.PaymentStatus;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntry;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatch;
import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchLine;
import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus;
import com.procurepal_services.stock_bridge_api.order.OrderNotFoundException;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorLedgerEntryRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorPayoutBatchLineRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorPayoutBatchRepository;
import com.procurepal_services.stock_bridge_api.settlement.dto.EligibleVendorPayout;
import com.procurepal_services.stock_bridge_api.settlement.dto.MarkPayoutFailedRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.MarkPayoutPaidRequest;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchDetail;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchSummary;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunPreview;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunResponse;
import com.procurepal_services.stock_bridge_api.settlement.dto.ReverseAccrualRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payout runs, and the operator actions around them. The super admin's half of this
 * module; the vendor never reaches this class except through the read-only batch
 * summaries on their own statement.
 *
 * <h2>Operator-triggered, not scheduled, and why</h2>
 * The batch run is an endpoint a human calls. The application does have
 * {@code @Scheduled} infrastructure and this module uses it for one job (the escrow
 * release sweep), so the choice was available and was made deliberately:
 *
 * <ul>
 *   <li><b>Nothing downstream is automatic.</b> There is no disbursement API here
 *       and this module deliberately integrates none - a human makes every transfer
 *       and records it. A scheduler that created batches nobody had asked for would
 *       generate a queue of unpaid instructions, which is worse than no queue: the
 *       batch is a claim on ledger lines, so unattended batches would hold a
 *       vendor's money out of the next run while looking like progress.</li>
 *   <li><b>The cutoff is not the click.</b> The usual argument FOR scheduling is
 *       reproducibility, and {@link PayoutCadence} supplies that without a
 *       scheduler: the cutoff is derived from the cadence, so a run started on
 *       Tuesday produces exactly the batch a run started at the cadence boundary
 *       would have. Being late changes when a vendor is paid, never what they are
 *       paid.</li>
 *   <li><b>A missed run cannot strand money.</b> Eligibility has no lower bound, so
 *       a skipped fortnight is absorbed by the next run rather than lost. That is
 *       what makes a human-driven cadence safe.</li>
 * </ul>
 *
 * The cadence remains a documented commitment even though nothing enforces it in
 * code: <b>every other Monday, cutoff 00:00 Africa/Lagos</b>. {@link PayoutCadence}
 * is the definition and {@link #preview()} tells an operator when the next one
 * falls.
 *
 * <h2>Idempotency, in three layers</h2>
 * <ol>
 *   <li>{@link #run(UUID, UUID)} skips a vendor that already has a live batch for
 *       this cutoff, and reports it as skipped rather than throwing - a second click
 *       should say "already done", not fail.</li>
 *   <li>{@code uq_vendor_payout_batches_seller_period} refuses the same
 *       vendor/cutoff pair, catching the race the check above cannot.</li>
 *   <li>{@code uq_vendor_payout_batch_lines_ledger_entry} refuses a ledger entry
 *       being claimed twice, which catches the case the other two cannot: two runs
 *       with DIFFERENT cutoffs whose eligible sets overlap.</li>
 * </ol>
 * None of them is caught and turned into a success. Following the house posture -
 * see {@code CompanyVendorLinkService} - the index is the real guard and a loud
 * failure is better than code that swallows a constraint violation on a payment
 * path.
 *
 * <h2>No tenant context, and no escape hatch either</h2>
 * A super admin principal is not a {@code TenantPrincipal}, so
 * {@code TenantResolutionFilter} never enables the Hibernate filter for these
 * requests and there is nothing for {@code readAcrossTenants} to lift - the same
 * situation, and the same reasoning, as {@code PlatformRevenueService}. Reaching
 * for a hatch here would also fail, because it asserts the caller is the platform
 * owner TENANT, which a super admin is not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VendorSettlementService {

    private static final String CURRENCY = "NGN";

    private final VendorLedgerEntryRepository ledgerEntryRepository;
    private final VendorPayoutBatchRepository payoutBatchRepository;
    private final VendorPayoutBatchLineRepository payoutBatchLineRepository;
    private final PayoutBatchNumberAllocator batchNumberAllocator;
    private final ClientRepository clientRepository;
    private final OrderRepository orderRepository;
    private final com.procurepal_services.stock_bridge_api.repository.OrderItemRepository orderItemRepository;
    private final VendorLedgerService vendorLedgerService;

    // ---------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------

    /**
     * What a run made now would produce, for every vendor, without writing anything.
     *
     * <p>Vendors with a zero or negative net are listed and marked ineligible rather
     * than omitted: "why is this vendor not in the run" is the question this screen
     * exists to answer, and a silently shorter list answers it with silence.
     */
    @Transactional(readOnly = true)
    public PayoutRunPreview preview() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = PayoutCadence.cutoffFor(now);

        // The union of "has money waiting" and "already has a batch at this cutoff". The
        // second half is not decoration: once a run claims a vendor's entries they have no
        // settleable rows left, so a preview driven off the ledger alone would simply drop
        // them - and a shorter list gives an operator no way to tell "already paid this
        // fortnight" from "earned nothing this fortnight".
        List<UUID> sellerIds = new ArrayList<>(ledgerEntryRepository.findSellersWithSettleableEntries(cutoff));
        for (VendorPayoutBatch live :
                payoutBatchRepository.findAllByPeriodEndAndStatusNot(cutoff, VendorPayoutBatchStatus.FAILED)) {
            if (!sellerIds.contains(live.getSellerClientId())) {
                sellerIds.add(live.getSellerClientId());
            }
        }
        Map<UUID, Client> sellers = loadSellers(sellerIds);

        List<EligibleVendorPayout> rows = new ArrayList<>();
        BigDecimal totalNet = BigDecimal.ZERO;
        int alreadyRun = 0;

        for (UUID sellerId : sellerIds) {
            List<VendorLedgerEntry> entries = ledgerEntryRepository.findSettleable(sellerId, cutoff);
            Totals totals = Totals.of(entries);
            Client seller = sellers.get(sellerId);

            boolean live = payoutBatchRepository
                    .findFirstBySellerClientIdAndPeriodEndAndStatusNot(
                            sellerId, cutoff, VendorPayoutBatchStatus.FAILED)
                    .isPresent();
            if (live) {
                alreadyRun++;
            }

            String reason = ineligibleReason(totals.net(), live);
            boolean eligible = reason == null;
            if (eligible) {
                totalNet = totalNet.add(totals.net());
            }

            rows.add(new EligibleVendorPayout(
                    sellerId,
                    seller == null ? "Unknown seller" : seller.getName(),
                    seller == null ? null : seller.getSlug(),
                    entries.size(),
                    totals.proceeds(),
                    totals.commission(),
                    totals.reversals(),
                    totals.net(),
                    eligible,
                    reason));
        }

        rows.sort(Comparator.comparing(EligibleVendorPayout::netAmount).reversed());
        return new PayoutRunPreview(
                cutoff,
                PayoutCadence.periodStartFor(cutoff),
                PayoutCadence.nextCutoffAfter(now),
                now,
                alreadyRun,
                VendorCommission.money(totalNet),
                rows);
    }

    // ---------------------------------------------------------------------------------
    // The run
    // ---------------------------------------------------------------------------------

    /**
     * Creates one PENDING batch per eligible vendor at the cadence's current cutoff.
     *
     * <p>Writes no ledger entry: a batch is a claim, not a payment, and the vendor's
     * balance is unchanged by it. The {@code PAYOUT} entry is posted only when a
     * human marks the batch paid - see {@link #markPaid}.
     *
     * @param onlySellerId narrow the run to one vendor. Null runs every eligible
     *     vendor. Present because a batch that failed for one vendor's bad account
     *     details should be re-runnable on its own without touching the other
     *     fourteen.
     * @param superAdminId who ran it. Recorded on every batch created.
     */
    @Transactional
    public PayoutRunResponse run(UUID onlySellerId, UUID superAdminId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = PayoutCadence.cutoffFor(now);
        OffsetDateTime periodStart = PayoutCadence.periodStartFor(cutoff);

        List<UUID> sellerIds = onlySellerId != null
                ? List.of(onlySellerId)
                : ledgerEntryRepository.findSellersWithSettleableEntries(cutoff);
        Map<UUID, Client> sellers = loadSellers(sellerIds);

        List<PayoutBatchSummary> created = new ArrayList<>();
        BigDecimal totalNet = BigDecimal.ZERO;
        int skipped = 0;

        for (UUID sellerId : sellerIds) {
            List<VendorLedgerEntry> entries = ledgerEntryRepository.findSettleable(sellerId, cutoff);
            Totals totals = Totals.of(entries);

            boolean live = payoutBatchRepository
                    .findFirstBySellerClientIdAndPeriodEndAndStatusNot(
                            sellerId, cutoff, VendorPayoutBatchStatus.FAILED)
                    .isPresent();
            if (ineligibleReason(totals.net(), live) != null) {
                skipped++;
                continue;
            }

            VendorPayoutBatch batch = payoutBatchRepository.saveAndFlush(VendorPayoutBatch.builder()
                    .sellerClientId(sellerId)
                    .batchNumber(batchNumberAllocator.allocate())
                    .periodStart(periodStart)
                    .periodEnd(cutoff)
                    .status(VendorPayoutBatchStatus.PENDING)
                    .currency(CURRENCY)
                    .proceedsTotal(totals.proceeds())
                    .commissionTotal(totals.commission())
                    .reversalTotal(totals.reversals())
                    .netAmount(totals.net())
                    .lineCount(entries.size())
                    .runBy(superAdminId)
                    .runAt(now)
                    .build());

            List<VendorPayoutBatchLine> membership = entries.stream()
                    .map(entry -> VendorPayoutBatchLine.builder()
                            .payoutBatchId(batch.getId())
                            .ledgerEntryId(entry.getId())
                            .build())
                    .toList();
            payoutBatchLineRepository.saveAll(membership);

            totalNet = totalNet.add(totals.net());
            created.add(toSummary(batch, sellers.get(sellerId)));
        }

        log.info(
                "Payout run at cutoff {}: {} batches created, {} skipped, net {}",
                cutoff,
                created.size(),
                skipped,
                totalNet);
        return new PayoutRunResponse(
                cutoff, periodStart, created.size(), skipped, VendorCommission.money(totalNet), created);
    }

    // ---------------------------------------------------------------------------------
    // Operator actions on a batch
    // ---------------------------------------------------------------------------------

    /**
     * Records that a human made the transfer, and posts the {@code PAYOUT} entry that
     * takes the money off the vendor's balance.
     *
     * <p>This is the only place a {@code PAYOUT} entry is ever written, and it is
     * written HERE rather than when the batch was created for a reason worth
     * stating: a PENDING batch has moved no money, and a ledger that discharged the
     * balance at run time would show a vendor as paid days before the transfer -
     * which is exactly the lie the whole module exists to avoid telling in the other
     * direction.
     *
     * @throws SettlementNotAllowedException if the batch is not PENDING. 409, not
     *     400: the caller is entitled to do this and the request is well-formed - it
     *     is the state that makes it impossible, usually because another ops user got
     *     there first.
     */
    @Transactional
    public PayoutBatchSummary markPaid(UUID batchId, MarkPayoutPaidRequest request, UUID superAdminId) {
        VendorPayoutBatch batch = requireBatch(batchId);
        if (batch.getStatus() != VendorPayoutBatchStatus.PENDING) {
            throw new SettlementNotAllowedException("Payout batch " + batch.getBatchNumber() + " is already "
                    + batch.getStatus().name().toLowerCase() + " and cannot be marked paid again.");
        }

        OffsetDateTime now = OffsetDateTime.now();
        batch.setStatus(VendorPayoutBatchStatus.PAID);
        batch.setSettledBy(superAdminId);
        batch.setSettledAt(now);
        batch.setPaymentReference(request.paymentReference().trim());

        ledgerEntryRepository.save(VendorLedgerEntry.builder()
                .sellerClientId(batch.getSellerClientId())
                .entryType(VendorLedgerEntryType.PAYOUT)
                .amount(VendorLedgerEntryType.PAYOUT.normalise(batch.getNetAmount()))
                .currency(CURRENCY)
                .payoutBatchId(batch.getId())
                .occurredAt(now)
                // Immediately mature, and required to be: a PAYOUT records money that
                // has ALREADY left the bank, so there is nothing left to hold. The
                // maturity hold applies to what a vendor is OWED, never to a discharge
                // of it, and chk_vendor_ledger_entries_correction_is_immediate refuses
                // any other value. (The entry is excluded from findSettleable anyway -
                // see VendorLedgerEntryRepository - so this is about the row being
                // honest rather than about eligibility.)
                .maturesAt(now)
                .memo(payoutMemo(batch, request))
                .idempotencyKey(VendorLedgerKeys.payout(batch.getId()))
                .createdBy(superAdminId)
                .build());

        log.info("Payout batch {} marked paid, reference {}", batch.getBatchNumber(), batch.getPaymentReference());
        return toSummary(batch, clientRepository.findById(batch.getSellerClientId()).orElse(null));
    }

    /**
     * Records that the transfer did not happen, and releases the batch's claim so its
     * lines go into the next run.
     *
     * <p>Posts NOTHING to the ledger. No money moved, so no entry should claim any -
     * see {@code VendorPayoutBatchStatus}. Deleting the membership rows is the only
     * delete in this module and is legitimate precisely because they are a claim
     * rather than a financial record.
     */
    @Transactional
    public PayoutBatchSummary markFailed(UUID batchId, MarkPayoutFailedRequest request, UUID superAdminId) {
        VendorPayoutBatch batch = requireBatch(batchId);
        if (batch.getStatus() != VendorPayoutBatchStatus.PENDING) {
            throw new SettlementNotAllowedException("Payout batch " + batch.getBatchNumber() + " is already "
                    + batch.getStatus().name().toLowerCase()
                    + ". A paid batch is reversed by a correcting ledger entry, not by failing it.");
        }

        batch.setStatus(VendorPayoutBatchStatus.FAILED);
        batch.setFailureReason(request.reason().trim());
        batch.setSettledBy(superAdminId);
        // settledAt deliberately left null: chk_vendor_payout_batches_settled_shape
        // ties it to PAID, because "when was it settled" must never have an answer for
        // a batch that was not.
        payoutBatchLineRepository.deleteAllByPayoutBatchId(batch.getId());

        log.info("Payout batch {} marked failed: {}", batch.getBatchNumber(), batch.getFailureReason());
        return toSummary(batch, clientRepository.findById(batch.getSellerClientId()).orElse(null));
    }

    // ---------------------------------------------------------------------------------
    // Reversal (refunds, returns, post-delivery cancellations)
    // ---------------------------------------------------------------------------------

    /**
     * Reverses an order's accrual: proceeds AND commission, as new opposite-signed
     * rows.
     *
     * <p>The actual money going back to the buyer happens outside this system -
     * there is no refund API here any more than there is a disbursement one - so
     * {@code markOrderRefunded} records a decision rather than moving anything. It is
     * still worth writing down: an order left at PAID after its goods came back reads
     * as a completed sale on every screen in the application.
     *
     * @throws SettlementNotAllowedException if the order never accrued. Deliberately
     *     loud rather than a quiet no-op: reversing an order that earned nothing is
     *     almost always somebody acting on the wrong order, and a silent success would
     *     let them believe it worked.
     */
    @Transactional
    public int reverseOrder(UUID orderId, ReverseAccrualRequest request, UUID superAdminId) {
        // findById and not a tenant-scoped finder: a super admin has no tenant, so the
        // Hibernate filter is not enabled for this request and there is nothing to
        // lift. Same situation as every other super admin read.
        Order order = orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new);

        int posted = vendorLedgerService.reverseAccrual(
                order, request.reason().trim(), superAdminId, OffsetDateTime.now());
        if (posted == 0) {
            throw new SettlementNotAllowedException("Order " + order.getOrderNumber()
                    + " has nothing to reverse - it has either never accrued, or has already been reversed in full.");
        }

        boolean markRefunded = request.markOrderRefunded() == null || request.markOrderRefunded();
        if (markRefunded && order.getPaymentStatus() == PaymentStatus.PAID) {
            order.setPaymentStatus(PaymentStatus.REFUNDED);
        }
        return posted;
    }

    // ---------------------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PayoutBatchSummary> list(UUID sellerId, VendorPayoutBatchStatus status, Pageable pageable) {
        Page<VendorPayoutBatch> page = payoutBatchRepository.findForOperator(sellerId, status, pageable);
        Map<UUID, Client> sellers = loadSellers(
                page.getContent().stream().map(VendorPayoutBatch::getSellerClientId).toList());
        return page.map(batch -> toSummary(batch, sellers.get(batch.getSellerClientId())));
    }

    @Transactional(readOnly = true)
    public PayoutBatchDetail get(UUID batchId) {
        VendorPayoutBatch batch = requireBatch(batchId);
        List<VendorLedgerEntry> entries = ledgerEntryRepository.findClaimedBy(batchId);
        BigDecimal linesTotal = entries.stream()
                .map(VendorLedgerEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PayoutBatchDetail(
                toSummary(batch, clientRepository.findById(batch.getSellerClientId()).orElse(null)),
                // Opening balance zero, so the running-balance column reads as the
                // batch's own subtotal and its last row equals the batch net. See
                // StatementLines.
                StatementLines.render(entries, BigDecimal.ZERO, statementContext(entries)),
                VendorCommission.money(linesTotal));
    }

    /** A vendor's own payout history, for their statement. Scoped by the caller, never by a parameter. */
    @Transactional(readOnly = true)
    public List<PayoutBatchSummary> batchesForSeller(UUID sellerClientId) {
        Client seller = clientRepository.findById(sellerClientId).orElse(null);
        return payoutBatchRepository.findAllBySellerClientIdOrderByPeriodEndDesc(sellerClientId).stream()
                .map(batch -> toSummary(batch, seller))
                .toList();
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    /**
     * Why a vendor gets no batch, or null when they do.
     *
     * <p>Zero and negative are separated because they mean different things to an
     * operator: zero is "nothing happened", negative is "this vendor owes us", and
     * the second is a conversation.
     */
    private static String ineligibleReason(BigDecimal net, boolean liveBatchExists) {
        if (liveBatchExists) {
            return "A payout batch already exists for this period.";
        }
        if (net.signum() < 0) {
            return "Balance is negative (" + net.toPlainString()
                    + ") - refunds outweigh new sales, so this nets off against future sales rather than being paid.";
        }
        if (net.signum() == 0) {
            return "Nothing payable for this period.";
        }
        return null;
    }

    /**
     * The order number and product name behind each line, so the audit view names the
     * orders rather than only their ids.
     *
     * <p>No tenant escape hatch is taken and none is needed: a super admin principal
     * never enabled the Hibernate filter, so the join into {@code orders} - which are
     * scoped to their BUYERS - resolves normally. On the vendor's own statement the
     * same lookup DOES need {@code readOwnSales}, which is why
     * {@code VendorStatementService} takes one and this does not.
     */
    private Map<UUID, Object[]> statementContext(List<VendorLedgerEntry> entries) {
        List<UUID> itemIds = entries.stream()
                .map(VendorLedgerEntry::getOrderItemId)
                .filter(java.util.Objects::nonNull)
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

    private VendorPayoutBatch requireBatch(UUID batchId) {
        return payoutBatchRepository.findById(batchId).orElseThrow(() -> new PayoutBatchNotFoundException(batchId));
    }

    private Map<UUID, Client> loadSellers(List<UUID> sellerIds) {
        Map<UUID, Client> sellers = new HashMap<>();
        if (sellerIds.isEmpty()) {
            return sellers;
        }
        for (Client client : clientRepository.findAllById(sellerIds)) {
            sellers.put(client.getId(), client);
        }
        return sellers;
    }

    private static String payoutMemo(VendorPayoutBatch batch, MarkPayoutPaidRequest request) {
        String base = "Payout " + batch.getBatchNumber() + " (ref " + request.paymentReference().trim() + ")";
        return request.note() == null || request.note().isBlank() ? base : base + " - " + request.note().trim();
    }

    static PayoutBatchSummary toSummary(VendorPayoutBatch batch, Client seller) {
        return new PayoutBatchSummary(
                batch.getId(),
                batch.getBatchNumber(),
                batch.getSellerClientId(),
                seller == null ? "Unknown seller" : seller.getName(),
                batch.getPeriodStart(),
                batch.getPeriodEnd(),
                batch.getStatus(),
                batch.getCurrency(),
                VendorCommission.money(batch.getProceedsTotal()),
                VendorCommission.money(batch.getCommissionTotal()),
                VendorCommission.money(batch.getReversalTotal()),
                VendorCommission.money(batch.getNetAmount()),
                batch.getLineCount(),
                batch.getRunAt(),
                batch.getRunBy(),
                batch.getSettledAt(),
                batch.getSettledBy(),
                batch.getPaymentReference(),
                batch.getFailureReason());
    }

    /**
     * The four figures a batch freezes, folded from a set of ledger entries in one
     * pass.
     *
     * <p>{@code net} is the plain sum of every entry's signed amount, and the three
     * component totals are partitions of it - so {@code proceeds + commission +
     * reversals = net} holds by construction rather than by arithmetic anyone has to
     * check. That identity is what a batch row asserts and what the audit view
     * re-derives.
     */
    private record Totals(BigDecimal proceeds, BigDecimal commission, BigDecimal reversals, BigDecimal net) {

        static Totals of(List<VendorLedgerEntry> entries) {
            BigDecimal proceeds = BigDecimal.ZERO;
            BigDecimal commission = BigDecimal.ZERO;
            BigDecimal reversals = BigDecimal.ZERO;
            for (VendorLedgerEntry entry : entries) {
                switch (entry.getEntryType()) {
                    case SALE_PROCEEDS -> proceeds = proceeds.add(entry.getAmount());
                    case COMMISSION -> commission = commission.add(entry.getAmount());
                    case SALE_REVERSAL, COMMISSION_REVERSAL -> reversals = reversals.add(entry.getAmount());
                    case PAYOUT -> {
                        // Unreachable: findSettleable excludes PAYOUT precisely so a
                        // payout can never be claimed by a later batch. Named rather
                        // than defaulted so the exclusion is visible from here too.
                    }
                }
            }
            return new Totals(
                    VendorCommission.money(proceeds),
                    VendorCommission.money(commission),
                    VendorCommission.money(reversals),
                    VendorCommission.money(proceeds.add(commission).add(reversals)));
        }
    }
}
