package com.procurepal_services.stock_bridge_api.email.preferences;

import com.procurepal_services.stock_bridge_api.email.preferences.dto.EmailPreferencesResponse;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-app half of the same flag {@link UnsubscribeService} clears from a mail
 * client. This is what makes an unsubscribe reversible.
 *
 * <h2>Why reversibility is a requirement and not a nicety</h2>
 * One-click unsubscribe is one click, with no confirmation step, fired by a button
 * that mail clients place directly beside "Report spam" and sometimes beside
 * "Delete". People hit it by accident constantly. If the only way back were a
 * support ticket, every mis-click would become a manual database edit and the
 * honest answer to "can you turn my newsletter back on" would be no. A signed-in
 * user flipping their own switch needs no ticket and no token.
 *
 * <p>It also closes a loop the token scheme deliberately leaves open. {@link
 * UnsubscribeTokenService} explains that an unsubscribe token never expires, so
 * anyone who has ever seen one can re-apply it forever; that is only acceptable
 * because the damage is one boolean the owner of the account can undo from inside
 * the application. This class is that undo.
 *
 * <h2>Self-only, structurally</h2>
 * Same shape and same reasoning as {@code ProfileService}: the id comes from the
 * authenticated principal and there is no path variable, so the only account these
 * methods can reach is the caller's own - which is why the endpoint carries no
 * {@code @PreAuthorize}. Consent is personal; nobody's colleague gets to opt them
 * back in, not even an OWNER with {@code MANAGE_USERS}.
 *
 * <p>The lookup goes through {@code findByIdForCurrentTenant} rather than {@code
 * findById}, again matching {@code ProfileService}: the id comes from a token, and
 * a token that outlives its user's tenant should fail closed the way every other
 * tenant-scoped read does.
 *
 * <h2>What this does NOT reach</h2>
 * Only the caller's own row. If the same address is also on a user row in another
 * tenant, that row keeps its own setting - unlike the public unsubscribe, which
 * clears every row holding the address. The asymmetry is intentional and matches
 * {@code EmailEligibility}'s: an unsubscribe is a request from the human at that
 * inbox and must silence it everywhere, whereas opting back in is a statement about
 * one account and cannot speak for a stranger who happens to share the address.
 * Erring the other way would let one user re-subscribe an inbox that somebody else
 * had asked to be left alone.
 *
 * <p>The practical consequence, which is worth knowing before somebody files it as
 * a bug: turning the switch back on here does not guarantee marketing resumes. If
 * any other row with the same address is still opted out, {@code EmailEligibility}
 * still suppresses the send, because it counts opt-outs and zero is the only value
 * that permits a message. That is the safe direction.
 */
@Service
@RequiredArgsConstructor
public class EmailPreferenceService {

    private final UserRepository userRepository;

    /**
     * @param callerId the authenticated user's own id, from the principal
     * @param receivePromotionalEmail the new value, already known non-null (see
     *     {@code EmailPreferencesRequest} for why that matters)
     * @return the persisted state, which is simply the value that was asked for -
     *     returned rather than assumed so the UI has one source of truth
     * @throws UserNotFoundException only when the token outlives its user row
     */
    @Transactional
    public EmailPreferencesResponse setPromotionalConsent(UUID callerId, boolean receivePromotionalEmail) {
        User user = userRepository.findByIdForCurrentTenant(callerId).orElseThrow(UserNotFoundException::new);
        // Dirty checking writes this on commit; no explicit save, matching
        // ProfileService. The DB trigger maintains updated_at either way.
        user.setReceivePromotionalEmail(receivePromotionalEmail);
        return EmailPreferencesResponse.from(user);
    }
}
