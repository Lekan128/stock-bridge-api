package com.procurepal_services.stock_bridge_api.marketplace.moderation.dto;

/**
 * Badge counts for the moderation screen's tabs.
 *
 * <p>{@code awaitingReview} is deliberately NOT the same as {@code pending}: it counts
 * only the PENDING listings a vendor has actually asked to publish
 * ({@code is_marketplace_listed}). A vendor's unsubmitted draft is PENDING too, but
 * nobody is waiting on the operator for it, and a nav badge that counted drafts would
 * show a number that never reaches zero however diligently the queue is worked - which
 * is how a badge stops being read at all.
 */
public record ModerationQueueCountsResponse(long awaitingReview, long pending, long approved, long rejected) {
}
