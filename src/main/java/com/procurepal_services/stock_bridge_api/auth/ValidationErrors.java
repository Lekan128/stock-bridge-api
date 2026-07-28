package com.procurepal_services.stock_bridge_api.auth;

import java.util.stream.Collectors;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Turns a bean-validation failure into one sentence a form can show the user.
 * Lives next to ApiError because it exists to fill one in - see
 * ProfileExceptionHandler for why these are handled per-controller-advice
 * rather than being left to Spring's default (which loses the request's
 * authentication on the /error re-dispatch and reports 403).
 */
public final class ValidationErrors {

    private ValidationErrors() {
    }

    public static String describe(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> fieldName(error) + " " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return detail.isBlank() ? "Request validation failed." : detail;
    }

    private static String fieldName(FieldError error) {
        // Multipart/@RequestPart bodies bind as "request.field"; only the field
        // means anything to whoever filled in the form.
        String field = error.getField();
        int lastDot = field.lastIndexOf('.');
        return lastDot >= 0 ? field.substring(lastDot + 1) : field;
    }
}
