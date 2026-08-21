package com.procurepal_services.stock_bridge_api.email.preferences;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to this package's two controllers only - see {@code
 * ClientSignupExceptionHandler} for why advices here are never global.
 *
 * <p>Both are listed on one advice because they are two halves of one flag and
 * their failure modes are the same three. Splitting them would duplicate the
 * validation handler below, which is the one nobody remembers to copy.
 */
@RestControllerAdvice(assignableTypes = {UnsubscribeController.class, EmailPreferenceController.class})
public class EmailPreferenceExceptionHandler {

    /**
     * 400, and the same 400 for every reason a token can fail.
     *
     * <p>Not 401 or 403: there is no authentication to be missing or insufficient,
     * and a 401 would invite a mail client or a browser to go looking for a login
     * that does not exist for this endpoint. Not 404, which would say something
     * about whether an address is known - the exact fact this endpoint is built not
     * to disclose. 400 is the honest description: the request carried a token that
     * is not a token.
     *
     * <p>Note this status is only ever reached by a caller whose token failed to
     * verify, so it leaks nothing about any address. A valid token is always 200,
     * whether it matched fifty user rows or none.
     */
    @ExceptionHandler(InvalidUnsubscribeTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidUnsubscribeTokenException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * Only reachable on the authenticated endpoint, and only with a token whose user
     * has since been deleted - the id comes from the principal, never from the
     * request.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * Handled here rather than left to Spring's default resolver, for the reason
     * spelled out in {@code ProfileExceptionHandler}: the default path calls {@code
     * response.sendError}, which re-dispatches through {@code /error} with the
     * request's authentication already gone, turning a validation failure into a
     * confusing 403. This keeps it one dispatch and a truthful 400 naming the field
     * - which matters more than usual here, since the field that fails is the one
     * whose absence would otherwise have unsubscribed the caller.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
