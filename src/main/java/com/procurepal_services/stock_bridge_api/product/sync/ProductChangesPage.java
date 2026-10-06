package com.procurepal_services.stock_bridge_api.product.sync;

import java.util.List;
import java.util.UUID;

/**
 * What changed after a cursor.
 *
 * @param products the current state of every product that changed - each at most once, however
 *     many times it changed.
 * @param removedIds products that no longer exist (deleted by an import undo or a catalogue reset).
 * @param cursor pass back next time. Unchanged when nothing has changed.
 * @param hasMore more changes are waiting; ask again straight away.
 */
public record ProductChangesPage(List<ProductSyncRow> products, List<UUID> removedIds, String cursor, boolean hasMore) {
}
