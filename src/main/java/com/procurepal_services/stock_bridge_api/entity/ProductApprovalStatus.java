package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where a {@link Product} stands with the platform's listing moderation, stored
 * as its name in {@code products.approval_status}.
 *
 * <h2>The workflow now exists</h2>
 * V11 laid these columns down deliberately early, ahead of any code reading them.
 * The review queue, the approve/reject decisions and the resubmission loop were
 * built by the module that opened marketplace listing up to vendors, and live in
 * {@code marketplace.moderation} - see {@code ProductModerationService} for the
 * workflow and {@code ProductModerationRules} for the two rulings that shape it
 * (ProcurePal is auto-approved; editing an APPROVED listing's identity fields
 * returns it to PENDING while price and stock edits do not).
 *
 * <h2>Only meaningful for a product being LISTED by a SELLER</h2>
 * {@code products} holds every tenant's private inventory as well as sellers'
 * catalogues, and nobody moderates a buying company's own stock list. New rows
 * default to {@link #PENDING} because the dangerous case is an unmoderated vendor
 * listing reaching a real purchase order (VENDOR_RESEARCH.md Section C item 4),
 * so the column fails closed - and the consequence is that every buying company's
 * private inventory row is nominally PENDING too.
 *
 * <p>V11 left one obligation to the moderation module because of that, and it is
 * discharged in exactly two places that must stay in step:
 * {@code ProductModerationSpecifications}, which pins the queue to the
 * vendor-seller id set so an operator is never shown a restaurant's list of its
 * own cooking oil, and {@code MarketplaceProductSpecifications.listedBy}, which
 * requires APPROVED alongside the seller pin before a row reaches the public
 * catalog. Neither may consult this column without the seller predicate.
 *
 * <p>Deliberately a flat three-state enum with no transition table, unlike
 * {@link OrderStatus}: unlike an order, a listing can move back and forth between
 * these states any number of times as a vendor fixes and resubmits, so there is
 * no graph worth encoding.
 */
public enum ProductApprovalStatus {

    /** Submitted or newly created; not cleared for the public catalogue. */
    PENDING,

    /**
     * Cleared for listing. Every product that existed before V11 was backfilled to
     * this - all of them predate third-party selling, so they are either a buying
     * company's own inventory or the platform owner's catalogue, and neither was
     * ever queued behind anything.
     */
    APPROVED,

    /**
     * Refused, with {@link Product#getRejectionReason()} explaining why so the
     * vendor can fix it and resubmit. A rejection carrying no reason only produces
     * a support ticket.
     */
    REJECTED;

    /** True when this product may appear on the public catalogue as far as moderation is concerned. */
    public boolean isListable() {
        return this == APPROVED;
    }
}
