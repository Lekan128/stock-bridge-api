package com.procurepal_services.stock_bridge_api.imports;

import static com.procurepal_services.stock_bridge_api.product.bulk.ProductExcelService.EXAMPLE_SKU_MARKER_PREFIX;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.entity.ImportRowStatus;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportSessionRow;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.imports.dto.CommitPreviewResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionSummaryResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.UndoBlockedResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ValueMappingRequest;
import com.procurepal_services.stock_bridge_api.imports.io.HeaderNames;
import com.procurepal_services.stock_bridge_api.imports.io.ImportLimits;
import com.procurepal_services.stock_bridge_api.imports.io.ImportResultReport;
import com.procurepal_services.stock_bridge_api.imports.io.ImportResultReportWriter;
import com.procurepal_services.stock_bridge_api.imports.io.SheetRow;
import com.procurepal_services.stock_bridge_api.imports.io.SheetTable;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReadException;
import com.procurepal_services.stock_bridge_api.imports.io.SpreadsheetReader;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRepository;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRowRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Everything about a bulk import that is not a fact about a row.
 *
 * <p>Upload, parse, persist, auto-map, re-validate, resolve values, preview, commit, report,
 * undo, expire. Two import kinds go through every line of it; see {@link ImportRowHandler} for
 * the argument that the seam belongs around the row and nowhere else, and BULK_IMPORT_DESIGN.md
 * section 1 for why "everything before the row handler is identical and must be built once" is
 * the claim this class exists to honour.
 *
 * <h2>How a row is stored, and why it is not stored the way it is sent</h2>
 * {@code import_session_rows.raw} holds the file keyed by <em>spreadsheet header</em>, not by
 * field key, and it is {@code updatable = false}. The wire form (contract section 4) is keyed by
 * field key, and the projection happens on the way out.
 *
 * <p>That indirection is what makes {@code PATCH /mapping} possible at all. The uploaded bytes
 * are not kept - there is no column for them, and holding ten megabytes per file for
 * forty-eight hours to serve a screen most users never see would be a poor trade - so a
 * re-mapping has to be answerable from what is in the table. Keyed by field key it would not be:
 * re-pointing "Item Code" from {@code name} to {@code sku} would need a value that was discarded
 * at upload. Keyed by header, the whole file is still there and the mapping is just a lens over
 * it. It is also exactly what design 6.1 asks for in words - "exactly what the cells contained,
 * never rewritten".
 *
 * <h2>Why every change re-validates the whole file</h2>
 * A single cell repair re-runs {@code validate} for every row and then {@code validateBatch}.
 * That is more work than it looks like it needs to be, and it is deliberate: the cross-row rules
 * are not decomposable. Fixing a typo in row 40's product code can retract a duplicate error on
 * row 41, and repairing row 41 can create one on row 40. Re-validating only the touched row
 * would leave the grid showing errors that are no longer true, which is worse than slow - a user
 * who fixes something and watches the error stay put concludes the screen is broken. The batch
 * cache ({@link ImportBatchCache}) is what keeps the cost to a handful of queries rather than one
 * per row.
 */
@Service
@RequiredArgsConstructor
public class ImportSessionService {

    /** Rows returned as ISSUES - contract section 3's server-side pseudo-filter. */
    private static final List<ImportRowStatus> ISSUE_STATUSES =
            List.of(ImportRowStatus.ERROR, ImportRowStatus.WARNING);

    /** What the expiry sweep may collect. Never COMMITTED or COMMITTING - see collectExpired. */
    private static final List<ImportStatus> COLLECTABLE_STATUSES = List.of(
            ImportStatus.PARSING, ImportStatus.NEEDS_REVIEW, ImportStatus.READY,
            ImportStatus.FAILED, ImportStatus.EXPIRED);

    private final ImportSessionRepository importSessionRepository;
    private final ImportSessionRowRepository importSessionRowRepository;
    private final UserRepository userRepository;
    private final SpreadsheetReader spreadsheetReader;
    private final ImportColumnMapper columnMapper;
    private final ImportResultReportWriter reportWriter;
    private final List<ImportRowHandler> handlers;

    private Map<ImportKind, ImportRowHandler> handlersByKind;

    /**
     * Dispatch on kind, built once from whatever handlers Spring found.
     *
     * <p>Registration is by presence rather than by a hardcoded list, which is the practical test
     * of whether the SPI's seam is in the right place: adding a third import kind means writing
     * one class and nothing else.
     */
    private ImportRowHandler handlerFor(ImportKind kind) {
        if (handlersByKind == null) {
            Map<ImportKind, ImportRowHandler> byKind = new EnumMap<>(ImportKind.class);
            handlers.forEach(handler -> byKind.put(handler.kind(), handler));
            handlersByKind = byKind;
        }
        ImportRowHandler handler = handlersByKind.get(kind);
        if (handler == null) {
            throw new IllegalStateException("No import row handler registered for " + kind);
        }
        return handler;
    }

    // ------------------------------------------------------------------ upload

    /**
     * Read the file, map its headers, persist a row per spreadsheet row, validate the lot.
     *
     * <p>Synchronous even at the row cap. Contract section 6's {@code ASYNC_ROW_THRESHOLD}
     * governs the commit, not this: parsing five thousand rows through M2's streaming reader is
     * fast, and the review screen has no way to wait for a PARSING file anyway - it renders a
     * static notice, not a poller. Answering 201 with something genuinely ready to review is the
     * only shape the frontend can use.
     *
     * <p>Over-limit files are rejected here with the actual number in the message, before a full
     * parse (design 11). {@code SpreadsheetReader} already enforces both caps and its messages
     * are written for the user, so they pass through untouched rather than reworded.
     */
    @Transactional
    public ImportSessionResponse create(MultipartFile file, ImportKind kind, ImportMode mode, UUID actingUserId) {
        SheetTable table = read(file);
        // Mode is meaningless for STOCK_IN (contract section 1) - persisted as CREATE_ONLY and
        // ignored, rather than left null, so the CHECK constraint and every later read see a
        // legal value.
        ImportMode effectiveMode = kind == ImportKind.STOCK_IN
                ? ImportMode.CREATE_ONLY
                : (mode == null ? ImportMode.CREATE_ONLY : mode);

        ImportRowHandler handler = handlerFor(kind);
        ImportColumnMapper.Mapping mapping = columnMapper.autoMap(table, kind, handler.fields());
        List<SheetRow> dataRows = withoutExampleRows(table, mapping.columnMapping());

        ImportSession session = importSessionRepository.save(ImportSession.builder()
                .kind(kind)
                .mode(effectiveMode)
                .status(ImportStatus.PARSING)
                .originalFilename(filenameOf(file))
                .columnMapping(new LinkedHashMap<>(mapping.columnMapping()))
                .valueMappings(new LinkedHashMap<>())
                .rowCount(dataRows.size())
                .validCount(0)
                .errorCount(0)
                .warningCount(0)
                .skippedCount(0)
                .uploadedBy(actingUserId == null ? null : userRepository.getReferenceById(actingUserId))
                .expiresAt(OffsetDateTime.now().plusHours(ImportLimits.SESSION_TTL_HOURS))
                .build());
        importSessionRepository.flush();

        List<ImportSessionRow> rows = new ArrayList<>();
        for (SheetRow sheetRow : dataRows) {
            rows.add(ImportSessionRow.builder()
                    .session(session)
                    .excelRow(sheetRow.excelRow())
                    .raw(rawOf(table, sheetRow))
                    .normalized(new LinkedHashMap<>())
                    .status(ImportRowStatus.VALID)
                    .errors(List.of())
                    .build());
        }
        importSessionRowRepository.saveAll(rows);
        importSessionRowRepository.flush();

        revalidate(session, rows, mapping.requiredFieldsMissing());
        return toResponse(session, mapping.unmappedHeaders(), mapping.requiredFieldsMissing());
    }

