package com.procurepal_services.stock_bridge_api.imports;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A per-pass memo the row handlers populate, so validating 5,000 rows does not issue 5,000
 * lookups for the same twelve SKUs.
 *
 * <p>BULK_IMPORT_CONTRACT.md section 2 names this explicitly as part of {@code RowContext}, and
 * it is not a micro-optimisation: the review grid re-validates the entire batch on every cell
 * repair (see {@code ImportSessionService.revalidate} for why it must), so a per-row query would
 * be paid again on every keystroke-settle, not once per upload.
 *
 * <h2>Lifetime, and why it is deliberately short</h2>
 * One instance per validation pass, discarded at the end of it. A cache that outlived the pass
 * would be a correctness problem rather than a speed one: the commit that follows creates
 * products and vendors, and a stale "this SKU does not exist" answer is precisely how a batch
 * would create the same product twice. Nothing here is a second-level cache and nothing is
 * shared between requests.
 *
 * <p>Not thread-safe, and not required to be - a pass runs on one thread.
 */
public final class ImportBatchCache {

    private final Map<String, Map<Object, Object>> namespaces = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <K, V> V get(String namespace, K key, Function<K, V> loader) {
        Map<Object, Object> entries = namespaces.computeIfAbsent(namespace, ignored -> new HashMap<>());
        // computeIfAbsent is deliberately not used for the value: a loader that returns null is
        // a legitimate answer here ("no product has this SKU") and computeIfAbsent would re-run
        // it every time, which is exactly the query storm this class exists to prevent.
        if (entries.containsKey(key)) {
            return (V) entries.get(key);
        }
        V value = loader.apply(key);
        entries.put(key, value);
        return value;
    }

    /** Forget one entry - used when a commit creates the thing a lookup previously missed. */
    public void invalidate(String namespace, Object key) {
        Map<Object, Object> entries = namespaces.get(namespace);
        if (entries != null) {
            entries.remove(key);
        }
    }

    public void put(String namespace, Object key, Object value) {
        namespaces.computeIfAbsent(namespace, ignored -> new HashMap<>()).put(key, value);
    }
}
