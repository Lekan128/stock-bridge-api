package com.procurepal_services.stock_bridge_api.company;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to CompanyController only, like every other feature package's advice -
 * see ClientSignupExceptionHandler for why these are never global.
 *
 * Validation is the only case here, and it is not optional plumbing: without a
 * local handler Spring re-dispatches the failure to /error, and that forward is
 * authorized again as an anonymous request, so a caller who merely typed a bad
 * email address gets 403 instead of 400 (SecurityConfig and ValidationErrors both
 * document this). There is no not-found case to handle - the company is resolved
 * from the caller's own token and its absence is a broken invariant, deliberately
 * left to surface as a 500 rather than dressed up as a 404 (see CompanyService).
 */
@RestControllerAdvice(assignableTypes = CompanyController.class)
public class CompanyExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }
}
