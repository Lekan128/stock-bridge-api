package com.procurepal_services.stock_bridge_api.superadmin.dto;

/**
 * How many applications sit in each state.
 *
 * <h2>Why this is an endpoint rather than something the list derives</h2>
 * The admin nav wants a badge on "Vendor waitlist" showing the pending count, and
 * a page of results cannot answer that - {@code totalElements} on a filtered page
 * answers "how many match the filter I am currently looking at", so a reviewer
 * standing on the APPROVED tab would see a badge counting approvals. Reading it
 * from three {@code countByStatus} calls is one cheap indexed query each
 * ({@code idx_vendor_waitlist_status_created_at} covers them) and gives the same
 * answer from every screen.
 *
 * <p>All three are returned, not just pending, because the same shape then serves
 * the tab labels on the queue itself - and a caller that needed only one would
 * still have made one request.
 */
public record VendorWaitlistCounts(long pending, long approved, long rejected) {
}
