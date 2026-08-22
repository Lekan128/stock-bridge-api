package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntry;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Plain JpaRepository: {@code vendor_ledger_entries} has no {@code client_id} and
 * is not tenant-filtered - see {@link VendorLedgerEntry} for why the rows cannot
 * be scoped to their reader. Every method here therefore takes a seller id
 * EXPLICITLY, and there is no second layer behind it: that predicate is the entire
 * thing keeping one vendor out of another's money.
 *
 * <p>Callers must pass an id that came from {@code VendorGuard.requireSeller()} or
 * from a super admin route, never from a request parameter on a vendor surface.
 * The one method that deliberately takes no seller id -
 * {@link #findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc} - is reached only
 * after its order has already been authorised, exactly as
 * {@code OrderItemRepository} documents for its own unfiltered finders.
 *
 * <h2>There are no write methods beyond save</h2>
 * No {@code deleteBy...}, no {@code @Modifying} update, and none should be added.
 * The table is append-only and a trigger enforces it, so a delete method here
 * would compile, pass review and fail at runtime.
 */
public interface VendorLedgerEntryRepository extends JpaRepository<VendorLedgerEntry, UUID> {

    /**
     * The check-first half of the idempotency posture: a repeat of an event that
     * already posted returns quietly instead of throwing, and only a genuine
     * simultaneous race reaches {@code uq_vendor_ledger_entries_idempotency_key}
     * and fails loudly. Same arrangement as
     * {@code CompanyVendorLinkService.findOrCreateVerifiedEntry}.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

    /** Every row about one order, oldest first: accrual, then any reversal of it. */
    List<VendorLedgerEntry> findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(UUID orderId);

    List<VendorLedgerEntry> findAllByOrderIdAndEntryType(UUID orderId, VendorLedgerEntryType entryType);

    /**
     * What the platform owes this vendor RIGHT NOW - the closing balance on their
     * statement, and the answer to "what do we owe this vendor".
     *
     * <p>One SUM with no CASE expression, which is the whole reason
     * {@link VendorLedgerEntry#getAmount()} is signed from the vendor's point of
     * view. COALESCE so a vendor who has never traded gets zero rather than null.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e WHERE e.sellerClientId = :sellerId")
    BigDecimal balanceFor(@Param("sellerId") UUID sellerId);

    /**
     * The same sum restricted to everything that happened BEFORE a moment - the
     * opening balance of a statement window.
     *
     * <p>Strictly less than, matching the half-open {@code [from, to)} windows every
     * analytics surface in this application uses, so an entry at exactly
     * {@code from} is a movement in this window and not part of its opening
     * balance. Without that, printing two adjacent statements would count one row
     * twice.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId AND e.occurredAt < :before")
    BigDecimal balanceBefore(@Param("sellerId") UUID sellerId, @Param("before") OffsetDateTime before);

    /** One vendor's movements in a half-open window, in the order a statement prints them. */
    @Query("SELECT e FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId AND e.occurredAt >= :from AND e.occurredAt < :to "
            + "ORDER BY e.occurredAt ASC, e.createdAt ASC, e.id ASC")
    List<VendorLedgerEntry> findWindow(
            @Param("sellerId") UUID sellerId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /**
     * Everything a payout run may claim for one vendor: unsettled, and MATURE
     * before the cutoff.
     *
     * <h2>The three predicates, and why each one is there</h2>
     * <ul>
     *   <li>{@code sellerClientId} - the isolation predicate. Without it a run pays
     *       one vendor another's money.</li>
     *   <li>{@code maturesAt < :cutoff} - both clocks, in one comparison, and the
     *       only place the period appears. See below.</li>
     *   <li>{@code NOT EXISTS} a membership row - "unsettled". This is what makes
     *       re-running a period a no-op instead of a double payment, backed by
     *       {@code uq_vendor_payout_batch_lines_ledger_entry} for the race.</li>
     * </ul>
     *
     * <h2>maturesAt, not occurredAt (M9) - and the two clocks stay separate</h2>
     * This clause used to read {@code occurredAt < :cutoff}. It now reads
     * {@code maturesAt < :cutoff}, and that ONE character of difference is the whole
     * maturity hold. The two clocks it joins are still independent and must stay so:
     * <ul>
     *   <li>MATURITY ({@code maturesAt}, stamped at accrual by
     *       {@code EscrowHoldPolicy}) decides WHETHER a line may be paid at all.</li>
     *   <li>CADENCE ({@code cutoff}, from {@code PayoutCadence}) decides WHEN a run
     *       happens and which fortnight it closes. M9 did not change it.</li>
     * </ul>
     * A line that matured on day 8 of a fortnight is not paid on day 8 - it waits
     * for the next fortnightly run, exactly like everything else.
     *
     * <p>Note it compares maturity against the CUTOFF and not against {@code now()}.
     * That is what keeps an operator-triggered run reproducible: a run started late
     * must pay precisely what a run started on the boundary would have paid, and a
     * now()-based test would silently include everything that ripened while the
     * operator was at lunch. Being late changes when a vendor is paid, never what.
     *
     * <p>Still NO lower bound: an entry that missed its own fortnight (its batch
     * failed, a run was skipped, or it simply matured late) is picked up here rather
     * than stranded forever. And because
     * {@code chk_vendor_ledger_entries_maturity} guarantees
     * {@code maturesAt >= occurredAt}, this predicate is strictly narrower than the
     * one it replaced - nothing can be paid EARLIER under M9 than it would have been
     * before it.
     *
     * <h2>Why PAYOUT entries are excluded</h2>
     * A payout row is never a member of a batch (it is produced BY one), so without
     * this clause it would look permanently unsettled and every subsequent run
     * would try to claim it - dragging each vendor's payable down by everything
     * they had ever been paid. It is the one exclusion that is not obvious and the
     * one whose absence would be silently catastrophic.
     */
    @Query("SELECT e FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId "
            + "AND e.maturesAt < :cutoff "
            + "AND e.entryType <> com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType.PAYOUT "
            + "AND NOT EXISTS (SELECT 1 FROM VendorPayoutBatchLine l WHERE l.ledgerEntryId = e.id) "
            + "ORDER BY e.occurredAt ASC, e.createdAt ASC, e.id ASC")
    List<VendorLedgerEntry> findSettleable(@Param("sellerId") UUID sellerId, @Param("cutoff") OffsetDateTime cutoff);

    /**
     * Every vendor with at least one unsettled entry before the cutoff - the
     * candidate list for a run, so the run does not have to walk every client on
     * the platform to discover that most of them sell nothing.
     *
     * <p>Returns ids only. Whether each candidate actually gets a batch is decided
     * afterwards from their net, which can be zero or negative (a clawback), and a
     * batch is never created for either.
     *
     * <p>{@code maturesAt} rather than {@code occurredAt}, matching
     * {@link #findSettleable} exactly. The two MUST agree: a candidate list built on
     * a wider predicate would nominate vendors whose settleable set then comes back
     * empty, and the run would report them as skipped for "nothing payable" when the
     * truth is "nothing MATURE yet" - two very different sentences to an operator.
     */
    @Query("SELECT DISTINCT e.sellerClientId FROM VendorLedgerEntry e "
            + "WHERE e.maturesAt < :cutoff "
            + "AND e.entryType <> com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType.PAYOUT "
            + "AND NOT EXISTS (SELECT 1 FROM VendorPayoutBatchLine l WHERE l.ledgerEntryId = e.id)")
    List<UUID> findSellersWithSettleableEntries(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Money a batch has CLAIMED but which no transfer has yet discharged - "in
     * flight". Claimed lines whose batch is still PENDING.
     *
     * <p>Split out from the payable figure because the two mean different things to
     * a vendor: one is "this will go into the next run", the other is "this is
     * already on an instruction somebody is about to pay". Both are still owed, and
     * both are inside the closing balance.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId "
            + "AND EXISTS (SELECT 1 FROM VendorPayoutBatchLine l, VendorPayoutBatch b "
            + "            WHERE l.ledgerEntryId = e.id AND b.id = l.payoutBatchId "
            + "            AND b.status = com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus.PENDING)")
    BigDecimal inFlightBalanceFor(@Param("sellerId") UUID sellerId);

    /**
     * What this vendor has waiting that has already MATURED: unclaimed, unsettled,
     * and past its hold as of {@code asOf}.
     *
     * <p>Deliberately different from {@link #findSettleable} - that one answers "what
     * goes into THIS batch" and is bounded by the fortnight CUTOFF, this one answers
     * "what does this vendor have available", which is the figure a vendor's
     * statement shows and which should not jump around as a fortnight boundary
     * passes. So it takes {@code asOf} (now) rather than a cutoff, on purpose.
     *
     * <p>Before M9 this had no date bound at all, because there was nothing to bound:
     * an entry was payable the instant it existed. It now splits with
     * {@link #maturingBalanceFor} - the two together are the whole unclaimed balance,
     * and reporting them separately is what lets a vendor be told "this much is
     * available, this much is still in its hold and ripens on the 3rd" rather than
     * one number that silently means both.
     *
     * <p>This figure can legitimately be NEGATIVE while the vendor is owed money: a
     * refund inside the hold window posts an immediately-mature reversal against a
     * still-immature sale. That is the SAFE direction - a run's net goes down, never
     * up - and it is why {@code VendorEscrowPosition} reports the parts rather than
     * deriving them from each other.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId "
            + "AND e.maturesAt <= :asOf "
            + "AND e.entryType <> com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType.PAYOUT "
            + "AND NOT EXISTS (SELECT 1 FROM VendorPayoutBatchLine l WHERE l.ledgerEntryId = e.id)")
    BigDecimal maturedUnclaimedBalanceFor(@Param("sellerId") UUID sellerId, @Param("asOf") OffsetDateTime asOf);

    /**
     * The other half: accrued, unclaimed, and still inside its hold as of
     * {@code asOf} - money the buyer has confirmed but which is not yet payable.
     *
     * <p>This is the bucket M9 introduced, and the reason it has to be surfaced
     * rather than folded into either neighbour: it is neither "in escrow" (the buyer
     * HAS confirmed, so the vendor has earned it and it is inside the closing
     * balance) nor "payable" (the hold has not run). A vendor shown one number for
     * "owed" and another for "coming in the next payout" cannot otherwise explain the
     * gap between them, which is precisely the "where is my money" question this
     * split exists to pre-empt.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId "
            + "AND e.maturesAt > :asOf "
            + "AND e.entryType <> com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType.PAYOUT "
            + "AND NOT EXISTS (SELECT 1 FROM VendorPayoutBatchLine l WHERE l.ledgerEntryId = e.id)")
    BigDecimal maturingBalanceFor(@Param("sellerId") UUID sellerId, @Param("asOf") OffsetDateTime asOf);

    /**
     * The maturing money broken out by the moment it ripens - {@code [maturesAt,
     * amount]} pairs, soonest first.
     *
     * <p>This is what turns "₦X is still maturing" into "₦X becomes payable on 3
     * March", which is the sentence that actually stops a support email. Grouped in
     * the database rather than folded in Java because the grouping key is the value
     * being displayed and a client should not have to reconstruct it, and because a
     * vendor with two hundred held lines should not have two hundred rows crossing
     * the wire to be summed into four.
     *
     * <p>Note the group is the exact instant, not the calendar day: two confirmations
     * on the same day mature at different times of day, and rounding them together
     * here would state a date the ledger cannot support. The screen does the
     * day-level presentation, where getting it wrong is a cosmetic problem rather
     * than a money one.
     */
    @Query("SELECT e.maturesAt, COALESCE(SUM(e.amount), 0) FROM VendorLedgerEntry e "
            + "WHERE e.sellerClientId = :sellerId "
            + "AND e.maturesAt > :asOf "
            + "AND e.entryType <> com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType.PAYOUT "
            + "AND NOT EXISTS (SELECT 1 FROM VendorPayoutBatchLine l WHERE l.ledgerEntryId = e.id) "
            + "GROUP BY e.maturesAt ORDER BY e.maturesAt ASC")
    List<Object[]> findMaturingTranches(@Param("sellerId") UUID sellerId, @Param("asOf") OffsetDateTime asOf);

    /** The lines one batch claimed, for its audit view. */
    @Query("SELECT e FROM VendorLedgerEntry e "
            + "WHERE EXISTS (SELECT 1 FROM VendorPayoutBatchLine l "
            + "              WHERE l.ledgerEntryId = e.id AND l.payoutBatchId = :batchId) "
            + "ORDER BY e.occurredAt ASC, e.createdAt ASC, e.id ASC")
    List<VendorLedgerEntry> findClaimedBy(@Param("batchId") UUID batchId);
}
