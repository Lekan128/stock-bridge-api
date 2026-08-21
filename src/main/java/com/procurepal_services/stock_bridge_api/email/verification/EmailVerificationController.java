package com.procurepal_services.stock_bridge_api.email.verification;

import com.procurepal_services.stock_bridge_api.email.verification.dto.VerifyEmailRequest;
import com.procurepal_services.stock_bridge_api.email.verification.dto.VerifyEmailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of the verification flow: one endpoint, no authentication, one
 * bearer secret in the body.
 *
 * <h2>Why this is unauthenticated, and what that costs</h2>
 * Registered permit-all in {@code SecurityConfig} (module A put the path there
 * ahead of this controller existing). It has to be: a person confirming their
 * address may not have signed in yet, may be reading mail on a device that has
 * never held a session, and in the invited-sub-user case may not yet know their
 * password. Requiring a login would mean the mail that unblocks an account can only
 * be acted on by someone who has already got past the thing it unblocks.
 *
 * <p>The consequence, spelled out because every permit-all path in this application
 * carries the same one: there is no principal, so {@code TenantResolutionFilter}
 * leaves {@code TenantContext} empty and Hibernate's tenant filter DISABLED for the
 * whole request. {@link EmailVerificationService} is written for that and says so
 * at length; do not add a handler here that assumes otherwise.
 *
 * <p>The token in the body is the only authentication this endpoint will ever have,
 * so the body is treated as hostile: size-bounded and {@code @NotBlank} at the DTO,
 * hashed before it is used to look anything up, and answered with one
 * indistinguishable refusal for every way it can be wrong.
 *
 * <h2>Why the resend endpoint is a different class</h2>
 * {@link EmailVerificationResendController} is authenticated and lives under
 * {@code /api/me}. Keeping the two apart means the security posture of each is
 * visible from its class doc rather than inferred per method, and it means no
 * future path added here can accidentally inherit permit-all - the same split
 * module C made between its unsubscribe and preference controllers.
 */
@RestController
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    /**
     * Confirms an address from an emailed token.
     *
     * <p>POST rather than GET even though a user arrives here by clicking a link.
     * The link goes to the FRONTEND ({@code /verify-email?token=...}), which renders
     * a page and calls this. Two reasons that indirection is worth it: a GET would
     * put a live credential in this API's access logs and in the {@code Referer} of
     * everything the page loads afterwards, and mail scanners prefetch links - an
     * Outlook link checker would consume the token before the human ever clicked,
     * and single-use means they would meet a dead link on their only attempt. See
     * {@link VerifyEmailRequest}.
     *
     * @return 200 with {@code {"verified": true, "message": "..."}} on success.
     *     400 with {@code ApiError} for a token that is unknown, expired, already
     *     used, superseded, or bound to an address the account no longer has -
     *     deliberately indistinguishable from each other.
     */
    @PostMapping("/api/email/verify")
    public VerifyEmailResponse verify(@Valid @RequestBody VerifyEmailRequest request) {
        return emailVerificationService.verify(request.token());
    }
}