    /**
     * Drops the greyed example rows both templates write.
     *
     * <p>Every generated sheet carries at least one row whose {@code sku} is the reserved
     * {@code EXAMPLE-SKU-DELETE-ME} marker, and the cell beside it says, in so many words,
     * "delete this row, or leave it - example rows are skipped automatically". The legacy
     * {@code ProductExcelService.parse}/{@code StockInExcelService.parse} have honoured that since
     * they wrote it. This engine did not, which made the promise false through the door almost
     * every user actually comes in by: download the template, fill it in, upload that file.
     *
     * <p>The consequence differed by kind and was bad in both. A catalog import created two
     * phantom products - "Sample Widget" with an opening balance of 100 and a ledger movement to
     * match - which the user then had to find and delete. A stock sheet acquired a row for a
     * product code they do not stock, which is an unanswerable question sitting on the review
     * screen of the single most common stock-in journey there is.
     *
     * <p>Matched on the mapped {@code sku} column rather than on a fixed index, so it still works
     * on a file whose columns have been rearranged or renamed and then mapped by hand - and does
     * nothing at all to a file with no sku column, where there is no marker to find.
     */
    private List<SheetRow> withoutExampleRows(SheetTable table, Map<String, String> columnMapping) {
        String skuHeader = columnMapping.entrySet().stream()
                .filter(entry -> ImportFields.SKU.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (skuHeader == null) {
            return table.rows();
        }
        return table.rows().stream()
                .filter(row -> {
                    String sku = table.value(row, skuHeader);
                    return sku == null
                            || !sku.trim().toUpperCase(Locale.ROOT).startsWith(EXAMPLE_SKU_MARKER_PREFIX);
                })
                .toList();
    }

    private SheetTable read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ImportExceptions.BadFile(
                    "There was no file in that upload. Pick a .xlsx or .csv file and try again.");
        }
        try {
            return spreadsheetReader.read(file);
        } catch (SpreadsheetReadException e) {
            // M2's messages are already user-facing and quote the real numbers; rewording them
            // here would produce two versions of "this file has 12,400 rows".
            throw new ImportExceptions.BadFile(e.getMessage());
        } catch (RuntimeException e) {
            throw new ImportExceptions.BadFile(
                    "We could not read that file. It needs to be a .xlsx or .csv spreadsheet.");
        }
    }

    private String filenameOf(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "upload.xlsx";
        }
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** The file's row, keyed by normalized header. Immutable from here on. */
    private Map<String, Object> rawOf(SheetTable table, SheetRow row) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : table.columnIndexes().entrySet()) {
            String value = row.cell(entry.getValue());
            if (value != null) {
                raw.put(entry.getKey(), value);
            }
        }
        return raw;
    }

    // -------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public ImportSessionResponse get(UUID id) {
        return toResponse(require(id), null, null);
    }

    /**
     * One page of rows, with ALL and ISSUES handled as the literal filter values contract
     * section 3 specifies.
     *
     * <p>ISSUES is one query with an IN-list, not two calls merged. The review screen defaults to
     * this view, and a warning that fell off the seam between two separately-paged result sets
     * would be exactly the silent drop section 8.8 forbids.
     */
    @Transactional(readOnly = true)
    public Page<ImportRowResponse> rows(UUID id, String status, Pageable pageable) {
        ImportSession session = require(id);
        Page<ImportSessionRow> page = pageOfRows(session, status, pageable);
        Map<String, String> mapping = session.getColumnMapping() == null ? Map.of() : session.getColumnMapping();
        return page.map(row -> toRowResponse(row, mapping));
    }

    private Page<ImportSessionRow> pageOfRows(ImportSession session, String status, Pageable pageable) {
        String wanted = status == null ? "ALL" : status.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(wanted)) {
            return importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(session.getId(), pageable);
        }
        if ("ISSUES".equals(wanted)) {
            return importSessionRowRepository.findAllBySessionIdAndStatusInOrderByExcelRowAsc(
                    session.getId(), ISSUE_STATUSES, pageable);
        }
        ImportRowStatus rowStatus;
        try {
            rowStatus = ImportRowStatus.valueOf(wanted);
        } catch (IllegalArgumentException e) {
            // An unknown filter is treated as no filter rather than a 400. The alternative is a
            // review screen showing an error toast because someone hand-edited a query string,
            // which helps nobody.
            return importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(session.getId(), pageable);
        }
        return importSessionRowRepository.findAllBySessionIdAndStatusOrderByExcelRowAsc(
                session.getId(), rowStatus, pageable);
    }

    @Transactional(readOnly = true)
    public Page<ImportSessionSummaryResponse> list(ImportKind kind, Pageable pageable) {
        UUID tenantId = requireTenantId();
        Page<ImportSession> page = kind == null
                ? importSessionRepository.findAllByClientIdOrderByCreatedAtDesc(tenantId, pageable)
                : importSessionRepository.findAllByClientIdAndKindOrderByCreatedAtDesc(tenantId, kind, pageable);
        return new PageImpl<>(
                page.getContent().stream().map(this::toSummary).toList(), pageable, page.getTotalElements());
    }

    // ------------------------------------------------------------------ repair

    /**
     * One cell repair, answered with the re-validated row so the grid never has to refetch.
     *
     * <p>Contract section 3 notes the grid issues one of these per keystroke-settle, so it has to
     * be cheap - and it is not as cheap as it could be, because it re-validates the whole file.
     * See the class javadoc for why that is not negotiable. What keeps it acceptable is that the
     * expensive part of validation is database lookups, and they are memoised per pass.
     */
    @Transactional
    public ImportRowResponse patchRow(UUID sessionId, UUID rowId, Map<String, Object> patch) {
        ImportSession session = requireEditable(sessionId);
        ImportSessionRow row = requireRow(session, rowId);

        Map<String, Object> normalized =
                new LinkedHashMap<>(row.getNormalized() == null ? Map.of() : row.getNormalized());
        Set<String> edited = new LinkedHashSet<>(editedKeys(row));
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (ImportFields.isReserved(entry.getKey())) {
                // Reserved keys are the engine's own bookkeeping. A client sending one is either
                // confused or probing; either way it does not get to set our internal state.
                continue;
            }
            normalized.put(entry.getKey(), entry.getValue());
            edited.add(entry.getKey());
        }
        normalized.put(ImportFields.EDITED, new ArrayList<>(edited));
        row.setNormalized(normalized);
        importSessionRowRepository.saveAndFlush(row);

        revalidate(session, allRows(session), null);
        return toRowResponse(requireRow(session, rowId), session.getColumnMapping());
    }

    @Transactional
    public ImportRowResponse skipRow(UUID sessionId, UUID rowId, boolean skipped) {
        ImportSession session = requireEditable(sessionId);
        ImportSessionRow row = requireRow(session, rowId);
        Map<String, Object> normalized =
                new LinkedHashMap<>(row.getNormalized() == null ? Map.of() : row.getNormalized());
        normalized.put(ImportFields.USER_SKIPPED, skipped);
        row.setNormalized(normalized);
        importSessionRowRepository.saveAndFlush(row);

        revalidate(session, allRows(session), null);
        return toRowResponse(requireRow(session, rowId), session.getColumnMapping());
    }

    /**
     * Re-point the columns, then read the file again through the new lens.
     *
     * <p>Every row's {@code normalized} is cleared first, and that is the point rather than
     * housekeeping: normalized values from the previous mapping would otherwise win over the
     * cells the new mapping points at, and the grid would show a column's old contents under its
     * new heading. Hand repairs go with them, which is correct - a repair was made to a cell that
     * has just been re-defined.
     */
    @Transactional
    public ImportSessionResponse patchMapping(UUID sessionId, Map<String, String> columnMapping) {
        ImportSession session = requireEditable(sessionId);
        Map<String, String> cleaned = new LinkedHashMap<>();
        columnMapping.forEach((header, field) -> {
            String normalizedHeader = HeaderNames.normalize(header);
            if (normalizedHeader != null && field != null && !field.isBlank()) {
                cleaned.put(normalizedHeader, field);
            }
        });
        session.setColumnMapping(cleaned);

        List<ImportSessionRow> rows = allRows(session);
        rows.forEach(row -> row.setNormalized(new LinkedHashMap<>()));
        importSessionRowRepository.saveAll(rows);
        importSessionRowRepository.flush();

        List<String> missing = columnMapper.missingRequiredFields(cleaned, handlerFor(session.getKind()).fields());
        revalidate(session, rows, missing);
        return toResponse(session, null, missing);
    }

    /**
     * One answer, every matching row settled - design 6.4's whole premise as a single call.
     *
     * <p>SKIP_ROWS is applied by the handler through {@code AUTO_SKIP} during re-validation
     * rather than by writing statuses here, so the engine stays out of the business of knowing
     * what a column means.
     */
    @Transactional
    public ImportSessionResponse resolveValue(UUID sessionId, ValueMappingRequest request) {
        ImportSession session = requireEditable(sessionId);
        requireResolutionAuthority(request);
        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        mappings.put(request.column(), request.from(), request.to());
        session.setValueMappings(mappings.toMap());
        importSessionRepository.saveAndFlush(session);

        revalidate(session, allRows(session), null);
        return toResponse(session, null, null);
    }

    // -------------------------------------------------------------- validation

    /**
     * The one validation pass. Everything that can change a verdict funnels through here.
     *
     * @param requiredFieldsMissing recomputed by the caller when the mapping changed; null means
     *     unchanged, in which case it is re-derived from the stored mapping.
     */
    private void revalidate(ImportSession session, List<ImportSessionRow> rows, List<String> requiredFieldsMissing) {
        ImportRowHandler handler = handlerFor(session.getKind());
        ImportBatchCache cache = new ImportBatchCache();
        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        UUID actingUserId = session.getUploadedBy() == null ? null : session.getUploadedBy().getId();

        List<ImportRowState> states = new ArrayList<>(rows.size());
        for (ImportSessionRow row : rows) {
            ImportRowState state = newState(session, row);
            RowContext ctx = new RowContext(
                    session, row, state.getInput(), state.getRawText(), mappings, cache, actingUserId);
            RowValidation validation = handler.validate(ctx);
            state.apply(validation);
            if (Boolean.TRUE.equals(validation.normalized().get(ImportFields.AUTO_SKIP))) {
                state.setSkipped(true);
            }
            states.add(state);
        }

        BatchContext batch = new BatchContext(session, states, mappings, cache, actingUserId);
        handler.validateBatch(batch);
        applyBulkFixCounts(states);
        persist(session, states, requiredFieldsMissing);
    }

    /**
     * Builds the effective inputs for one row: the file, overlaid with anything the user has done
     * to it.
     *
     * <p>The overlay rule is the subtle part. A normalized value that is non-null always wins -
     * that covers both the user's repair and the previous pass's coerced value, and re-coercing a
     * coerced value is what makes {@code validate} idempotent. A normalized value that is null
     * wins only if the user explicitly edited that cell; otherwise it means "the last pass could
     * not read this", and the raw text has to come back so the error can quote what was actually
     * typed. Without that distinction, a cell someone deliberately emptied would be helpfully
     * re-filled from the file on the very next keystroke.
     */
    private ImportRowState newState(ImportSession session, ImportSessionRow row) {
        Map<String, Object> input = new LinkedHashMap<>();
        Map<String, String> rawText = new LinkedHashMap<>();
        Map<String, Object> raw = row.getRaw() == null ? Map.of() : row.getRaw();
        Map<String, String> mapping = session.getColumnMapping() == null ? Map.of() : session.getColumnMapping();

        mapping.forEach((header, field) -> {
            Object cell = raw.get(header);
            if (cell != null) {
                rawText.put(field, cell.toString());
            }
            input.put(field, cell);
        });

        Map<String, Object> normalized = row.getNormalized() == null ? Map.of() : row.getNormalized();
        Set<String> edited = editedKeys(row);
        normalized.forEach((key, value) -> {
            if (ImportFields.isReserved(key)) {
                return;
            }
            if (value != null || edited.contains(key)) {
                input.put(key, value);
            }
        });

        ImportRowState state = new ImportRowState(row, input, rawText);
        state.setSkipped(Boolean.TRUE.equals(normalized.get(ImportFields.USER_SKIPPED)));
        return state;
    }

    @SuppressWarnings("unchecked")
    private Set<String> editedKeys(ImportSessionRow row) {
        Map<String, Object> normalized = row.getNormalized();
        if (normalized == null || !(normalized.get(ImportFields.EDITED) instanceof Collection<?> edited)) {
            return Set.of();
        }
        return new LinkedHashSet<>((Collection<String>) edited);
    }

    /**
     * {@code bulkFixCount} - the number in "Fix all 12 rows".
     *
     * <p>Computed once here, over the whole file, and stored on the error. Contract section 4
     * requires it to arrive with the error rather than be derived client-side, and the reason is
     * arithmetic: the grid holds fifty rows, the twelve broken ones may be spread over four
     * pages, and a count derived from what is on screen would be wrong in a way nobody would
     * notice until they trusted it.
     *
     * <p>Grouped on (column, code, raw value) rather than on the message. The message quotes the
     * row's own subject - "what is Garri 25kg measured in?" - so twelve rows with the same broken
     * unit have twelve different sentences and would group into twelve groups of one. The code is
     * what identifies the shared mistake, which is what the affordance is actually offering to
     * fix.
     */
    private void applyBulkFixCounts(List<ImportRowState> states) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ImportRowState state : states) {
            if (state.isSkipped()) {
                continue;
            }
            for (RowIssue issue : state.getErrors()) {
                counts.merge(bulkFixKey(state, issue), 1, Integer::sum);
            }
        }
        for (ImportRowState state : states) {
            Map<RowIssue, Integer> forRow = new LinkedHashMap<>();
            for (RowIssue issue : state.getErrors()) {
                forRow.put(issue, counts.getOrDefault(bulkFixKey(state, issue), 1));
            }
            state.setBulkFixCounts(forRow);
        }
    }

    private String bulkFixKey(ImportRowState state, RowIssue issue) {
        String value = issue.column() == null ? "" : String.valueOf(state.rawTextOf(issue.column()));
        return issue.column() + " " + issue.code() + " " + value;
    }

    /** Writes the pass's verdict back to the rows and rolls the counters up onto the file. */
    private void persist(ImportSession session, List<ImportRowState> states, List<String> requiredFieldsMissing) {
        int valid = 0;
        int errors = 0;
        int warnings = 0;
        int skipped = 0;

        for (ImportRowState state : states) {
            ImportSessionRow row = state.getRow();
            Map<String, Object> normalized = new LinkedHashMap<>(state.getNormalized());
            normalized.remove(ImportFields.AUTO_SKIP);
            carryReserved(row, normalized, ImportFields.EDITED);
            carryReserved(row, normalized, ImportFields.USER_SKIPPED);
            carryReserved(row, normalized, ImportFields.BEFORE);
            carryReserved(row, normalized, ImportFields.OUTCOME);
            carryReserved(row, normalized, ImportFields.OUTCOME_MESSAGE);
            if (state.getContinuationOf() != null) {
                normalized.put(ImportFields.CONTINUATION_OF, state.getContinuationOf());
            }
            row.setNormalized(normalized);
            row.setErrors(issuesOf(state));
            row.setResolvedEntityId(state.getResolvedEntityId());
            row.setStatus(state.derivedStatus());

            switch (row.getStatus()) {
                case SKIPPED -> skipped++;
                case ERROR -> errors++;
                case WARNING -> warnings++;
                default -> valid++;
            }
        }
        importSessionRowRepository.saveAll(states.stream().map(ImportRowState::getRow).toList());
        importSessionRowRepository.flush();

        session.setRowCount(states.size());
        session.setValidCount(valid);
        session.setErrorCount(errors);
        session.setWarningCount(warnings);
        session.setSkippedCount(skipped);

        // A committed or committing file is a historical record and its status is not up for
        // re-derivation; re-validating one must not knock it back into review.
        if (session.getStatus() == ImportStatus.COMMITTED || session.getStatus() == ImportStatus.COMMITTING) {
            importSessionRepository.saveAndFlush(session);
            return;
        }
        List<String> missing = requiredFieldsMissing != null
                ? requiredFieldsMissing
                : columnMapper.missingRequiredFields(
                        session.getColumnMapping() == null ? Map.of() : session.getColumnMapping(),
                        handlerFor(session.getKind()).fields());

        // READY on errors alone. Warnings do not block - contract section 8.8's ignored quantity
        // is a warning precisely so the rest of a good row still imports - and neither do
        // unanswered value questions, because the frontend gates Continue on errorCount and an
        // unresolved supplier has a defined fallback ("imported without a supplier") that the
        // grid has already stated out loud.
        session.setStatus(errors == 0 && missing.isEmpty() ? ImportStatus.READY : ImportStatus.NEEDS_REVIEW);
        importSessionRepository.saveAndFlush(session);
    }

    private void carryReserved(ImportSessionRow row, Map<String, Object> target, String key) {
        Map<String, Object> previous = row.getNormalized();
        if (previous != null && previous.containsKey(key)) {
            target.put(key, previous.get(key));
        }
    }

    private List<Map<String, Object>> issuesOf(ImportRowState state) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Map<RowIssue, Integer> counts = state.getBulkFixCounts();
        for (RowIssue issue : state.getErrors()) {
            Map<String, Object> map = issue.toMap();
            Integer count = counts == null ? null : counts.get(issue);
            // Written on EVERY error, including a lone one. Contract section 4 says non-null on
            // every error that has one, and "one" is the honest answer for a mistake made once -
            // the frontend is what decides that a count of 1 does not deserve a bulk button. A
            // null here where a count exists removes the highest-value interaction on the review
            // screen and fails invisibly: no error, just a missing button and a user doing a
            // ten-minute repair instead of a thirty-second one.
            map.put(RowIssue.KEY_BULK_FIX_COUNT, count == null ? 1 : count);
            issues.add(map);
        }
        // Warnings deliberately carry no count. A warning is informational - an update row's
        // ignored quantity - not a defect to repair, so there is nothing for a bulk form to
        // apply, and section 8.3's "every fix offers its bulk form" is errors-only by design
        // rather than by omission. Do not add it here later.
        for (RowIssue issue : state.getWarnings()) {
            issues.add(issue.toMap());
        }
        return issues;
    }

    // ------------------------------------------------------------------ shared

    private List<ImportSessionRow> allRows(ImportSession session) {
        return importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(session.getId());
    }

    /**
     * Rebuilds the batch view from what is already persisted, without re-running validation.
     *
     * <p>Used by preview, commit, undo and the unresolved-value list, all of which need the whole
     * file and none of which should be re-deciding it. Re-validating inside a commit in
     * particular would be a way for a verdict to change between the confirm screen the user
     * agreed to and the write that follows.
     */
    private BatchContext batchOf(ImportSession session, List<ImportSessionRow> rows) {
        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        ImportBatchCache cache = new ImportBatchCache();
        List<ImportRowState> states = new ArrayList<>(rows.size());
        for (ImportSessionRow row : rows) {
            ImportRowState state = newState(session, row);
            Map<String, Object> normalized = row.getNormalized() == null ? Map.of() : row.getNormalized();
            state.setNormalized(new LinkedHashMap<>(normalized));
            state.setResolvedEntityId(row.getResolvedEntityId());
            state.setSkipped(row.getStatus() == ImportRowStatus.SKIPPED);
            state.setContinuationOf(normalized.get(ImportFields.CONTINUATION_OF) instanceof Number number
                    ? number.intValue()
                    : null);
            // A row that was left in ERROR carries one placeholder issue rather than its real
            // list, because nothing downstream reads the text - isCommittable() only asks
            // whether there is anything at all, and re-hydrating the full set would mean
            // parsing persisted jsonb back into records for no reader.
            state.setErrors(row.getStatus() == ImportRowStatus.ERROR
                    ? new ArrayList<>(List.of(RowIssue.error(null, "PERSISTED", "This row still has something to fix.")))
                    : new ArrayList<>());
            states.add(state);
        }
        UUID actingUserId = session.getUploadedBy() == null ? null : session.getUploadedBy().getId();
        return new BatchContext(session, states, mappings, cache, actingUserId);
    }

    // ----------------------------------------------------------------- preview

    @Transactional(readOnly = true)
    public CommitPreviewResponse preview(UUID id) {
        ImportSession session = require(id);
        return CommitPreviewResponse.from(handlerFor(session.getKind()).preview(batchOf(session, allRows(session))));
    }

    // ------------------------------------------------------------------ commit

    /** @param async whether the caller should answer 202 and let the work run behind it. */
    public record CommitTicket(UUID sessionId, UUID clientId, UUID actingUserId, boolean async) {
    }

    /**
     * Claim the file for committing, under a row lock. Design 11's idempotency guard.
     *
     * <p>{@code findByIdAndClientIdForUpdate} takes {@code SELECT ... FOR UPDATE}, so two
     * requests arriving together serialise here and the second sees COMMITTING rather than READY.
     * That is the whole guard: a double-clicked Commit cannot double-import, because the
     * transition out of READY happens exactly once and the loser is told which case it hit.
     *
     * <p>Deliberately its own short transaction, separate from the write. On the async path the
     * lock has to be released before the HTTP response goes out, or the worker thread would sit
     * behind a lock held by a request that has already finished.
     */
    @Transactional
    public CommitTicket beginCommit(UUID id, UUID actingUserId) {
        UUID tenantId = requireTenantId();
        ImportSession session = importSessionRepository
                .findByIdAndClientIdForUpdate(id, tenantId)
                .orElseThrow(ImportExceptions.NotFound::new);
        requireKindAuthority(session);

        switch (session.getStatus()) {
            case COMMITTED -> throw new ImportExceptions.NotCommittable(
                    "This file has already been imported. Open it from your recent imports to see what happened.");
            case COMMITTING -> throw new ImportExceptions.NotCommittable(
                    "This file is being imported right now. Give it a moment - it will finish on its own.");
            case EXPIRED -> throw new ImportExceptions.NotCommittable(
                    "We only keep an uploaded file for two days, and this one has passed that. Upload it again.");
            case READY -> {
                // The only way through.
            }
            default -> throw new ImportExceptions.NotCommittable(
                    "There is still something to fix in this file before it can be imported.");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            session.setStatus(ImportStatus.EXPIRED);
            throw new ImportExceptions.NotCommittable(
                    "We only keep an uploaded file for two days, and this one has passed that. Upload it again.");
        }

        session.setStatus(ImportStatus.COMMITTING);
        importSessionRepository.saveAndFlush(session);
        return new CommitTicket(
                session.getId(),
                session.getClientId(),
                actingUserId,
                session.getRowCount() > ImportLimits.ASYNC_ROW_THRESHOLD);
    }

    /**
     * The write. One transaction over the whole batch, opened and owned here (design 6.5).
     *
     * <p>Called on the request thread for a small file and on a worker for a large one; the
     * tenant is re-established from the ticket either way, so the two paths run the same code.
     */
    @Transactional
    public void runCommit(CommitTicket ticket) {
        ImportSession session = importSessionRepository
                .findByIdAndClientId(ticket.sessionId(), ticket.clientId())
                .orElseThrow(ImportExceptions.NotFound::new);
        List<ImportSessionRow> rows = allRows(session);
        BatchContext batch = batchOf(session, rows);
        CommitOutcome outcome = handlerFor(session.getKind()).commit(batch);

        for (ImportRowState state : batch.states()) {
            ImportSessionRow row = state.getRow();
            Map<String, Object> normalized = new LinkedHashMap<>(state.getNormalized());
            if (state.getOutcome() != null) {
                normalized.put(ImportFields.OUTCOME, state.getOutcome());
                normalized.put(ImportFields.OUTCOME_MESSAGE, state.getOutcomeMessage());
            }
            row.setNormalized(normalized);
            row.setResolvedEntityId(state.getResolvedEntityId());
            if (ImportFields.OUTCOME_SKIPPED.equals(state.getOutcome())) {
                row.setStatus(ImportRowStatus.SKIPPED);
            } else if (ImportFields.OUTCOME_FAILED.equals(state.getOutcome())) {
                row.setStatus(ImportRowStatus.ERROR);
            } else if (state.getOutcome() != null) {
                row.setStatus(ImportRowStatus.COMMITTED);
            }
        }
        importSessionRowRepository.saveAll(rows);

        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        mappings.putResult(resultMap(outcome));
        session.setValueMappings(mappings.toMap());
        session.setStatus(ImportStatus.COMMITTED);
        session.setCommittedAt(OffsetDateTime.now());
        session.setSkippedCount(outcome.skippedCount());
        importSessionRepository.saveAndFlush(session);
    }

    /**
     * Records a run that blew up, in its own transaction so the record survives the rollback that
     * lost the work.
     *
     * <p>Without this a failed commit would leave the file stuck in COMMITTING forever - the
     * review screen redirects COMMITTING to the result screen, the result screen would have
     * nothing to show, and the user would be stranded on a spinner with no way back. M5's polling
     * contract also requires the status to eventually leave COMMITTING one way or another.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(CommitTicket ticket) {
        importSessionRepository.findByIdAndClientId(ticket.sessionId(), ticket.clientId()).ifPresent(session -> {
            session.setStatus(ImportStatus.FAILED);
            ValueMappings mappings = new ValueMappings(session.getValueMappings());
            mappings.putResult(failureResultMap(session));
            session.setValueMappings(mappings.toMap());
            importSessionRepository.saveAndFlush(session);
        });
    }

    private Map<String, Object> resultMap(CommitOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headline", outcome.headline());
        result.put("lines", outcome.lines().stream()
                .map(line -> Map.<String, Object>of(
                        "key", line.key(), "label", line.label(), "count", line.count(), "text", line.text()))
                .toList());
        result.put("created", outcome.createdCount());
        result.put("updated", outcome.updatedCount());
        result.put("skipped", outcome.skippedCount());
        result.put("failed", outcome.failedCount());
        result.put("vendors", outcome.vendorsCreated());
        result.put("products", outcome.productsCreated());
        result.put("movements", outcome.movementsCreated());
        return result;
    }

    private Map<String, Object> failureResultMap(ImportSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headline", "This import could not be completed");
        result.put("lines", List.of(Map.<String, Object>of(
                "key", "failed",
                "label", "Nothing changed",
                "count", session.getRowCount(),
                "text", "Nothing was imported, and nothing in your catalog or your stock was changed.")));
        result.put("created", 0);
        result.put("updated", 0);
        result.put("skipped", 0);
        result.put("failed", session.getRowCount());
        result.put("vendors", 0);
        result.put("products", 0);
        result.put("movements", 0);
        return result;
    }

    // ------------------------------------------------------------------ result

    @Transactional(readOnly = true)
    public ImportResultResponse result(UUID id) {
        return resultOf(require(id));
    }

    private ImportResultResponse resultOf(ImportSession session) {
        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        Map<String, Object> stored = mappings.result();
        if (stored == null) {
            throw new ImportExceptions.NoResultYet();
        }
        boolean undone = mappings.isUndone();
        return new ImportResultResponse(
                session.getId(),
                session.getStatus(),
                String.valueOf(stored.get("headline")),
                linesOf(stored),
                intOf(stored, "created"),
                intOf(stored, "updated"),
                intOf(stored, "skipped"),
                intOf(stored, "failed"),
                intOf(stored, "vendors"),
                intOf(stored, "products"),
                intOf(stored, "movements"),
                session.getStatus() == ImportStatus.COMMITTED && !undone,
                undone ? "You have already undone this import." : null,
                "/api/imports/" + session.getId() + "/report",
                targetUrlFor(session));
    }

    /**
     * Where "View products" goes. Filtered to this import, which is what makes the result screen
     * a place you can act from rather than only read.
     */
    private String targetUrlFor(ImportSession session) {
        return session.getKind() == ImportKind.STOCK_IN
                ? "/app/stock/movements?importBatchId=" + session.getId()
                : "/app/products?importBatchId=" + session.getId();
    }

    @SuppressWarnings("unchecked")
    private List<CommitPreviewResponse.Line> linesOf(Map<String, Object> stored) {
        if (!(stored.get("lines") instanceof List<?> typed)) {
            return List.of();
        }
        List<CommitPreviewResponse.Line> result = new ArrayList<>();
        for (Object entry : typed) {
            if (entry instanceof Map<?, ?> line) {
                Map<String, Object> map = (Map<String, Object>) line;
                result.add(new CommitPreviewResponse.Line(
                        String.valueOf(map.get("key")),
                        String.valueOf(map.get("label")),
                        map.get("count") instanceof Number number ? number.intValue() : 0,
                        String.valueOf(map.get("text"))));
            }
        }
        return result;
    }

    private int intOf(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number number ? number.intValue() : 0;
    }

    // -------------------------------------------------------------------- undo

    /**
     * Reverse a committed import, or answer 409 with what is in the way.
     *
     * <p>The refusal is thrown rather than returned so it rolls back anything the handler had
     * begun before it discovered the blocker, and so it can carry contract section 4's exact
     * {@code {message, blockers}} body - see {@link ImportExceptions.UndoBlocked} for why the
     * shape of that body is itself a requirement rather than a detail.
     */
    @Transactional
    public ImportResultResponse undo(UUID id) {
        UUID tenantId = requireTenantId();
        ImportSession session = importSessionRepository
                .findByIdAndClientIdForUpdate(id, tenantId)
                .orElseThrow(ImportExceptions.NotFound::new);
        requireKindAuthority(session);

        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        if (session.getStatus() != ImportStatus.COMMITTED) {
            throw new ImportExceptions.NotCommittable("There is nothing to undo - this file was never imported.");
        }
        if (mappings.isUndone()) {
            throw new ImportExceptions.NotCommittable("You have already undone this import.");
        }

        UndoOutcome outcome = handlerFor(session.getKind()).undo(session);
        if (outcome.blocked()) {
            throw new ImportExceptions.UndoBlocked(
                    outcome.message(),
                    outcome.blockers().stream()
                            .map(blocker -> new UndoBlockedResponse.Blocker(
                                    blocker.excelRow(), blocker.label(), blocker.reason(), blocker.entityId()))
                            .toList());
        }

        mappings.markUndone(OffsetDateTime.now().toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headline", outcome.message());
        result.put("lines", outcome.lines().stream()
                .map(line -> Map.<String, Object>of(
                        "key", line.key(), "label", line.label(), "count", line.count(), "text", line.text()))
                .toList());
        result.put("created", 0);
        result.put("updated", outcome.fieldsReverted());
        result.put("skipped", 0);
        result.put("failed", 0);
        result.put("vendors", 0);
        result.put("products", 0);
        result.put("movements", outcome.movementsReversed());
        mappings.putResult(result);
        session.setValueMappings(mappings.toMap());
        importSessionRepository.saveAndFlush(session);
        return resultOf(session);
    }

    // ------------------------------------------------------------------ report

    /**
     * The .xlsx of every row and what happened to it - design 9.5's "the thing an accountant asks
     * for three weeks later".
     *
     * <p>Every row, including skipped ones. M2's {@code ImportResultReportWriter} takes plain
     * records for exactly this reason and its javadoc says so: completeness is the point, and a
     * report that quietly omitted the rows that did nothing would be unable to answer the only
     * question it is ever opened to answer, which is "what happened to this line of my file?"
     */
    @Transactional(readOnly = true)
    public byte[] report(UUID id) {
        ImportSession session = require(id);
        List<ImportFieldDescriptor> fields = handlerFor(session.getKind()).fields();

        List<ImportResultReport.Column> columns = fields.stream()
                .map(field -> new ImportResultReport.Column(field.key(), field.label()))
                .toList();

        List<ImportResultReport.Row> reportRows = new ArrayList<>();
        for (ImportSessionRow row : allRows(session)) {
            Map<String, Object> normalized = row.getNormalized() == null ? Map.of() : row.getNormalized();
            Map<String, String> values = new LinkedHashMap<>();
            for (ImportFieldDescriptor field : fields) {
                Object value = normalized.get(field.key());
                values.put(field.key(), value == null ? "" : value.toString());
            }
            reportRows.add(new ImportResultReport.Row(
                    row.getExcelRow(), outcomeFor(row), messageFor(row, normalized), values));
        }
        return reportWriter.write(new ImportResultReport.Sheet(reportHeadline(session), columns), reportRows);
    }

    private String reportHeadline(ImportSession session) {
        return session.getStatus() == ImportStatus.COMMITTED
                ? "%s - imported %s".formatted(
                        session.getOriginalFilename(),
                        session.getCommittedAt() == null ? "" : session.getCommittedAt().toLocalDate())
                : "%s - not yet imported".formatted(session.getOriginalFilename());
    }

    private String outcomeFor(ImportSessionRow row) {
        Map<String, Object> normalized = row.getNormalized();
        Object outcome = normalized == null ? null : normalized.get(ImportFields.OUTCOME);
        if (outcome != null) {
            return outcome.toString();
        }
        return switch (row.getStatus()) {
            case SKIPPED -> ImportFields.OUTCOME_SKIPPED;
            case ERROR -> ImportFields.OUTCOME_FAILED;
            default -> row.getStatus().name();
        };
    }

    private String messageFor(ImportSessionRow row, Map<String, Object> normalized) {
        Object message = normalized.get(ImportFields.OUTCOME_MESSAGE);
        if (message != null) {
            return message.toString();
        }
        List<Map<String, Object>> issues = row.getErrors();
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        return issues.stream()
                .map(issue -> String.valueOf(issue.get(RowIssue.KEY_MESSAGE)))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    // ----------------------------------------------------------------- discard

    @Transactional
    public void discard(UUID id) {
        ImportSession session = require(id);
        if (!session.isUncommitted()) {
            throw new ImportExceptions.NotCommittable(
                    "This file has already been imported, so it cannot be thrown away. Undo it instead if you want "
                            + "to reverse it.");
        }
        importSessionRowRepository.deleteAllBySessionId(session.getId());
        importSessionRepository.delete(session);
    }

    /**
     * The forty-eight-hour sweep (design 11), run with no tenant context.
     *
     * <p>Never touches COMMITTED or COMMITTING. Both {@code import_batch_id} foreign keys are
     * {@code ON DELETE RESTRICT}, so deleting a committed file would fail against every product
     * and movement it stamped - and if it somehow succeeded it would erase the provenance of real
     * inventory. {@code ImportSession.isUncommitted()} states the rule once and
     * {@link #COLLECTABLE_STATUSES} is the enumerated form of it.
     *
     * @return how many were collected, so the caller can log something meaningful.
     */
    @Transactional
    public int collectExpired(int batchSize) {
        List<ImportSession> expired = importSessionRepository
                .findExpiredUncommitted(OffsetDateTime.now(), COLLECTABLE_STATUSES, Pageable.ofSize(batchSize))
                .getContent();
        for (ImportSession session : expired) {
            importSessionRowRepository.deleteAllBySessionId(session.getId());
            importSessionRepository.delete(session);
        }
        return expired.size();
    }

    // -------------------------------------------------------------- assembling

    private ImportSessionResponse toResponse(
            ImportSession session, List<String> unmappedHeaders, List<String> requiredFieldsMissing) {
        ImportRowHandler handler = handlerFor(session.getKind());
        List<ImportFieldDescriptor> fields = handler.fields();
        Map<String, String> mapping = session.getColumnMapping() == null ? Map.of() : session.getColumnMapping();

        List<String> missing = requiredFieldsMissing != null
                ? requiredFieldsMissing
                : columnMapper.missingRequiredFields(mapping, fields);
        List<String> unmapped = unmappedHeaders != null ? unmappedHeaders : unmappedHeadersOf(session, mapping);

        // Contract section 4 wants an explicit null for a header that resolved to nothing, so the
        // mapping screen can render one row per column in the file. A header omitted entirely
        // would be a column the user has no way to re-point.
        Map<String, String> wireMapping = new LinkedHashMap<>(mapping);
        unmapped.forEach(header -> wireMapping.put(header, null));

        List<UnresolvedValue> unresolved = session.getStatus() == ImportStatus.COMMITTED
                ? List.of()
                : handler.unresolvedValues(batchOf(session, allRows(session)));

        return new ImportSessionResponse(
                session.getId(),
                session.getKind(),
                session.getMode(),
                session.getStatus(),
                session.getOriginalFilename(),
                session.getRowCount(),
                session.getValidCount(),
                session.getErrorCount(),
                session.getWarningCount(),
                session.getSkippedCount(),
                !missing.isEmpty(),
                wireMapping,
                unmapped,
                missing,
                fields,
                unresolved,
                uploaderNameOf(session),
                session.getCreatedAt(),
                session.getExpiresAt(),
                session.getCommittedAt());
    }

    /**
     * Headers present in the file but pointing at no field.
     *
     * <p>Recovered from the rows rather than stored on the file's own row, because {@code raw} is
     * keyed by header and therefore still knows every column the file had - which means the
     * mapping screen keeps working after a refresh without needing a column of its own in a
     * schema this module does not own.
     */
    private List<String> unmappedHeadersOf(ImportSession session, Map<String, String> mapping) {
        Set<String> headers = new LinkedHashSet<>();
        importSessionRowRepository
                .findAllBySessionIdOrderByExcelRowAsc(session.getId(), Pageable.ofSize(20))
                .forEach(row -> {
                    if (row.getRaw() != null) {
                        headers.addAll(row.getRaw().keySet());
                    }
                });
        headers.removeAll(mapping.keySet());
        return List.copyOf(headers);
    }

    private String uploaderNameOf(ImportSession session) {
        User uploader = session.getUploadedBy();
        return uploader == null ? "Someone at your company" : uploader.getUsername();
    }

    private ImportRowResponse toRowResponse(ImportSessionRow row, Map<String, String> mapping) {
        Map<String, Object> normalized = row.getNormalized() == null ? Map.of() : row.getNormalized();
        Map<String, Object> raw = row.getRaw() == null ? Map.of() : row.getRaw();
        Map<String, String> columnMapping = mapping == null ? Map.of() : mapping;

        // raw goes out keyed by field, projected through the current mapping - see the class
        // javadoc for why it is not stored that way.
        Map<String, Object> wireRaw = new LinkedHashMap<>();
        columnMapping.forEach((header, field) -> {
            Object value = raw.get(header);
            if (value != null) {
                wireRaw.put(field, value);
            }
        });

        Map<String, Object> wireNormalized = new LinkedHashMap<>();
        normalized.forEach((key, value) -> {
            if (!ImportFields.isReserved(key)) {
                wireNormalized.put(key, value);
            }
        });

        List<ImportRowResponse.Error> errors = new ArrayList<>();
        List<ImportRowResponse.Warning> warnings = new ArrayList<>();
        List<Map<String, Object>> stored = row.getErrors() == null ? List.of() : row.getErrors();
        for (Map<String, Object> issue : stored) {
            String column = asText(issue.get(RowIssue.KEY_COLUMN));
            String message = asText(issue.get(RowIssue.KEY_MESSAGE));
            if (RowIssue.Severity.WARNING.name().equals(asText(issue.get(RowIssue.KEY_SEVERITY)))) {
                warnings.add(new ImportRowResponse.Warning(column, message));
                continue;
            }
            String suggestionValue = asText(issue.get(RowIssue.KEY_SUGGESTION_VALUE));
            errors.add(new ImportRowResponse.Error(
                    column,
                    message,
                    suggestionValue == null
                            ? null
                            : new ImportRowResponse.Suggestion(
                                    suggestionValue, asText(issue.get(RowIssue.KEY_SUGGESTION_LABEL))),
                    issue.get(RowIssue.KEY_BULK_FIX_COUNT) instanceof Number number ? number.intValue() : null));
        }

        return new ImportRowResponse(
                row.getId(),
                row.getExcelRow(),
                row.getStatus(),
                wireRaw,
                wireNormalized,
                errors,
                warnings,
                row.getResolvedEntityId(),
                resolvedLabelOf(row, normalized),
                normalized.get(ImportFields.CONTINUATION_OF) instanceof Number number ? number.intValue() : null,
                asText(normalized.get(ImportFields.OUTCOME)));
    }

    /**
     * The name behind {@code resolvedEntityId}, taken from whichever column of this kind names
     * the thing. Contract section 8.7: the id is sent so links can be built, and the label is the
     * only part that is ever rendered.
     */
    private String resolvedLabelOf(ImportSessionRow row, Map<String, Object> normalized) {
        if (row.getResolvedEntityId() == null) {
            return null;
        }
        Object name = normalized.get(ImportFields.NAME);
        if (name == null) {
            name = normalized.get(ImportFields.PRODUCT_NAME);
        }
        if (name == null) {
            name = normalized.get(ImportFields.SKU);
        }
        return name == null ? null : name.toString();
    }

    private ImportSessionSummaryResponse toSummary(ImportSession session) {
        ValueMappings mappings = new ValueMappings(session.getValueMappings());
        Map<String, Object> stored = mappings.result();
        Integer created = stored == null ? null : intOf(stored, "created");
        Integer updated = stored == null ? null : intOf(stored, "updated");
        return new ImportSessionSummaryResponse(
                session.getId(),
                session.getKind(),
                session.getStatus(),
                session.getOriginalFilename(),
                session.getRowCount(),
                created,
                updated,
                summaryTextOf(session, mappings, stored),
                uploaderNameOf(session),
                session.getCreatedAt(),
                session.getCommittedAt(),
                session.getStatus() == ImportStatus.COMMITTED && !mappings.isUndone());
    }

    /**
     * "38 created, 4 updated" - composed here so the list never string-builds a count. Same rule
     * the preview and result lines follow (contract section 4), and the same reason: copy has
     * rules, and rules applied in a list component get applied differently in the next list
     * component.
     */
    private String summaryTextOf(ImportSession session, ValueMappings mappings, Map<String, Object> stored) {
        if (mappings.isUndone()) {
            return "Undone";
        }
        if (stored == null) {
            return switch (session.getStatus()) {
                case NEEDS_REVIEW -> session.getErrorCount() > 0
                        ? ImportCopy.rows(session.getErrorCount()) + " to fix"
                        : "Waiting for review";
                case READY -> ImportCopy.rows(session.getRowCount()) + " ready to import";
                case EXPIRED -> "Expired";
                case FAILED -> "Did not finish";
                default -> "Being read";
            };
        }
        List<String> parts = new ArrayList<>();
        if (intOf(stored, "created") > 0) {
            parts.add(ImportCopy.count(intOf(stored, "created")) + " created");
        }
        if (intOf(stored, "updated") > 0) {
            parts.add(ImportCopy.count(intOf(stored, "updated")) + " updated");
        }
        if (intOf(stored, "skipped") > 0) {
            parts.add(ImportCopy.count(intOf(stored, "skipped")) + " skipped");
        }
        return parts.isEmpty() ? "Nothing changed" : String.join(", ", parts);
    }

    private String asText(Object value) {
        return value == null ? null : value.toString();
    }

    // ------------------------------------------------------------- lookup/auth

    /**
     * Loads a file the caller owns and is allowed to see, or answers 404.
     *
     * <p>404 rather than 403 for another tenant's id, deliberately: a 403 would confirm that the
     * id exists, which is the one bit of information a probe is after.
     */
    private ImportSession require(UUID id) {
        ImportSession session =
                importSessionRepository.findByIdForCurrentTenant(id).orElseThrow(ImportExceptions.NotFound::new);
        requireKindAuthority(session);
        return session;
    }

    private ImportSession requireEditable(UUID id) {
        ImportSession session = require(id);
        if (!session.isUncommitted()) {
            throw new ImportExceptions.NotCommittable("This file has already been imported, so it cannot be changed.");
        }
        if (session.getStatus() == ImportStatus.EXPIRED) {
            throw new ImportExceptions.NotCommittable(
                    "We only keep an uploaded file for two days, and this one has passed that. Upload it again.");
        }
        return session;
    }

    /**
     * The kind-specific permission check the controller cannot make.
     *
     * <p>{@code @PreAuthorize} on the method can only require MANAGE_PRODUCTS <em>or</em>
     * MANAGE_INVENTORY, because the URL of {@code GET /api/imports/{id}} does not say which kind
     * of import it is - that is a property of the row, not of the route. So the real check
     * happens here, against the loaded row, exactly as contract section 3 prescribes.
     */
    private void requireKindAuthority(ImportSession session) {
        requireKindAuthority(session.getKind());
    }

    /**
     * The two extra authorities contract section 3 attaches to inline creation:
     * {@code MANAGE_VENDORS} to invent a supplier, {@code MANAGE_PRODUCTS} to invent a product.
     *
     * <p>Both handlers already consult these when they build an {@link UnresolvedValue}, which
     * sets {@code allowCreateNew} and makes the resolution card hide the offer. That is a display
     * decision and nothing more. {@code PATCH /{id}/value-mappings} is an ordinary authenticated
     * call, and anybody who can open the review screen at all - a storekeeper running the weekly
     * stock-in, say - can send a {@code CREATE_NEW} body by hand. Without this check the answer
     * is 200 and the commit goes on to write a {@code CompanyVendor} or a {@code Product}, which
     * is precisely the authority they were refused. So the flag stays advisory and the check
     * lives here, where the decision is actually recorded.
     */
    private void requireResolutionAuthority(ValueMappingRequest request) {
        if (request.to() == null || !request.to().isCreateNew()) {
            return;
        }
        if (ImportFields.VENDOR_NAME.equals(request.column()) && !hasAuthority("MANAGE_VENDORS")) {
            throw new ImportExceptions.Forbidden(
                    "You do not have permission to add a supplier at this company. Pick one that already "
                            + "exists, or ask someone who can to add it.");
        }
        if (ImportFields.SKU.equals(request.column()) && !hasAuthority("MANAGE_PRODUCTS")) {
            throw new ImportExceptions.Forbidden(
                    "You do not have permission to add a product at this company. Match this to something "
                            + "you already stock, or leave the delivery out.");
        }
    }

    /** Also called when creating, where the kind comes from the request rather than a row. */
    public void requireKindAuthority(ImportKind kind) {
        String required = kind == ImportKind.STOCK_IN ? "MANAGE_INVENTORY" : "MANAGE_PRODUCTS";
        if (!hasAuthority(required)) {
            throw new ImportExceptions.Forbidden(kind == ImportKind.STOCK_IN
                    ? "You do not have permission to record stock at this company."
                    : "You do not have permission to change the product catalog at this company.");
        }
    }

    private boolean hasAuthority(String authority) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(granted -> Objects.equals(authority, granted.getAuthority()));
    }

    /**
     * The only way to reach a row.
     *
     * <p>M1 left {@code ImportSessionRow} without a by-id-alone finder on purpose: rows carry no
     * {@code client_id}, so the pair of "load the file's row tenant-scoped, then find the line
     * within it" IS the tenancy check. Calling {@code findById} on the row repository would
     * quietly cross tenants.
     */
    private ImportSessionRow requireRow(ImportSession session, UUID rowId) {
        return importSessionRowRepository
                .findByIdAndSessionId(rowId, session.getId())
                .orElseThrow(ImportExceptions.NotFound::new);
    }

    private UUID requireTenantId() {
        UUID tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("No tenant context set");
        }
        return tenantId;
    }
}
