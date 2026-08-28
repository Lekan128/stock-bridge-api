package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * One spreadsheet row as it moves through a single validation pass: the persisted entity, the
 * inputs the handler was given, and the verdict being assembled about it.
 *
 * <h2>Why the pass works on this rather than on the entity</h2>
 * A validation pass is three phases - per-row {@code validate}, then cross-row
 * {@code validateBatch}, then the engine deriving statuses and bulk-fix counts - and the middle
 * phase routinely undoes what the first one decided. Section 7.1's continuation rows are exactly
 * that: {@code validate} sees a second row with a SKU it has already seen and has no way to know
 * it is a legitimate second supplier, so {@code validateBatch} has to reach back in and remove
 * the duplicate error it raised. Doing that against Hibernate-managed entities would mean the
 * intermediate, wrong state is what a flush would write; doing it here means nothing reaches the
 * database until the whole pass has agreed on an answer.
 *
 * <p>It also gives {@code validateBatch}, {@code unresolvedValues}, {@code preview} and
 * {@code commit} one shared view of the batch, which is what lets a handler resolve a SKU once
 * and have every later phase use the same answer.
 */
@Getter
@Setter
public class ImportRowState {

    /** The persisted row. Its {@code raw} is the file and is never written to. */
    private final ImportSessionRow row;

    /**
     * Field key to effective input value: the file's cell, overlaid with any repair the user has
     * made in the grid. This is what {@code validate} reads - see
     * {@code ImportSessionService.effectiveInput} for how the overlay is decided, and why an
     * edited-to-blank cell is distinguishable from one that was never touched.
     */
    private final Map<String, Object> input;

    /** Field key to the original cell text, kept for error messages that quote what was typed. */
    private final Map<String, String> rawText;

    private Map<String, Object> normalized = new LinkedHashMap<>();
    private List<RowIssue> errors = new ArrayList<>();
    private List<RowIssue> warnings = new ArrayList<>();
    private UUID resolvedEntityId;
    private String resolvedEntityLabel;

    /**
     * The excel row of the parent, when this row is a section 7.1 continuation - a repeated SKU
     * carrying a second supplier rather than a duplicate. Set by {@code validateBatch}, and the
     * only reason the grid can nest the row under its parent instead of showing two unrelated
     * lines for one product.
     */
    private Integer continuationOf;

    /**
     * True when the user explicitly marked this row skipped, or a SKIP_ROWS resolution did. Held
     * separately from {@code status} because a skipped row is still validated - the user can
     * un-skip it, and finding out only then that it was broken is exactly the surprise the
     * review step exists to remove.
     */
    private boolean skipped;

    /** Set by {@code commit}: CREATED, UPDATED, SKIPPED or FAILED. Contract section 4's {@code outcome}. */
    private String outcome;

    /** Free-text explanation of {@code outcome}, printed in the downloadable report. */
    private String outcomeMessage;

    /**
     * How many other rows share each of this row's errors, filled in by the engine after
     * {@code validateBatch} - contract section 4's {@code bulkFixCount}.
     *
     * <p>It lives on the state rather than inside {@link RowIssue} because a handler raising an
     * error cannot possibly know the answer: the count is a fact about the file, and the file is
     * only whole once every row has been judged.
     */
    private java.util.Map<RowIssue, Integer> bulkFixCounts;

    public ImportRowState(ImportSessionRow row, Map<String, Object> input, Map<String, String> rawText) {
        this.row = row;
        this.input = input;
        this.rawText = rawText;
        this.skipped = row.getStatus() == ImportRowStatus.SKIPPED;
    }

    public int excelRow() {
        return row.getExcelRow();
    }

    public void apply(RowValidation validation) {
        this.normalized = new LinkedHashMap<>(validation.normalized());
        this.errors = new ArrayList<>(validation.errors());
        this.warnings = new ArrayList<>(validation.warnings());
        this.resolvedEntityId = validation.resolvedEntityId();
        this.resolvedEntityLabel = validation.resolvedEntityLabel();
    }

    public Object value(String field) {
        return normalized.get(field);
    }

    public String text(String field) {
        Object value = normalized.get(field);
        return value == null ? null : value.toString();
    }

    public String rawTextOf(String field) {
        return rawText.get(field);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Drops every error raised for one column - how {@code validateBatch} retracts a verdict. */
    public void clearErrors(String column, String code) {
        errors.removeIf(issue -> code.equals(issue.code()) && java.util.Objects.equals(column, issue.column()));
    }

    public void addError(RowIssue issue) {
        errors.add(issue);
    }

    public void addWarning(RowIssue issue) {
        warnings.add(issue);
    }

    /**
     * The status this row's verdict implies. Skipped wins over everything, because a row the user
     * has taken out of the import must not go on shouting about a problem they have already
     * chosen not to solve - and must not be counted among the errors that block Continue.
     */
    public ImportRowStatus derivedStatus() {
        if (skipped) {
            return ImportRowStatus.SKIPPED;
        }
        if (hasErrors()) {
            return ImportRowStatus.ERROR;
        }
        if (!warnings.isEmpty()) {
            return ImportRowStatus.WARNING;
        }
        return ImportRowStatus.VALID;
    }

    /** Whether commit will act on this row: valid or merely warned, and not skipped. */
    public boolean isCommittable() {
        return !skipped && !hasErrors();
    }
}
