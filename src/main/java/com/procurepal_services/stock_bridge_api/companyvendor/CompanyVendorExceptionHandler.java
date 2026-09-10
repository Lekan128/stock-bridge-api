package com.procurepal_services.stock_bridge_api.companyvendor;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to CompanyVendorController only - see ClientSignupExceptionHandler for why. */
@RestControllerAdvice(assignableTypes = CompanyVendorController.class)
public class CompanyVendorExceptionHandler {

    @ExceptionHandler(CompanyVendorNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CompanyVendorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /** 409 rather than 403 - see CompanyVendorNotEditableException for why. */
    @ExceptionHandler(CompanyVendorNotEditableException.class)
    public ResponseEntity<ApiError> handleNotEditable(CompanyVendorNotEditableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidCompanyVendorException.class)
    public ResponseEntity<ApiError> handleInvalid(InvalidCompanyVendorException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ValidationErrors.describe(ex)));
    }

    /**
     * The CHECK constraints and the partial unique index, translated rather than
     * left to become a 500. See CompanyVendorConstraints for why the database is
     * where these rules live and why that makes this handler necessary rather than
     * defensive clutter.
     *
     * <p>An unrecognised violation is rethrown deliberately. It means this table
     * grew a constraint nobody taught this class about, and a generic
     * "something was invalid" 400 would hide that until somebody read the logs -
     * a 500 with a stack trace is the honest answer to a rule the server itself
     * does not understand.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(DataIntegrityViolationException ex) {
        CompanyVendorConstraints.Violation violation =
                CompanyVendorConstraints.translate(ex).orElseThrow(() -> ex);
        return ResponseEntity.status(violation.status()).body(new ApiError(violation.message()));
    }
}
