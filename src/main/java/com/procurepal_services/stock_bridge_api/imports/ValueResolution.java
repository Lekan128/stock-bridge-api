package com.procurepal_services.stock_bridge_api.imports;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The answer to one distinct-value question - BULK_IMPORT_CONTRACT.md section 3's
 * {@code PATCH /value-mappings} body and section 4's {@code UnresolvedValue.resolution}, the
 * same five-armed union in both directions.
 *
 * <h2>Why this is one decision and not forty-seven</h2>
 * {@code "Dangote Ltd"} appearing on forty-seven rows and matching no supplier is not
 * forty-seven mistakes; it is one question asked once (design 6.4). The union's arms are exactly
 * the answers a person can give to it: it is that existing one, add it, use this literal value
 * instead, leave it blank, or drop those rows. Anything the resolver cannot express in those
 * five would have to be answered per row, which is the experience this whole screen exists to
 * avoid.
 *
 * <h2>Why CREATE_NEW carries a payload rather than an id</h2>
 * Creating the vendor at resolution time would write to the tenant's directory before the user
 * has confirmed the import - and a user who then abandons the session would be left with
 * suppliers they never agreed to. The payload is held in {@code import_sessions.value_mappings}
 * and only becomes a row inside the commit transaction, which is also what lets the confirm
 * screen say "2 new suppliers will be added to your directory" as a *prediction* rather than a
 * report (design 9.4, decision 13.2).
 *
 * <p>Serialized with nulls omitted so the wire form is the tight discriminated union the
 * frontend's {@code ValueResolution} type declares, rather than every arm carrying every other
 * arm's fields set to null.
 *
 * @param kind which arm. Spelled exactly as the contract lists them.
 * @param id EXISTING only - the entity the value resolves to.
 * @param value LITERAL only - the corrected text to substitute into every matching cell.
 * @param payload CREATE_NEW only - the minimum needed to create the thing later. A vendor needs
 *     {@code name}; a product needs {@code name} and {@code unitOfMeasure}, because a product
 *     with no unit cannot be stocked into and the very row that asked for it would fail again on
 *     the next pass (design 6.7).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueResolution(String kind, UUID id, String value, Map<String, Object> payload) {

    public static final String KIND_EXISTING = "EXISTING";
    public static final String KIND_CREATE_NEW = "CREATE_NEW";
    public static final String KIND_LITERAL = "LITERAL";
    public static final String KIND_BLANK = "BLANK";
    public static final String KIND_SKIP_ROWS = "SKIP_ROWS";

    public ValueResolution {
        payload = payload == null ? null : Map.copyOf(payload);
    }

    public boolean isExisting() {
        return KIND_EXISTING.equals(kind);
    }

    public boolean isCreateNew() {
        return KIND_CREATE_NEW.equals(kind);
    }

    public boolean isLiteral() {
        return KIND_LITERAL.equals(kind);
    }

    public boolean isBlank() {
        return KIND_BLANK.equals(kind);
    }

    public boolean isSkipRows() {
        return KIND_SKIP_ROWS.equals(kind);
    }

    /** Whether this arm names an entity that exists, or will exist, once the commit runs. */
    public boolean namesAnEntity() {
        return isExisting() || isCreateNew();
    }

    public String payloadText(String key) {
        if (payload == null) {
            return null;
        }
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        String text = raw.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** The jsonb form stored under {@code import_sessions.value_mappings}. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        if (id != null) {
            map.put("id", id.toString());
        }
        if (value != null) {
            map.put("value", value);
        }
        if (payload != null) {
            map.put("payload", payload);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ValueResolution fromMap(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Object kind = map.get("kind");
        if (kind == null) {
            return null;
        }
        Object id = map.get("id");
        Object value = map.get("value");
        Object payload = map.get("payload");
        return new ValueResolution(
                kind.toString(),
                id == null ? null : UUID.fromString(id.toString()),
                value == null ? null : value.toString(),
                payload instanceof Map<?, ?> typed ? (Map<String, Object>) typed : null);
    }
}
