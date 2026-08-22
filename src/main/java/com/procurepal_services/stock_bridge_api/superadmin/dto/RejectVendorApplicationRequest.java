package com.procurepal_services.stock_bridge_api.superadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of POST /api/superadmin/vendor-waitlist/{id}/reject.
 *
 * <h2>The note is required, and that is the only interesting thing about this record</h2>
 * It is the body of the email the applicant gets. Optional, it would be omitted
 * most of the time - the path of least resistance on a reviewer's screen is to
 * click the button - and every rejected business would receive a generic notice
 * that reads as a decision a machine made. Required, the reviewer writes the one
 * sentence they already have in their head, and the applicant learns something
 * they can act on. See {@code VendorEmails.applicationRejected}, which is built
 * around the note rather than around boilerplate with the note appended.
 *
 * <p>Max 500 mirrors {@code vendor_waitlist_applications.review_note} exactly, so
 * an over-long note is a readable 400 rather than a Postgres 22001 surfacing as a
 * 500 after the email has already been composed.
 */
public record RejectVendorApplicationRequest(@NotBlank @Size(max = 500) String reviewNote) {
}
