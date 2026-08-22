package com.procurepal_services.stock_bridge_api.email.verification;

import com.procurepal_services.stock_bridge_api.email.verification.dto.ResendVerificationResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Send me the confirmation link again", for the signed-in caller's own address.
 *
 * <h2>Why it lives under /api/me and not next to /api/email/verify</h2>
 * Two reasons, and the second is the important one.
 *
 * <p>It is the same kind of thing as everything else under {@code /api/me}: a
 * self-service action with no id anywhere in the path, so the only account it can
 * reach is the caller's own. That is why it needs no {@code @PreAuthorize} - the
 * authorization is structural, exactly as {@code ProfileController} and module C's
 * {@code /api/me/email-preferences} explain.
 *
 * <p>The second reason is defensive. A sibling path like {@code
 * /api/email/verify/resend} would sit one careless {@code /api/email/**} wildcard
 * in {@code SecurityConfig}'s permit-all array away from becoming an
 * <em>unauthenticated</em> endpoint that sends mail to an attacker-chosen address.
 * Today's exact-path matcher would not do that, but the array already contains
 * three {@code /api/email/*} entries and a future fourth is likely to be added by
 * pattern. Nothing under {@code /api/me} is or could plausibly be made permit-all,
 * so the protection here does not depend on anybody remembering.
 *
 * <h2>Rate limited</h2>
 * See {@link EmailVerificationRateLimiter} for why an endpoint that causes mail to
 * be sent to a caller-controlled address cannot be unbounded, and what the
 * in-process limiter does and does not cover.
 */
@RestController
@RequestMapping("/api/me/email-verification")
@RequiredArgsConstructor
public class EmailVerificationResendController {

    private final EmailVerificationService emailVerificationService;

    /**
     * Issues a fresh token, retires any previous one, and emails the link.
     *
     * <p>Takes no body. The address is whatever is on the caller's own profile -
     * accepting one in the request would turn this into "email an arbitrary address
     * on ProcurePal's behalf", which is the thing the rate limiter exists to bound
     * rather than a feature to offer. Changing which address gets confirmed is done
     * by editing the profile ({@code PUT /api/me}) and then calling this.
     *
     * @return 200 with {@code {"sent": true, "message": "..."}} when a link went
     *     out; 200 with {@code sent: false} for the two ordinary no-ops (already
     *     verified, no usable address on the profile - see
     *     {@link ResendVerificationResponse} for why those are not errors);
     *     429 with {@code ApiError} and a {@code Retry-After} header when the
     *     caller has exhausted their budget; 404 if the principal's user row no
     *     longer exists.
     */
    @PostMapping
    public ResendVerificationResponse resend(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return emailVerificationService.resend(principal.getUserId());
    }
}
