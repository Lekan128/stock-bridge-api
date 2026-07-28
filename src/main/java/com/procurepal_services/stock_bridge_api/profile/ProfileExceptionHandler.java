package com.procurepal_services.stock_bridge_api.profile;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to ProfileController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = ProfileController.class)
public class ProfileExceptionHandler {

    /**
     * 400, not 401: the caller's token is fine and they stay logged in. A 401
     * here would read as "your session expired" and, in the frontend, trigger a
     * logout in the middle of a form the user is still filling in.
     */
    @ExceptionHandler(IncorrectCurrentPasswordException.class)
    public ResponseEntity<ApiError> handleIncorrectCurrentPassword(IncorrectCurrentPasswordException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ApiError> handlePasswordMismatch(PasswordMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * Only reachable with a token whose user has since been deleted - the id
     * comes from the principal, never from the request.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * Handled here rather than left to Spring's default resolver. The default
     * path calls response.sendError, which re-dispatches through /error - and on
     * an ERROR dispatch JwtAuthenticationFilter doesn't re-run (OncePerRequestFilter
     * skips those), so the request arrives at /error unauthenticated and the
     * security rules turn a validation failure into a confusing 403. Writing the
     * response from an @ExceptionHandler keeps it a single dispatch and a
     * truthful 400 with the field that failed.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
