package com.procurepal_services.stock_bridge_api.companyvendor.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * "At a glance, what is this supplier worth to us" - order count, lifetime spend
 * and when we last bought. VENDOR_RESEARCH.md Section B lists this as a MUST for
 * exactly the reason item 13 gives: a directory that lists names and phone
 * numbers is a contacts app.
 *
 * <h2>What counts as a purchase</h2>
 * An order that reached PLACED - i.e. {@code placed_at IS NOT NULL} - and was not
 * later cancelled. Both halves matter:
 *
 * <ul>
 *   <li>PLACED, not created, because a Monnify order sitting at PENDING_PAYMENT
 *       may never be paid for. Counting it would tell a buyer they had spent money
 *       they have not spent. It is also the exact moment the VERIFIED directory
 *       entry itself is created, so the vendor and its totals come into existence
 *       together rather than a screen showing a supplier with an impossible
 *       zero.</li>
 *   <li>Not cancelled, because a cancelled order is a purchase that did not
 *       happen. It still appears in the purchase HISTORY list, badged with its
 *       status - "we ordered and pulled out" is information a buyer wants - but it
 *       must not move the money.</li>
 * </ul>
 *
 * <p>All three are zero/null for an EXTERNAL vendor, and always will be: an
 * off-platform supplier has no orders in this system. Recording purchases against
 * them by hand is a separate feature that has not been built.
 */
public record VendorSpendSummary(
        long orderCount,
        /* Never null - a vendor with no qualifying orders reads as 0, not as an absent figure. */
        BigDecimal totalSpend,
        /* Null only when there has never been a qualifying order. */
        OffsetDateTime lastPurchasedAt) {

    public static VendorSpendSummary none() {
        return new VendorSpendSummary(0L, BigDecimal.ZERO, null);
    }
}
