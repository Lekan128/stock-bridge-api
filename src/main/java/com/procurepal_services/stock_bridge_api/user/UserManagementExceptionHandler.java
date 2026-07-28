package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to UserController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = UserController.class)
public class UserManagementExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiError> handleUsernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ApiError> handleInvalidRole(InvalidRoleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(SelfServiceNotAllowedException.class)
    public ResponseEntity<ApiError> handleSelfService(SelfServiceNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(LastActiveOwnerException.class)
    public ResponseEntity<ApiError> handleLastActiveOwner(LastActiveOwnerException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 403: the caller has MANAGE_USERS and is allowed to manage users -
     * it's the state of this particular user (the account holder) that makes the
     * change impossible, which is the same shape of failure as the last-active-
     * owner rule above.
     */
    @ExceptionHandler(RootUserRoleChangeNotAllowedException.class)
    public ResponseEntity<ApiError> handleRootRoleChange(RootUserRoleChangeNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(RootUserDeactivationNotAllowedException.class)
    public ResponseEntity<ApiError> handleRootDeactivation(RootUserDeactivationNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * 403 rather than 409, unlike its siblings: this one genuinely is about who
     * is asking. The identical request from the account holder themselves
     * succeeds, so the resource state isn't what's wrong.
     */
    @ExceptionHandler(RootPasswordResetNotAllowedException.class)
    public ResponseEntity<ApiError> handleRootPasswordReset(RootPasswordResetNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ApiError> handlePasswordMismatch(PasswordMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /** See ProfileExceptionHandler for why validation is handled here rather than left to Spring's default. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }

    /** Safety net for a concurrent create() racing the username pre-check - see ClientSignupExceptionHandler. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("That username is already in use within this organization."));
    }
}
