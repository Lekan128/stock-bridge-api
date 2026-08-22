package com.procurepal_services.stock_bridge_api.superadmin;

import com.procurepal_services.stock_bridge_api.analytics.InvalidAnalyticsParameterException;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.ValidationErrors;
import com.procurepal_services.stock_bridge_api.client.ClientIdentifierTakenException;
import com.procurepal_services.stock_bridge_api.marketplace.analytics.InvalidAnalyticsRangeException;
import com.procurepal_services.stock_bridge_api.order.OrderNotFoundException;
import com.procurepal_services.stock_bridge_api.settlement.PayoutBatchNotFoundException;
import com.procurepal_services.stock_bridge_api.settlement.SettlementNotAllowedException;
import com.procurepal_services.stock_bridge_api.settlement.SuperAdminReauthenticationFailedException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
            SuperAdminPlatformOwnerUserController.class,
            SuperAdminVendorController.class,
            SuperAdminVendorWaitlistController.class,
            SuperAdminSettlementController.class
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
     * Reuses the marketplace analytics exception type rather than declaring a super
     * admin twin, for the same reason PlatformRevenueService reuses that module's window
     * rules: the range a screen will refuse should not depend on which screen it is. A
     * second exception type would be a second message and, eventually, a second bound.
     */
    @ExceptionHandler(InvalidAnalyticsRangeException.class)
    public ResponseEntity<ApiError> handleInvalidAnalyticsRange(InvalidAnalyticsRangeException ex) {
        return ResponseEntity.badRequest().body(new ApiError(ex.getMessage()));
    }

    /**
     * A query parameter that will not bind: {@code ?granularity=HOURLY},
     * {@code ?status=SHIPPED}, {@code ?sellerId=all}, {@code ?from=yesterday}. Without this
     * the unhandled path turns a bad parameter into a 500 - or, once Spring's /error
     * re-dispatch has lost the request's authentication, into a 403 that reads as "you may
     * not see these numbers". The message names the parameter but never echoes the value,
     * so a crafted URL cannot reflect content back through the error body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("'" + ex.getName() + "' is not a valid value for this request."));
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

    /**
     * Siblings of ClientNotFoundException rather than reuses of it. Both are 404 and
     * both could have shared its type; they do not, because the one caller who
     * matters - a super admin approving an application, who is holding an
     * application id and about to be handed a client id - would be sent to the wrong
     * table by a message that said "Client not found". See each exception's Javadoc.
     */
    @ExceptionHandler(VendorApplicationNotFoundException.class)
    public ResponseEntity<ApiError> handleVendorApplicationNotFound(VendorApplicationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    @ExceptionHandler(VendorNotFoundException.class)
    public ResponseEntity<ApiError> handleVendorNotFound(VendorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, not 400, matching LastActiveOwnerException above: the request was
     * well-formed and the caller is allowed to make it - it is the STATE of the row
     * that makes it impossible, usually because another ops user got there first or
     * a browser tab was left open on a stale queue.
     *
     * <p>This is the guard that keeps a double-approve from being a constraint
     * violation. Approving twice would otherwise create a second client and orphan
     * the first vendor account, and no CHECK on the table objects to that - see the
     * exception's Javadoc for why the database cannot catch this one.
     */
    @ExceptionHandler(VendorApplicationAlreadyReviewedException.class)
    public ResponseEntity<ApiError> handleVendorApplicationAlreadyReviewed(
            VendorApplicationAlreadyReviewedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * Settlement (M7). Sibling of ClientNotFoundException rather than a reuse of it,
     * for the reason VendorNotFoundException gives above: the operator who hits this
     * is holding a batch id and a bank transfer they are trying to record, and
     * "Client not found" would send them to the wrong table.
     */
    @ExceptionHandler(PayoutBatchNotFoundException.class)
    public ResponseEntity<ApiError> handlePayoutBatchNotFound(PayoutBatchNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(ex.getMessage()));
    }

    /**
     * 409, matching every other "the request is fine, the state is not" case on this
     * surface: a batch already marked paid, a period already run, an order with
     * nothing to reverse. Every one of them is a money action somebody is about to
     * repeat by refreshing, so the exception's own message says what already
     * happened rather than only that something went wrong.
     */
    @ExceptionHandler(SettlementNotAllowedException.class)
    public ResponseEntity<ApiError> handleSettlementNotAllowed(SettlementNotAllowedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(ex.getMessage()));
    }

    /**
     * The super admin re-entered their password to authorise a money-policy change and
     * it did not match (M9).
     *
     * <p>403 rather than 401, and the distinction is practical rather than pedantic:
     * the request IS authenticated - a valid super admin token got it this far - and a
     * 401 tells every HTTP client that the TOKEN is bad, which in this application's
     * frontend means a refresh attempt and then a sign-out. Mistyping a password in a
     * confirmation box should not eject an operator from the admin panel. 403 says the
     * true thing: you are who you say you are, and you have not proved enough for
     * THIS.
     *
     * <p>The message is the exception's own and deliberately distinguishes nothing -
     * see its Javadoc. Nothing was written before it was thrown, so there is no
     * partial change to describe.
     */
    @ExceptionHandler(SuperAdminReauthenticationFailedException.class)
    public ResponseEntity<ApiError> handleReauthenticationFailed(SuperAdminReauthenticationFailedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiError(ex.getMessage()));
    }

    /**
     * Reuses the order module's type rather than declaring a super-admin twin: an
     * order id that resolves to nothing means the same thing whoever is asking, and
     * the reversal route is the only path here that takes one.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiError> handleOrderNotFound(OrderNotFoundException ex) {
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
