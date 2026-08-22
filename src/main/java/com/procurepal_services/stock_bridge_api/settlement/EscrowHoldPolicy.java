package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import com.procurepal_services.stock_bridge_api.entity.VendorSettlementSettings;
import com.procurepal_services.stock_bridge_api.repository.VendorSettlementSettingsRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The maturity hold, in the one place it is allowed to exist - the same discipline
 * {@link VendorCommission} applies to rounding, and for the same reason: this is a
 * rule two honest implementations could disagree about, so it gets exactly one
 * implementation and every caller goes through it.
 *
 * <h2>THE RULE</h2>
 * <pre>
 *     maturesAt = occurredAt + escrowHoldDays        for SALE_PROCEEDS and COMMISSION
 *     maturesAt = occurredAt                         for everything else
 * </pre>
 *
 * <p>Money accrues when the BUYER confirms receipt, exactly as it did before M9 -
 * nothing here moves that. What it adds is that the accrued entry is not
 * payout-ELIGIBLE until the hold has run, which is the owner's "the money is locked
 * for 7 days to protect the buyer and us from fraud".
 *
 * <h2>Maturity is not the payout cadence. Two clocks.</h2>
 * This decides WHETHER a line may be paid. {@link PayoutCadence} decides WHEN a run
 * happens, and it is completely unchanged by this class: fixed 14-day windows
 * anchored to Monday 1 January 2024, 00:00 Africa/Lagos. A line that matures on day
 * 8 of a fortnight is <em>not</em> paid on day 8 - it waits for the next fortnightly
 * run like everything else, and a line that matures after a cutoff goes into the run
 * after that. Conflating the two is the likeliest way to get this module wrong, which
 * is why the two numbers live in two classes that do not reference each other's
 * values.
 *
 * <h2>Corrections never wait</h2>
 * A reversal, a commission reversal and a payout all mature immediately. The whole
 * purpose of a hold is to catch a refund inside the window; a refund that had to
 * serve its own hold could land after the payout of the sale it reverses, which is
 * exactly the failure the hold was introduced to prevent, arriving through the hold.
 * {@code chk_vendor_ledger_entries_correction_is_immediate} makes it structural, so
 * a future caller cannot get this wrong quietly.
 *
 * <h2>The bounds, and why zero is allowed</h2>
 * {@code [0, 90]}, enforced here, on the request DTO, and by a CHECK on the table -
 * three times, because each catches a different mistake and the table is the only
 * one that cannot be refactored away.
 *
 * <p><b>Zero is permitted deliberately.</b> It means "payable the moment the buyer
 * confirms", which is precisely the behaviour that shipped in M7. Allowing it is the
 * documented way back to the previous model without a deploy; it is a coherent state
 * rather than a degenerate one (every correction row already carries
 * {@code maturesAt == occurredAt}); and forbidding it would mean the only way to undo
 * this feature is a migration.
 *
 * <p><b>Ninety is the ceiling.</b> Past a quarter a hold stops being a fraud window
 * and becomes working capital taken from a small business that has already shipped
 * the goods, and it is far beyond any dispute window this platform operates. It is a
 * bound on damage, not a target.
 *
 * <p><b>Above 14 is legal and consequential.</b> A hold longer than a payout
 * fortnight means money confirmed in one cycle can never be paid by the run that
 * closes it, so every vendor is systematically paid a cycle later. That is a
 * legitimate policy choice and it is not blocked here - but the super admin screen
 * says so out loud before the operator confirms, because it is not obvious from the
 * number.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscrowHoldPolicy {

    /**
     * The hold a fresh database starts with, and the number the owner asked for.
     * Defined here AND as the column default in
     * {@code V15__escrow_maturity_and_settlement_settings.sql} - the migration seeds
     * the row from its own default, and this constant is the fallback below, so the
     * two can only ever agree on 7.
     */
    public static final int DEFAULT_HOLD_DAYS = 7;

    /** See the class doc. Zero is allowed and means "payable on confirmation". */
    public static final int MIN_HOLD_DAYS = 0;

    /** See the class doc. A bound on damage, not a target. */
    public static final int MAX_HOLD_DAYS = 90;

    private final VendorSettlementSettingsRepository settingsRepository;

    /**
     * The hold currently in force, in days.
     *
     * <h2>Why a missing row degrades instead of throwing</h2>
     * V15 seeds the row, so its absence means a broken database - and
     * {@link com.procurepal_services.stock_bridge_api.repository.MarketplaceSettingsRepository}
     * tells its callers to fail loudly in exactly this situation. The opposite
     * choice here is deliberate, because the two rows are read on different paths.
     *
     * <p>A missing delivery fee is read while quoting a checkout, where inventing a
     * number would charge a buyer something nobody agreed. This one is read while
     * ACCRUING, which happens inside a buyer confirming they received their goods.
     * Throwing there would fail that confirmation - blocking a delivery the buyer is
     * standing in front of, over a settings row they cannot see and cannot fix - and
     * {@code VendorLedgerService} is explicit that bookkeeping must never do that.
     *
     * <p>What is actually risked by degrading is small and self-correcting: the
     * fallback is the same number the migration seeds rather than a guess, the
     * operator's own value is used again the moment the row is readable, and the
     * value that was actually applied is stamped on the row for good. The log line is
     * {@code error} rather than {@code warn} because a missing singleton is a broken
     * schema and should page somebody even though nothing visible failed.
     */
    @Transactional(readOnly = true)
    public int holdDays() {
        return settingsRepository
                .findBySingletonTrue()
                .map(VendorSettlementSettings::getEscrowHoldDays)
                .orElseGet(() -> {
                    log.error(
                            "vendor_settlement_settings has no singleton row - V15 seeds one. Falling back to the "
                                    + "default {}-day escrow hold so delivery confirmations keep working.",
                            DEFAULT_HOLD_DAYS);
                    return DEFAULT_HOLD_DAYS;
                });
    }

    /**
     * When an entry of this kind, occurring at this moment, becomes payout-eligible.
     *
     * <p>The kind is a parameter rather than a caller decision on purpose: "which
     * kinds are held" is part of the rule, so it lives with the rule. A caller that
     * had to remember to pass {@code occurredAt} for a reversal is a caller that will
     * eventually forget.
     *
     * @param holdDays the hold to apply, read ONCE per accrual by the caller and
     *     passed in - not re-read here. Posting a proceeds row and its commission row
     *     with two separate reads could straddle a settings change and stamp two
     *     different maturities on one event, which would split an atomic pair across
     *     two payout batches.
     */
    public OffsetDateTime maturityFor(VendorLedgerEntryType entryType, OffsetDateTime occurredAt, int holdDays) {
        return isHeld(entryType) ? occurredAt.plusDays(holdDays) : occurredAt;
    }

    /**
     * Whether the hold applies to this kind at all.
     *
     * <p>Only the two ACCRUAL kinds are held, and they are held together: the
     * commission is not a separate event from the sale it is charged on, so giving it
     * its own maturity would let a run claim a lone negative commission row and show
     * a vendor a deduction with no sale behind it - a batch whose own lines do not
     * explain its total.
     */
    public static boolean isHeld(VendorLedgerEntryType entryType) {
        return entryType == VendorLedgerEntryType.SALE_PROCEEDS || entryType == VendorLedgerEntryType.COMMISSION;
    }

    /**
     * Whether a value is one an operator may set. Used by the service so the message
     * and the bound are stated once; bean validation on the request DTO says the same
     * thing earlier, and the CHECK constraint says it last.
     */
    public static boolean isInRange(int holdDays) {
        return holdDays >= MIN_HOLD_DAYS && holdDays <= MAX_HOLD_DAYS;
    }
}
