package com.procurepal_services.stock_bridge_api.companyvendor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Turns a {@code company_vendors} constraint violation into a sentence and a
 * status code.
 *
 * <h2>Why this exists when the service already validates</h2>
 * V11__vendors.sql puts the coherence rules in CHECK constraints on purpose,
 * because they are row-local and service code that forgets one fails silently.
 * The consequence is that the database is the last line of defence and it speaks
 * in constraint names - and a caller who hits one has otherwise sent a request
 * that produces a 500 with a Postgres message in the log and nothing useful on
 * screen. Every path into this table is validated before the write, so reaching
 * here means a genuine race (two orders from the same seller in the same instant)
 * or a bug; either way the answer owed to the caller is a 4xx that says what is
 * wrong, not a 500 that says the server broke.
 *
 * <h2>Matching on the constraint name, not on the message</h2>
 * Postgres puts the constraint name in the error and Hibernate wraps it several
 * layers deep, so the whole cause chain is flattened and searched. Matching on the
 * name is stable across Postgres versions and driver wording, where matching on
 * the prose is not.
 *
 * <p>An unrecognised violation is deliberately NOT swallowed into a generic 400 by
 * this class - see {@code CompanyVendorExceptionHandler}, which decides what to do
 * with an empty result.
 */
final class CompanyVendorConstraints {

    /** The two failures a caller can actually cause, and the two that mean a bug here. */
    private static final Map<String, Violation> BY_CONSTRAINT_NAME = byConstraintName();

    private CompanyVendorConstraints() {
    }

    record Violation(HttpStatus status, String message) {
    }

    static Optional<Violation> translate(DataIntegrityViolationException exception) {
        String flattened = flattenCauses(exception);
        return BY_CONSTRAINT_NAME.entrySet().stream()
                .filter(entry -> flattened.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static Map<String, Violation> byConstraintName() {
        Map<String, Violation> messages = new LinkedHashMap<>();

        // The one a buyer can genuinely trip: an EXTERNAL row with no contact
        // number. @NotBlank on the request catches it first, so arriving here means
        // a caller that bypassed the DTO - still a bad request, not a server fault.
        messages.put(
                "chk_company_vendors_external_shape",
                new Violation(
                        HttpStatus.BAD_REQUEST,
                        "A supplier you add yourself needs a contact number, and cannot be linked to a "
                                + "ProcurePaddy seller account."));

        // Reachable only if something writes a VERIFIED row without naming the
        // seller it is asserting a fact about.
        messages.put(
                "chk_company_vendors_verified_shape",
                new Violation(
                        HttpStatus.BAD_REQUEST,
                        "A verified supplier entry must name the ProcurePaddy seller it refers to."));

        messages.put(
                "chk_company_vendors_kind",
                new Violation(HttpStatus.BAD_REQUEST, "That is not a kind of supplier this directory holds."));

        // A company buying from itself - the one nonsense row the auto-create on
        // purchase could otherwise produce.
        messages.put(
                "chk_company_vendors_not_self",
                new Violation(HttpStatus.BAD_REQUEST, "A company cannot be its own supplier."));

        // The partial unique index. 409 rather than 400: the request was well
        // formed and the row simply already exists, which is what the find-or-create
        // on purchase is there to prevent and what a race can still produce.
        messages.put(
                "uq_company_vendors_client_id_platform_client_id",
                new Violation(HttpStatus.CONFLICT, "That ProcurePaddy seller is already in your supplier directory."));

        return Map.copyOf(messages);
    }

    /**
     * The constraint name can sit on any link of the cause chain depending on how
     * far Hibernate got, so all of them are concatenated rather than guessing at a
     * depth. Lowercased because Postgres reports identifiers folded and nothing
     * here should depend on that.
     */
    private static String flattenCauses(Throwable throwable) {
        StringBuilder flattened = new StringBuilder();
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                flattened.append(cause.getMessage()).append('\n');
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return flattened.toString().toLowerCase();
    }
}
