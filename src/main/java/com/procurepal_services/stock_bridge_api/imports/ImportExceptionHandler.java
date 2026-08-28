package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.imports.dto.UndoBlockedResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to {@link ImportController} only - same reasoning every other handler in this codebase
 * gives: an advice that applied everywhere would start translating exceptions raised by
 * unrelated features, and the first sign of it would be a wrong message on a screen nobody
 * connected to this module.
 *
 * <h2>The one handler here that is not routine</h2>
 * {@link #handleUndoBlocked} answers 409 with {@code UndoBlockedResponse} instead of the shared
 * {@code ApiError}, and that difference is a hard requirement rather than a preference. The
 * frontend identifies the blocked-undo case structurally - {@code typeof message === 'string' &&
 * Array.isArray(blockers)} - and falls back to a generic toast on anything else. So answering
 * with {@code ApiError}, which also has a {@code message}, would be a 409 carrying a true
 * sentence that still loses every blocker: the user would be told the undo failed and never
 * shown which three of their forty deliveries are in the way, which is the entire content of the
 * answer (design 6.6).
 */
@RestControllerAdvice(assignableTypes = ImportController.class)
public class ImportExceptionHandler {

    @ExceptionHandler(ImportExceptions.NotFound.class)
    public ResponseEntity<ApiError> handleNotFound(ImportExceptions.NotFound ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(ImportExceptions.NoResultYet.class)
    public ResponseEntity<ApiError> handleNoResultYet(ImportExceptions.NoResultYet ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(ImportExceptions.Forbidden.class)
    public ResponseEntity<ApiError> handleForbidden(ImportExceptions.Forbidden ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(ImportExceptions.NotCommittable.class)
    public ResponseEntity<ApiError> handleNotCommittable(ImportExceptions.NotCommittable ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /** Contract section 4's {@code UndoBlockedResponse}, and nothing else. See the class javadoc. */
    @ExceptionHandler(ImportExceptions.UndoBlocked.class)
    public ResponseEntity<UndoBlockedResponse> handleUndoBlocked(ImportExceptions.UndoBlocked ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getBody());
    }

    @ExceptionHandler(ImportExceptions.BadFile.class)
    public ResponseEntity<ApiError> handleBadFile(ImportExceptions.BadFile ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * A commit that threw. 500 rather than 4xx because it is our fault, not the file's - the file
     * had already passed validation - and the message says "nothing was changed" because the
     * all-or-nothing transaction means that is literally true.
     */
    @ExceptionHandler(ImportExceptions.CommitFailed.class)
    public ResponseEntity<ApiError> handleCommitFailed(ImportExceptions.CommitFailed ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(ex.getMessage()));
    }
}
