package com.procurepal_services.stock_bridge_api.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one call to {@link ImportRowHandler#validate} answers with: the coerced values commit
 * will read, everything wrong with them, and the entity the row resolved to.
 *
 * <h2>Why the normalized map comes back rather than being written in place</h2>
 * {@code validate} is required by the SPI contract to be pure with respect to the database, and
 * it runs many times per row over a session's life - once at upload, again on every cell repair,
 * again on every mapping change, again on every value resolution. Returning a fresh map rather
 * than mutating the row means a validation pass that throws half way through leaves the row
 * exactly as it was, and means the engine (not the handler) decides when a result is good enough
 * to persist.
 *
 * @param normalized field key to coerced value - {@code "KG"} rather than {@code "kgs"}, a
 *     {@link java.math.BigDecimal} rather than {@code "45,000"}. Null for a cell that could not
 *     be coerced, which is what makes {@code normalized} readable by commit without re-parsing.
 * @param errors cell problems that block the commit.
 * @param warnings cell problems that do not. An update row's ignored {@code quantity_on_hand}
 *     is the canonical one, and contract section 8.8 makes its presence non-negotiable: the
 *     column is ignored and the grid has to say so.
 * @param resolvedEntityId the product this row will update, or will stock into. Null on a row
 *     that creates.
 * @param resolvedEntityLabel the human name behind that id, carried so the grid can show what
 *     the row matched without a second lookup and without ever printing the id.
 */
public record RowValidation(
        Map<String, Object> normalized,
        List<RowIssue> errors,
        List<RowIssue> warnings,
        UUID resolvedEntityId,
        String resolvedEntityLabel) {

    public RowValidation {
        normalized = new LinkedHashMap<>(normalized);
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    /** Builder, because a row handler accumulates issues across twenty-odd independent checks. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Object> normalized = new LinkedHashMap<>();
        private final List<RowIssue> errors = new ArrayList<>();
        private final List<RowIssue> warnings = new ArrayList<>();
        private UUID resolvedEntityId;
        private String resolvedEntityLabel;

        public Builder value(String field, Object value) {
            normalized.put(field, value);
            return this;
        }

        public Builder issue(RowIssue issue) {
            if (issue.isError()) {
                errors.add(issue);
            } else {
                warnings.add(issue);
            }
            return this;
        }

        public Builder error(String column, String code, String message) {
            return issue(RowIssue.error(column, code, message));
        }

        public Builder error(String column, String code, String message, ImportFieldDescriptor.Option suggestion) {
            return issue(RowIssue.error(column, code, message, suggestion));
        }

        public Builder warning(String column, String code, String message) {
            return issue(RowIssue.warning(column, code, message));
        }

        /** A warning carrying the value one click would apply - see {@link RowIssue#warning}. */
        public Builder warning(
                String column, String code, String message, ImportFieldDescriptor.Option suggestion) {
            return issue(RowIssue.warning(column, code, message, suggestion));
        }

        public Builder resolvedTo(UUID id, String label) {
            this.resolvedEntityId = id;
            this.resolvedEntityLabel = label;
            return this;
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public Object valueOf(String field) {
            return normalized.get(field);
        }

        public RowValidation build() {
            return new RowValidation(normalized, errors, warnings, resolvedEntityId, resolvedEntityLabel);
        }
    }
}
