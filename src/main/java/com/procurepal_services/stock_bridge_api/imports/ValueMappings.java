package com.procurepal_services.stock_bridge_api.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read/write view over {@code import_sessions.value_mappings} - the answers the user has already
 * given to section 6.4's distinct-value questions, keyed column then value.
 *
 * <h2>Shape, and why it is nested</h2>
 * <pre>{ "vendor_name": { "Dangote Ltd": { "kind": "EXISTING", "id": "…" } } }</pre>
 * Keyed by column first because the same text can legitimately mean different things in
 * different columns - {@code "BAG"} is a packaging unit in one column and nonsense in another -
 * and a flat map would collapse them into one answer that is wrong in at least one place.
 *
 * <h2>Case folding</h2>
 * Lookup folds case and collapses internal whitespace, so answering for {@code "Dangote Ltd"}
 * also settles {@code "DANGOTE  LTD"} - which is the same supplier typed by a different person
 * on a different day, and asking twice would be a bug the user would read as us not paying
 * attention. The stored key keeps the user's original spelling, because that is what the
 * resolution card quotes back at them.
 *
 * <h2>Reserved keys</h2>
 * Keys beginning {@code __} are the engine's own bookkeeping rather than a column - currently
 * only {@link #UNDONE_AT}. They are namespaced this way because a spreadsheet column can never
 * normalize to a name starting with two underscores ({@code HeaderNames.normalize} folds
 * separators to single underscores and never leads with one), so the two can share the map
 * without any chance of collision.
 */
public final class ValueMappings {

    /**
     * Timestamp of a completed undo.
     *
     * <p>M1 owns the schema and gave {@code import_sessions} no "undone" column, and the status
     * enum has nowhere honest to put it either: the batch really was committed, so moving it out
     * of COMMITTED would make the result screen unreachable and the recent-imports list lie
     * about what happened. Recording the fact here keeps the status truthful while still letting
     * {@code undoable} answer false the second time somebody clicks it - which matters, because
     * a stock-in undo that ran twice would write its compensating adjustments twice and take the
     * stock negative.
     */
    public static final String UNDONE_AT = "__undone_at";

    /**
     * The committed result, stashed so {@code GET /result} can answer after a refresh.
     *
     * <p>Contract section 3 makes the result a real URL that must survive a reload and a shared
     * link, while a commit only ever answers once - and on the async path it answers 202 with no
     * body at all, so this is the ONLY place the frontend can ever learn what happened. It is
     * kept here rather than recomputed because several of its numbers (how many suppliers were
     * created inline, how many ledger rows were written) are facts about the run rather than
     * about the rows, and re-deriving them later would mean re-deriving them wrongly.
     */
    public static final String RESULT = "__result";

    private final Map<String, Object> backing;

    public ValueMappings(Map<String, Object> backing) {
        this.backing = backing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(backing);
    }

    public static String normalizeKey(String value) {
        return value == null ? null : value.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> columnMap(String column) {
        Object existing = backing.get(column);
        return existing instanceof Map<?, ?> typed ? (Map<String, Object>) typed : Map.of();
    }

    @SuppressWarnings("unchecked")
    public Optional<ValueResolution> resolutionFor(String column, String value) {
        if (column == null || value == null) {
            return Optional.empty();
        }
        String wanted = normalizeKey(value);
        for (Map.Entry<String, Object> entry : columnMap(column).entrySet()) {
            if (wanted.equals(normalizeKey(entry.getKey())) && entry.getValue() instanceof Map<?, ?> typed) {
                return Optional.ofNullable(ValueResolution.fromMap((Map<String, Object>) typed));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public void put(String column, String value, ValueResolution resolution) {
        Object existing = backing.get(column);
        Map<String, Object> forColumn = existing instanceof Map<?, ?> typed
                ? new LinkedHashMap<>((Map<String, Object>) typed)
                : new LinkedHashMap<>();
        String wanted = normalizeKey(value);
        forColumn.keySet().removeIf(key -> wanted.equals(normalizeKey(key)));
        forColumn.put(value, resolution.toMap());
        backing.put(column, forColumn);
    }

    /** Every column that has at least one answer, excluding the engine's reserved keys. */
    public List<String> answeredColumns() {
        return backing.keySet().stream().filter(key -> !key.startsWith("__")).toList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> result() {
        Object stored = backing.get(RESULT);
        return stored instanceof Map<?, ?> typed ? (Map<String, Object>) typed : null;
    }

    public void putResult(Map<String, Object> result) {
        backing.put(RESULT, result);
    }

    public boolean isUndone() {
        return backing.get(UNDONE_AT) != null;
    }

    public void markUndone(String timestamp) {
        backing.put(UNDONE_AT, timestamp);
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(backing);
    }
}
