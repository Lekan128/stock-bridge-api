package com.procurepal_services.stock_bridge_api.email.preferences;

import com.procurepal_services.stock_bridge_api.email.preferences.dto.EmailPreferencesRequest;
import com.procurepal_services.stock_bridge_api.email.preferences.dto.EmailPreferencesResponse;
import com.procurepal_services.stock_bridge_api.security.AuthenticatedUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a signed-in user turn marketing email on or off for their own account -
 * chiefly, turn it back on after a mis-clicked one-click unsubscribe.
 *
 * <h2>Why this is its own controller under /api/me rather than part of ProfileController</h2>
 * {@code PUT /api/me} replaces the profile fields wholesale: a field the caller
 * omits is cleared rather than preserved. Consent cannot live in a payload with
 * that contract - any screen editing a job title would have to remember to echo the
 * marketing flag back, and forgetting once silently opts the user out of something
 * the form never mentioned. A dedicated route means the only way to change this
 * setting is to say so.
 *
 * <p>It also keeps read and write in the places that make sense for each. Reading
 * the flag belongs to {@code /api/me}, alongside every other "who am I" field.
 * Writing it belongs here. The two are not a matched pair and do not need to be.
 *
 * <h2>Authorization is structural, and needs no SecurityConfig change</h2>
 * No {@code @PreAuthorize}, for the same reason {@code ProfileController} has none:
 * there is no id anywhere in the path, so the only account reachable is the
 * caller's own. And no new permit-all entry - {@code /api/me/email-preferences}
 * falls under the existing {@code /api/**} rule requiring the tenant audience
 * authority, so an anonymous or superadmin-audience caller is refused before the
 * handler is reached. That is the correct default here and the opposite of {@link
 * UnsubscribeController}, which had to be permitted precisely because its caller
 * can never authenticate.
 */
@RestController
@RequestMapping("/api/me/email-preferences")
@RequiredArgsConstructor
public class EmailPreferenceController {

    private final EmailPreferenceService emailPreferenceService;

    /**
     * <pre>
     * PUT /api/me/email-preferences
     * { "receivePromotionalEmail": true }
     *   -> 200 { "receivePromotionalEmail": true }
     * </pre>
     *
     * <p>PUT rather than POST because it is idempotent and sets a value rather than
     * toggling one: a retry after a dropped response lands on the same state, which
     * a "flip it" endpoint could not promise.
     *
     * @return the persisted setting. 400 if the field is absent or not a boolean;
     *     403 with no token; 404 only if the token's user no longer exists.
     */
    @PutMapping
    public EmailPreferencesResponse update(
            @Valid @RequestBody EmailPreferencesRequest request,
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
        return emailPreferenceService.setPromotionalConsent(
                principal.getUserId(), request.receivePromotionalEmail());
    }
}
