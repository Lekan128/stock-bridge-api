package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.entity.ImportKind;
import com.procurepal_services.stock_bridge_api.entity.ImportMode;
import com.procurepal_services.stock_bridge_api.imports.dto.ColumnMappingRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.CommitPreviewResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ConfirmPackRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportLinkedPackResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionSummaryResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PatchRowRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.SkipRowRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ValueMappingRequest;
import com.procurepal_services.stock_bridge_api.imports.io.ImportLimits;
import com.procurepal_services.stock_bridge_api.product.ProductManagementService;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateFilter;
import com.procurepal_services.stock_bridge_api.product.bulk.StockInTemplateService;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Every endpoint in BULK_IMPORT_CONTRACT.md section 3.
 *
 * <h2>Authorization, in two halves</h2>
 * The method-level check can only ask for MANAGE_PRODUCTS <em>or</em> MANAGE_INVENTORY, because
 * {@code GET /api/imports/{id}} does not say from its URL which kind of import it is - that is a
 * property of the row, not of the route. So the coarse check happens here and the real one
 * happens in the service against the loaded row, exactly as contract section 3 sets out. Getting
 * this backwards - putting only MANAGE_PRODUCTS on the class - would lock a storekeeper out of
 * the stock-in flow their whole job depends on.
 *
 * <h2>Status codes that are part of the contract rather than of HTTP</h2>
 * Two of the responses below are load-bearing in a way that is invisible from the server side:
 * <ul>
 *   <li><b>202 on commit.</b> Nothing else makes the client poll. It checks
 *       {@code response.status === 202} literally and, on anything else, treats the body as the
 *       finished result - so a 200 with an empty body would send the confirm screen to a result
 *       that does not exist yet.</li>
 *   <li><b>The 409 body on undo.</b> It must be {@code {message, blockers}} and nothing else;
 *       the client detects it by {@code typeof message === 'string' && Array.isArray(blockers)}.
 *       A plain {@code ApiError} on the same status degrades to a generic toast and the panel
 *       built to render the blockers never appears. See {@code ImportExceptionHandler}.</li>
 * </ul>
 *
 * <h2>Why the download endpoints exist as GETs and still cannot be linked</h2>
 * The report and both templates are ordinary authenticated GETs, and the frontend fetches them
 * as blobs through its authed client rather than as {@code <a href>}s - the bearer token lives in
 * memory, so a bare link sends no credentials and answers 401. The endpoints are shaped for that:
 * plain GET, no token in the query string, {@code Content-Disposition} set so the saved file has
 * a sensible name.
 */
