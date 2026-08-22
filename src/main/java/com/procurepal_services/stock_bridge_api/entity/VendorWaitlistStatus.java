package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where a {@link VendorWaitlistApplication} has got to, stored as its name in
 * {@code vendor_waitlist_applications.status}.
 *
 * <p>Deliberately a flat three-state enum with no transition table, unlike
 * {@link OrderStatus}. There is exactly one decision point and it is made by a
 * human once: PENDING goes to APPROVED or REJECTED and stops. Modelling that as
 * a transition graph would be ceremony around a single edge.
 */
public enum VendorWaitlistStatus {

    /** Submitted, nobody has looked at it. The only state with no reviewer and no reviewed-at. */
    PENDING,

    /**
     * Accepted, and a {@code clients} row with {@link ClientType#VENDOR} was created
     * for them. {@link VendorWaitlistApplication#getApprovedClientId()} is required
     * here (a database CHECK enforces it), so that "approved" can never mean
     * "we meant to and never did".
     */
    APPROVED,

    /** Declined. Kept rather than deleted: a reapplication needs the history behind it. */
    REJECTED;

    /** True once a super admin has decided, either way. */
    public boolean isReviewed() {
        return this != PENDING;
    }
}
