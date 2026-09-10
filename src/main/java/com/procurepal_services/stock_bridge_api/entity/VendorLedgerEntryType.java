package com.procurepal_services.stock_bridge_api.entity;

import java.math.BigDecimal;

/**
 * The kinds of money event that can happen to a vendor. Deliberately typed rather
 * than "a signed amount with a memo": a statement groups by kind, a payout batch
 * totals by kind, and a reversal has to be findable as a reversal months later.
 *
 * <h2>The sign convention lives here and nowhere else</h2>
 * {@link VendorLedgerEntry#getAmount()} is signed FROM THE VENDOR'S POINT OF VIEW -
 * positive means the platform owes them more. That is what lets every balance in
 * this module be {@code SUM(amount)} with a different WHERE clause and no CASE
 * expression anywhere. Each constant below declares the sign it requires, and
 * {@link #normalise(BigDecimal)} is the one place a magnitude becomes a signed
 * amount, so a caller cannot post a commission that pays the vendor.
 *
 * <p>The same rule is enforced a second time by {@code chk_vendor_ledger_entries_sign}
 * in V14. Two gates on purpose: this one gives a readable failure at the call site,
 * and the CHECK catches the write that never came through this class.
 *
 * <h2>What is deliberately not here</h2>
 * VENDOR_RESEARCH.md Section A lists shipping fee, penalty, marketing and
 * adjustment as further kinds worth having, and calls them "cheap now, painful to
 * retrofit". They are still not here, because a kind nothing posts is a kind
 * nobody tested - and the CHECK constraint that would have to be widened is a
 * one-line migration on the day a surface exists that writes one.
 */
public enum VendorLedgerEntryType {

    /**
     * The line total, owed to the vendor because the goods were confirmed
     * delivered. Never posted at checkout or at payment - see
     * {@code VendorLedgerService} for why the moment matters.
     */
    SALE_PROCEEDS(Sign.POSITIVE),

    /**
     * The platform's fee on that line: {@code line_total * commission_rate},
     * rounded half-up to the kobo by {@code VendorCommission}.
     *
     * <p>Allowed to be exactly zero, and both reasons are real: a vendor onboarded
     * commission-free has an agreed rate of {@code 0.0000} (V11 is explicit that
     * this is a different fact from "no rate agreed", which is null and posts no
     * row at all), and a genuinely tiny line can round to zero at a real rate.
     * Either should show on the statement as a zero fee rather than vanish.
     */
    COMMISSION(Sign.NEGATIVE_OR_ZERO),

    /** Undoes a {@link #SALE_PROCEEDS} after a refund, a return or a cancellation. */
    SALE_REVERSAL(Sign.NEGATIVE),

    /**
     * Undoes the matching {@link #COMMISSION}. Always posted WITH a
     * {@link #SALE_REVERSAL} and never alone: a refund that reversed the sale but
     * kept the fee would charge a vendor for a sale that did not happen, which
     * VENDOR_RESEARCH.md Section C item 6 names as the thing that breaks first.
     */
    COMMISSION_REVERSAL(Sign.POSITIVE_OR_ZERO),

    /**
     * Money that actually left the bank. Posted when a human marks a payout batch
     * PAID and never before - a PENDING batch has moved no money, and a ledger that
     * said otherwise would show a vendor paid days before the transfer.
     */
    PAYOUT(Sign.NEGATIVE);

    private enum Sign {
        POSITIVE,
        POSITIVE_OR_ZERO,
        NEGATIVE,
        NEGATIVE_OR_ZERO
    }

    private final Sign sign;

    VendorLedgerEntryType(Sign sign) {
        this.sign = sign;
    }

    /** True when this kind reduces what the platform owes the vendor. */
    public boolean isDebit() {
        return sign == Sign.NEGATIVE || sign == Sign.NEGATIVE_OR_ZERO;
    }

    /** True when this row corrects an earlier one and must therefore name it. */
    public boolean isReversal() {
        return this == SALE_REVERSAL || this == COMMISSION_REVERSAL;
    }

    /**
     * Turns a MAGNITUDE into the signed amount this kind requires.
     *
     * <p>Every caller passes a positive number and lets this decide the direction,
     * which is the whole point: a service that computed its own signs would
     * eventually get one wrong, and a commission posted positive would pay a vendor
     * their own fee while looking entirely normal in every list and total until
     * somebody reconciled a bank statement.
     *
     * @throws IllegalArgumentException if the magnitude is negative, or is zero for a
     *     kind that requires a movement. Both are programming errors, not user input.
     */
    public BigDecimal normalise(BigDecimal magnitude) {
        if (magnitude == null || magnitude.signum() < 0) {
            throw new IllegalArgumentException(
                    "A ledger amount is supplied as a non-negative magnitude; " + this + " decides the sign.");
        }
        if (magnitude.signum() == 0 && (sign == Sign.POSITIVE || sign == Sign.NEGATIVE)) {
            throw new IllegalArgumentException("A " + this + " entry of zero would record nothing.");
        }
        return isDebit() ? magnitude.negate() : magnitude;
    }
}