@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyAuthority('MANAGE_PRODUCTS','MANAGE_INVENTORY')")
public class ImportController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ImportSessionService importSessionService;
    private final ImportCommitExecutor commitExecutor;
    private final ProductManagementService productManagementService;
    private final StockInTemplateService stockInTemplateService;

    /**
     * Upload. Part names are exactly {@code file}, {@code kind} and {@code mode} - the frontend
     * builds this {@code FormData} by hand and cannot see a rename.
     *
     * <p>{@code mode} is optional and defaults to CREATE_ONLY, which is design 13.1's resolved
     * decision: it matches current behaviour and is the safe answer for someone who has not
     * thought about it, with the radio right there for anyone who has.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportSessionResponse> create(
            @RequestPart("file") MultipartFile file,
            @RequestPart("kind") String kind,
            @RequestPart(value = "mode", required = false) String mode,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        ImportKind importKind = parseKind(kind);
        ImportMode importMode = parseMode(mode);
        // Checked before a byte is parsed: the kind is known here, so there is no reason to read
        // a ten-megabyte file for someone who was never going to be allowed to import it.
        importSessionService.requireKindAuthority(importKind);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(importSessionService.create(file, importKind, importMode, principal.getUserId()));
    }

    @GetMapping("/{id}")
    public ImportSessionResponse get(@PathVariable UUID id) {
        return importSessionService.get(id);
    }

    /**
     * One page of rows.
     *
     * <p>{@code status} accepts the five row statuses plus the two literals contract section 3
     * defines: {@code ALL}, and {@code ISSUES} meaning ERROR+WARNING in one correctly-paged
     * response. ISSUES is the review screen's default view, and the contract is explicit that the
     * frontend must not fake it by merging two requests - page two of errors and page two of
     * warnings are not page two of anything.
     */
    @GetMapping("/{id}/rows")
    public Page<ImportRowResponse> rows(
            @PathVariable UUID id,
            @RequestParam(required = false) String status,
            @PageableDefault(size = ImportLimits.GRID_PAGE_SIZE) Pageable pageable) {
        return importSessionService.rows(id, status, pageable);
    }

    /** Returns the re-validated row, so the grid updates without a refetch (contract section 3). */
    @PatchMapping("/{id}/rows/{rowId}")
    public ImportRowResponse patchRow(
            @PathVariable UUID id, @PathVariable UUID rowId, @Valid @RequestBody PatchRowRequest request) {
        return importSessionService.patchRow(id, rowId, request.normalized());
    }

    /**
     * "Confirm" on a candidate pack (MULTI_PACK_PER_VENDOR_DESIGN.md section 6a) - creates the
     * pack the {@code COUNTED_IN_NEW_PACK} error's suggestion named, then patches and re-validates
     * the row exactly like {@link #patchRow}. Stock-in only; every other kind's handler throws.
     */
    @PostMapping("/{id}/rows/{rowId}/confirm-pack")
    public ImportRowResponse confirmPack(
            @PathVariable UUID id, @PathVariable UUID rowId, @Valid @RequestBody ConfirmPackRequest request) {
        return importSessionService.confirmCountedInPack(id, rowId, request.packagingUnit(), request.packagingSize());
    }

    @PatchMapping("/{id}/rows/{rowId}/skip")
    public ImportRowResponse skipRow(
            @PathVariable UUID id, @PathVariable UUID rowId, @Valid @RequestBody SkipRowRequest request) {
        return importSessionService.skipRow(id, rowId, Boolean.TRUE.equals(request.skipped()));
    }

    @PatchMapping("/{id}/mapping")
    public ImportSessionResponse patchMapping(
            @PathVariable UUID id, @Valid @RequestBody ColumnMappingRequest request) {
        return importSessionService.patchMapping(id, request.columnMapping());
    }

    /** Body is the bare {@code {column, from, to}} - not wrapped, per contract section 3. */
    @PatchMapping("/{id}/value-mappings")
    public ImportSessionResponse resolveValue(
            @PathVariable UUID id, @Valid @RequestBody ValueMappingRequest request) {
        return importSessionService.resolveValue(id, request);
    }

    @GetMapping("/{id}/preview")
    public CommitPreviewResponse preview(@PathVariable UUID id) {
        return importSessionService.preview(id);
    }

    /**
     * Commit. 200 with the result below the async threshold, a literal 202 above it.
     *
     * <p>The claim on the file happens first and under a row lock, so a double-clicked button
     * cannot double-import - the second call finds COMMITTING rather than READY and gets a 409
     * (design 11). Only after that claim has been committed to the database does the work start,
     * which is what lets the async path release the lock before the response goes out.
     *
     * <p>The 202 body is ignored by the client; it polls {@code GET /{id}} until the status
     * leaves COMMITTING and then reads {@code GET /{id}/result}. It is sent as an empty 202
     * rather than a fabricated result, because there is genuinely nothing to report yet and
     * inventing one would be a lie the result screen would then display.
     */
    @PostMapping("/{id}/commit")
    public ResponseEntity<ImportResultResponse> commit(
            @PathVariable UUID id, @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        ImportSessionService.CommitTicket ticket = importSessionService.beginCommit(id, principal.getUserId());
        if (ticket.async()) {
            commitExecutor.runLater(ticket);
            return ResponseEntity.accepted().build();
        }
        commitExecutor.runNow(ticket);
        return ResponseEntity.ok(importSessionService.result(id));
    }

    /**
     * The result, as a URL of its own.
     *
     * <p>Separate from commit's return value because the result screen has to survive a refresh
     * and a shared link, and because on the async path this is the only place the outcome is ever
     * readable - commit answered 202 with nothing in it.
     */
    @GetMapping("/{id}/result")
    public ImportResultResponse result(@PathVariable UUID id) {
        return importSessionService.result(id);
    }

    /** 200 when it was undone, 409 with {@code {message, blockers}} when something is in the way. */
    @PostMapping("/{id}/undo")
    public ImportResultResponse undo(@PathVariable UUID id) {
        return importSessionService.undo(id);
    }

    @GetMapping("/{id}/report")
    public ResponseEntity<byte[]> report(@PathVariable UUID id) {
        return xlsx(importSessionService.report(id), "import-report.xlsx");
    }

    @GetMapping
    public Page<ImportSessionSummaryResponse> list(
            @RequestParam(required = false) ImportKind kind, @PageableDefault(size = 20) Pageable pageable) {
        return importSessionService.list(kind, pageable);
    }

    /**
     * What discarding this session would take with it - every pack its review screen confirmed
     * into existence (V25). The confirm dialog fetches this first so it can name them instead of
     * the plain-discard copy claiming nothing will change, which stopped being true the moment
     * MULTI_PACK_PER_VENDOR_DESIGN.md section 6a's "Confirm" started persisting independently of
     * the session.
     */
    @GetMapping("/{id}/linked-packs")
    public List<ImportLinkedPackResponse> linkedPacks(@PathVariable UUID id) {
        return importSessionService.linkedPacks(id);
    }

    /**
     * {@code removePackIds} - comma-separated, same lenient parsing as {@link #parseIds} - names
     * which of {@link #linkedPacks} the caller also wants gone. Absent or empty leaves every pack
     * exactly as before, which is the entire previous behaviour of this endpoint and stays the
     * default: removal is something the discard dialog offers, never something it does silently.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(
            @PathVariable UUID id, @RequestParam(required = false) String removePackIds) {
        importSessionService.discard(id, parseIds(removePackIds));
        return ResponseEntity.noContent().build();
    }

    /**
     * The per-tenant product template - the same generator {@code GET /api/products/template}
     * serves, so a tenant downloading from either place gets an identical file with their own
     * suppliers in the dropdown and {@code unit_price} present or absent according to whether
     * they sell.
     */
    @GetMapping("/templates/products")
    @PreAuthorize("hasAuthority('MANAGE_PRODUCTS')")
    public ResponseEntity<byte[]> productTemplate() {
        return xlsx(productManagementService.generateTemplate(), "product-import-template.xlsx");
    }

    /**
     * The pre-filled stock sheet - a download of the tenant's own catalog (design 8.1), with the
     * quantity column empty because that is the one thing they are here to fill in.
     *
     * <p>{@code productIds} wins over {@code filter} when both are present, per contract section
     * 3, and the two are never sent together by the frontend. Honouring the more specific of the
     * two rather than erroring means a selection made on the product list always produces the
     * sheet the user was looking at.
     *
     * <p>{@code vendorId} and {@code categoryId} exist for {@code BY_VENDOR} and
     * {@code BY_CATEGORY}. The contract records that no UI reaches them yet and that the backend
     * side should be built anyway, so it is.
     */
    @GetMapping("/templates/stock-in")
    @PreAuthorize("hasAuthority('MANAGE_INVENTORY')")
    public ResponseEntity<byte[]> stockInTemplate(
            @RequestParam(required = false) String productIds,
            @RequestParam(required = false) StockInTemplateFilter filter,
            @RequestParam(required = false) UUID vendorId,
            @RequestParam(required = false) UUID categoryId) {
        List<UUID> ids = parseIds(productIds);
        return xlsx(
                stockInTemplateService.generate(ids, ids.isEmpty() ? filter : null, vendorId, categoryId),
                "stock-sheet.xlsx");
    }

    /**
     * Comma-separated ids, with unparseable entries dropped rather than 400ing the whole request.
     *
     * <p>A malformed id in this list can only come from a URL somebody edited, and the useful
     * answer to that is the sheet for the products we did recognise - not an error page in place
     * of a download.
     */
    private List<UUID> parseIds(String productIds) {
        if (productIds == null || productIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(productIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> {
                    try {
                        return UUID.fromString(value);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ImportKind parseKind(String kind) {
        try {
            return ImportKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ImportExceptions.BadFile(
                    "We do not know what kind of import that is. Start again from the import page.");
        }
    }

    private ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ImportMode.CREATE_ONLY;
        }
        try {
            return ImportMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ImportExceptions.BadFile(
                    "We do not recognise that import option. Choose one of the three on the upload page.");
        }
    }

    private ResponseEntity<byte[]> xlsx(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
