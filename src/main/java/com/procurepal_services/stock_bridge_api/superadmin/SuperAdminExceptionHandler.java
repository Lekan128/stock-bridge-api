package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.InvalidAnalyticsParameterException;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.client.ClientIdentifierTakenException;
import com.procurepal_services.stock_bridge_api.user.InvalidRoleException;
import com.procurepal_services.stock_bridge_api.user.LastActiveOwnerException;
import com.procurepal_services.stock_bridge_api.user.PasswordMismatchException;
import com.procurepal_services.stock_bridge_api.user.RootUserDeactivationNotAllowedException;
import com.procurepal_services.stock_bridge_api.user.RootUserRoleChangeNotAllowedException;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import com.procurepal_services.stock_bridge_api.user.UsernameTakenException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Scoped to the superadmin controllers only - see ClientSignupExceptionHandler for why.
 *
 * <h2>Every controller under /api/superadmin must be listed below</h2>
 * {@code assignableTypes} is an allow-list, so a controller that is not named here
 * gets none of these handlers: a ClientNotFoundException raised from it would go
 * uncaught and surface as a 500 instead of the 404 it was written to be. That is
 * the failure mode to watch for when the next super-admin controller is added -
 * nothing warns about it, and the endpoint looks healthy right up until something
 * goes wrong on it.
 *
 * <h2>Why the user-management exceptions are re-mapped here rather than shared</h2>
 * SuperAdminUserService reuses UserManagementService's exception types deliberately
 * ("the account owner's role cannot be changed" means the same thing whoever is
 * asking), but UserManagementExceptionHandler is itself scoped to UserController
 * alone, so the same types have to be mapped again for this surface. The statuses
 * below match it exactly. Two of its handlers are deliberately NOT reproduced,
 * because SuperAdminUserService cannot raise either:
 * <ul>
 *   <li>{@code SelfServiceNotAllowedException} - a super admin is a row in
 *       {@code super_admins} and can never be the tenant user being edited, so
 *       there is no self to guard against.</li>
 *   <li>{@code RootPasswordResetNotAllowedException} - resetting the platform
 *       owner's root password is a supported lockout-recovery path here. See
 *       SuperAdminUserService for why the tenant-side objection does not carry
 *       over.</li>
 * </ul>
 * Mapping either would advertise a rule this surface does not have.
 */
@RestControllerAdvice(
        assignableTypes = {
            SuperAdminClientController.class,
            SuperAdminAnalyticsController.class,
            SuperAdminTenantUserController.class,
            SuperAdminPlatformOwnerUserController.class
        })
public class SuperAdminExceptionHandler {

    @ExceptionHandler(ClientNotFoundException.class)
    public ResponseEntity<ApiError> handleClientNotFound(ClientNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /** Reuses AnalyticsController's exception type - the per-client analytics endpoints share its validation. */
    @ExceptionHandler(InvalidAnalyticsParameterException.class)
    public ResponseEntity<ApiError> handleInvalidAnalyticsParameter(InvalidAnalyticsParameterException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 404: the caller asked for a surface that always exists and spelled it
     * correctly - what is missing is a bootstrap step nobody has run yet. The
     * exception's Javadoc carries the full reasoning, and its message names the
     * configuration to set.
     */
    @ExceptionHandler(PlatformOwnerNotBootstrappedException.class)
    public ResponseEntity<ApiError> handlePlatformOwnerNotBootstrapped(PlatformOwnerNotBootstrappedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiError> handleUserNotFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<ApiError> handleUsernameTaken(UsernameTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * Renaming a tenant's login slug can collide with exactly the rule signup
     * enforces, so it raises signup's exception rather than a parallel one - a
     * caller should not have to learn two vocabularies for one collision.
     */
    @ExceptionHandler(ClientIdentifierTakenException.class)
    public ResponseEntity<ApiError> handleClientIdentifierTaken(ClientIdentifierTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ApiError> handleInvalidRole(InvalidRoleException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(LastActiveOwnerException.class)
    public ResponseEntity<ApiError> handleLastActiveOwner(LastActiveOwnerException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 403, matching UserManagementExceptionHandler: the caller is allowed
     * to manage these users - it is the state of this one account (ProcurePal's
     * account holder) that makes the change impossible, which is the same shape of
     * failure as the last-active-owner rule above.
     */
    @ExceptionHandler(RootUserRoleChangeNotAllowedException.class)
    public ResponseEntity<ApiError> handleRootRoleChange(RootUserRoleChangeNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(RootUserDeactivationNotAllowedException.class)
    public ResponseEntity<ApiError> handleRootDeactivation(RootUserDeactivationNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(PasswordMismatchException.class)
    public ResponseEntity<ApiError> handlePasswordMismatch(PasswordMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ex.getMessage()));
    }

    /**
     * An unparseable body, which on this surface almost always means one thing: a
     * {@code paymentTerms} that is not one of the two PaymentTerms constants.
     * Handled here for the same reason validation is - left to Spring's default it
     * re-dispatches to /error, loses the request's authentication on the way, and
     * reports 403, which tells the caller they are not allowed to do something they
     * are perfectly allowed to do. The exception's own message quotes the offending
     * JSON and the Java type name, so it is summarised rather than echoed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("Request body could not be read. Check that every field has the expected type."));
    }

    /** See ProfileExceptionHandler for why validation is handled here rather than left to Spring's default. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiError(ValidationErrors.describe(ex)));
    }

    /**
     * Safety net for the races the service's pre-checks cannot close. All of them
     * are unique-index violations and all of them mean one thing to the caller: two
     * ops users (or two browser tabs) did conflicting things at once and this one
     * lost. It covers a duplicate username, a duplicate slug, and two simultaneous
     * "first user" creates racing {@code uq_users_one_root_per_client}.
     *
     * <p>The message is generic on purpose rather than echoing {@code ex}: the
     * driver's own text names columns and, on a user insert, can quote the failing
     * statement's bind parameters - one of which is a password hash. See
     * ClientSignupExceptionHandler for why the violation is allowed to propagate to
     * an advice instead of being caught inline (Postgres poisons the transaction, so
     * catching and continuing in it is not safe).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("That change conflicts with an existing record. Reload and try again."));
    }
}
