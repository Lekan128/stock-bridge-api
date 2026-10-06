package com.procurepal_services.stock_bridge_api.product.sync;

import java.util.List;
import java.util.UUID;

/**
 * One page of a first, full sync, in product-id order.
 *
 * @param cursor where to start reading changes once every page has been read. Only on the FIRST
 *     page, because it must be fixed before that page was read: any change made while the later
 *     pages load is then guaranteed to come through the change feed.
 * @param nextAfterId pass as {@code afterId} for the next page; null on the last.
 * @param total how many products there are, for a progress figure. Only on the first page, and
 *     only an estimate of what the pages will add up to: products can come and go while they load.
 */
public record ProductSnapshotPage(
        List<ProductSyncRow> products, UUID nextAfterId, boolean hasMore, String cursor, Long total) {
}
