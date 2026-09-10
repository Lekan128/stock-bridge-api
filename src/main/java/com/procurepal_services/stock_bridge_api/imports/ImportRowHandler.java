package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The only thing that differs between a product-catalog import and a stock-in import.
 *
 * <p>Everything else - upload, parse, column mapping, value resolution, the review grid's
 * read/patch cycle, the confirmation summary, the report, undo's transaction and its 409 - is
 * kind-agnostic and lives in {@code ImportSessionService}. That is BULK_IMPORT_DESIGN.md section
 * 1's claim, and this interface is what makes it a fact the compiler checks rather than a
 * convention the next module might not notice.
 *
 * <h2>Why the split is drawn exactly here</h2>
 * The two imports look similar and are not. Their key differs (a SKU that may not exist yet
 * against one that must), their cadence differs (onboarding against every delivery, forever),
 * their fill pattern is inverted (the user brings the rows against we bring the rows and the
 * user brings one number). Design 6.7 argues at length that collapsing them into one sheet gets
 * the worst of both. But every one of those differences is a fact about *a row* - what it means,
 * what it resolves to, what writing it does. None of them is a fact about files, spreadsheets,
 * paging, review, or transactions. So the seam goes around the row, and the ~1,500 lines on the
 * other side of it get written once.
 *
 * <p>The practical test of whether the seam is in the right place: adding a third import kind
 * should mean writing one class implementing this interface and registering it, with no edit to
 * the service, the controller, the DTOs, or the frontend. It does.
 *
 * <h2>The phases, in the order the engine runs them</h2>
 * <ol>
 *   <li>{@link #fields()} - once, to auto-map the file's headers and to tell the grid what
 *       columns exist.</li>
 *   <li>{@link #validate} - per row, repeatedly, over the session's whole life.</li>
 *   <li>{@link #validateBatch} - once per pass, after every row has been judged alone.</li>
 *   <li>{@link #unresolvedValues} - once per pass, to collapse what is left into questions.</li>
 *   <li>{@link #preview} - on demand, when the user reaches the confirm screen.</li>
 *   <li>{@link #commit} - once, inside one transaction the engine opens and owns.</li>
 *   <li>{@link #undo} - later, maybe never, inside another.</li>
 * </ol>
 */
public interface ImportRowHandler {

    /** Which kind this handler serves. The engine dispatches on it; exactly one handler per kind. */
    ImportKind kind();

    /**
     * Field keys this kind understands, in template column order. Drives auto-mapping.
     *
     * <p>May consult {@code TenantContext} - the catalog's column set genuinely depends on the
     * tenant (a buying company has no selling price to give, so {@code unit_price} is omitted
     * entirely, exactly as {@code ProductExcelService.headerNamesFor} already decides for the
     * downloadable template). Keeping the two answers in agreement is why this reads the tenant
     * rather than taking a flag: a template with a column the grid does not know about, or vice
     * versa, is a bug that only shows up for one class of customer.
     */
    List<ImportFieldDescriptor> fields();

    /**
     * Validate one row's values in isolation and against the tenant's data.
     *
     * <p>Pure with respect to the database: reads, never writes. Returns the errors and warnings
     * for this row plus the entity it resolved to - the product to update, or the product to
     * stock into.
     *
     * <p>Called far more often than its name suggests: once per row at upload, then again for
     * every row on every mapping change, every value resolution, and every cell repair. It must
     * therefore be cheap and it must be idempotent - running it twice on its own output has to
     * produce the same answer, because that is exactly what re-validation does.
     */
    RowValidation validate(RowContext ctx);

    /**
     * Cross-row validation, run once after every row has been validated individually.
     *
     * <p>This is where duplicate-SKU-within-file, the continuation-row convention (design 7.1)
     * and multiple {@code is_preferred_vendor=TRUE} are decided - the three rules that are
     * unanswerable from a single row. M2 deliberately left all three here rather than in its
     * parser: {@code ProductExcelService.parse} still treats a repeated SKU as a flat error,
     * because a parser that has not been told about import modes or vendor lines has no basis
     * for narrowing it.
     *
     * <p>Mutates the states it is given, including retracting errors {@link #validate} raised.
     */
    void validateBatch(BatchContext ctx);

    /**
     * Columns this handler resolves against {@code ValueMappings} <b>itself</b>, and which the
     * engine must therefore not apply on its behalf.
     *
     * <h2>Why the engine has to be told rather than work it out</h2>
     * Most columns hold a value: an answer of "use KG instead" means "put KG in the cell", and
     * the engine can do that generically for every row that said KGS. A REFERENCE column holds a
     * pointer to an entity, and there the same five arms mean five different things - EXISTING
     * names a row in the supplier directory, CREATE_NEW is a promise to write one inside the
     * commit transaction, LITERAL is a different name to go looking under. Only the handler knows
     * how to turn any of that into a {@code CompanyVendor}, and it does, at the point where it
     * needs one.
     *
     * <p>So this is the seam between the two: a column named here is the handler's, and
     * {@code ImportSessionService.applyValueMappings} leaves it alone; anything not named gets
     * the generic LITERAL/BLANK substitution. Erring towards naming a column that does not need
     * it costs a dead bulk fix - the defect that motivated this method - so the list is short and
     * every entry has real handling behind it.
     *
     * @return field keys, never null. Empty by default, the right answer for a handler that asks
     *     no distinct-value questions.
     */
    default java.util.Set<String> selfResolvedColumns() {
        return java.util.Set.of();
    }

    /**
     * Turns a parsed-but-unconfirmed pack declaration into a real, persisted fact, for a handler
     * whose column can name one (MULTI_PACK_PER_VENDOR_DESIGN.md section 6a - a stock-in row's
     * {@code counted_in} cell typed as {@code "100 kg"}, a size nobody has configured a pack for
     * yet). Returns the label to write back into the row's own field so the next validation pass
     * resolves it against the newly-created fact instead of re-raising the same question.
     *
     * <p>Unsupported by default - a product-catalog row's columns never name a pack this way.
     * Throwing rather than returning null keeps "this handler has nothing to confirm" and "this
     * specific confirmation failed" from being confused by a caller.
     *
     * @param valueMappings the session's own answers, so a row whose {@code vendor_name} was
     *     resolved by the "unrecognised supplier" card (a {@code CREATE_NEW} promise that
     *     otherwise only materialises at commit, per {@link #selfResolvedColumns}'s own doc) can
     *     still be confirmed now - the pack needs a real vendor to hang off immediately, not at
     *     the end of the file.
     */
    default String confirmPack(
            ImportSessionRow row,
            UUID tenantId,
            ValueMappings valueMappings,
            String packagingUnit,
            BigDecimal packagingSize) {
        throw new UnsupportedOperationException("This import kind has no pack to confirm.");
    }

    /**
     * Distinct unresolved values needing a human decision (design 6.4), collapsed across rows.
     *
     * <p>Returns questions, not problems. A value only belongs here if one answer settles every
     * row that carries it; anything genuinely per-row stays a cell error in the grid.
     */
    List<UnresolvedValue> unresolvedValues(BatchContext ctx);

    /** What the confirm screen (design 9.4) says. Read-only; must not write. */
    CommitPreview preview(BatchContext ctx);

    /**
     * Write. Runs inside ONE transaction that the engine opens and owns.
     *
     * <p>All-or-nothing across the batch, with rows the user marked skipped excluded (design
     * 6.5). Throwing rolls the whole thing back, which is the intended failure mode: "nothing
     * happened, fix these" was already the right mental model, and the reason it used to be
     * painful - that fixing meant re-uploading - is gone now that repairs happen in the grid.
     *
     * <p>Every entity written must carry the session id as {@code import_batch_id}. That stamp
     * is what makes {@link #undo} possible and what lets the result screen link to exactly what
     * this run created.
     */
    CommitOutcome commit(BatchContext ctx);

    /**
     * Reverse a committed batch, or explain why it cannot be. Same transaction rule.
     *
     * <p>Never deletes ledger rows (contract section 8.10). Where reversal is impossible the
     * answer is a populated {@link UndoOutcome#blocked} - a sentence and a list of what is in the
     * way - not an exception.
     */
    UndoOutcome undo(ImportSession session);
}
