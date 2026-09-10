package com.procurepal_services.stock_bridge_api.user;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scoped to RoleController only - see UserManagementExceptionHandler for why each controller gets its own. */
@RestControllerAdvice(assignableTypes = RoleController.class)
public class RoleManagementExceptionHandler {

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(RoleNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(RoleNameTakenException.class)
    public ResponseEntity<ApiError> handleNameTaken(RoleNameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidPermissionCodeException.class)
    public ResponseEntity<ApiError> handleInvalidPermissionCode(InvalidPermissionCodeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /** 400, not 403: the caller has MANAGE_ROLES - it's this particular role (a system one) that can't be touched. */
    @ExceptionHandler(SystemRoleNotEditableException.class)
    public ResponseEntity<ApiError> handleSystemRoleNotEditable(SystemRoleNotEditableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(RoleInUseException.class)
    public ResponseEntity<ApiError> handleRoleInUse(RoleInUseException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }
}
