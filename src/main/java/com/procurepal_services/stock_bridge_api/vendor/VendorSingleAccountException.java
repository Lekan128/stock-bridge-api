package com.procurepal_services.stock_bridge_api.vendor;

/**
 * Something tried to give a vendor a second user account.
 *
 * <h2>Why this is not just {@link VendorNotAllowedException}</h2>
 * That one says "your company is the wrong KIND for this surface" and is raised
 * about the CALLER. This is raised about the TARGET, by callers who are
 * perfectly entitled to create users in general - a tenant OWNER, a super admin -
 * and who have simply named a client that may only ever have one. The two are
 * different facts and get different messages, so a support conversation can tell
 * "you cannot use this screen" from "this account holds exactly one login".
 *
 * <p>403, not 409. A conflict would say "try again once the state changes", and
 * there is no state a vendor can reach in which a second account becomes
 * allowed: the number is one, permanently, by product rule
 * (VENDOR_RESEARCH.md Section A, "Vendor sub-users / roles - LATER"). The
 * message says so rather than implying a retry.
 *
 * <p>The message names no user and no client. A caller probing this learns the
 * rule, which is public, and nothing about who already holds the account.
 */
public class VendorSingleAccountException extends RuntimeException {

    public VendorSingleAccountException() {
        super("A vendor account has exactly one user and cannot be given additional users or staff.");
    }
}
