package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.entity.VendorWaitlistStatus;

/**
 * Somebody already decided this application. Maps to 409 via
 * {@link SuperAdminExceptionHandler}.
 *
 * <h2>Why this exists rather than letting the database refuse it</h2>
 * Approving twice would try to create a second {@code clients} row for one
 * application and repoint {@code approved_client_id} at it, orphaning the first
 * vendor account - a row nobody is watching, holding a working login, attached to
 * no application. Approving an already-rejected application would mail somebody an
 * account minutes after mailing them a refusal. Neither is caught by a CHECK:
 * {@code chk_vendor_waitlist_approved_has_client} is satisfied perfectly well by
 * the second approval, because it only asks that an APPROVED row names A client,
 * not that it names the first one. The constraints protect the row's internal
 * consistency; this protects the transition.
 *
 * <p>So it is a service-level guard, checked before anything is created, and it
 * has to be - by the time a constraint could object the damage is done.
 *
 * <h2>409, not 400</h2>
 * The request was well-formed and the caller is allowed to make it; what has
 * changed is the state of the row, usually because another ops user got there
 * first or because a browser tab was left open. That is the same shape as
 * {@code LastActiveOwnerException} and is answered the same way. The message names
 * the status it is in, so the screen can say "this was already rejected" rather
 * than "something went wrong" - there is nothing to withhold from a super admin,
 * who can read the row anyway.
 */
public class VendorApplicationAlreadyReviewedException extends RuntimeException {

    public VendorApplicationAlreadyReviewedException(VendorWaitlistStatus status) {
        super("This application has already been " + status.name().toLowerCase() + ".");
    }
}
