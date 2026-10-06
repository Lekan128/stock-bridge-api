package com.procurepal_services.stock_bridge_api.email.verification;

import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * "Has this user confirmed their email?" as a gate on money-moving actions.
 *
 * <p>Ordering and paying require a verified address because those are the actions
 * whose receipts, payment confirmations and delivery updates go by email - and
 * because a marketplace order placed from an unconfirmed throwaway address is the
 * cheapest kind of fake order there is. Everything else in the app (inventory,
 * browsing, the cart) stays open to an unverified account.
 *
 * <p>Checked per USER, not per company: verification is a claim about one person's
 * inbox, and a sub-user is the one pressing the button.
 */
@Component
@RequiredArgsConstructor
public class VerifiedEmailGuard {

    private final UserRepository userRepository;

    public boolean isVerified(UUID userId) {
        if (userId == null) {
            return false;
        }
        // findById is EntityManager.find, which the tenant filter does not apply to -
        // and the id comes from the authenticated principal, never from a request body.
        return userRepository.findById(userId).map(User::isEmailVerified).orElse(false);
    }

    public void requireVerified(UUID userId) {
        if (!isVerified(userId)) {
            throw new EmailNotVerifiedException();
        }
    }
}
