package com.procurepal_services.stock_bridge_api.stock;

import com.procurepal_services.stock_bridge_api.auth.PermissionCodes;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds a stock write to the permissions the user has NOW, not the ones their access token was
 * minted with (Phase H, decision D2).
 *
 * <p>A phone records stock offline under the permissions it last saw and sends it later, and the
 * access token carries a role snapshot for its whole fifteen minutes. Without this, a STOCK_IN
 * taken away from a storekeeper at 09:00 still let their queued 09:05 delivery through. The
 * {@code @PreAuthorize} on each endpoint stays as the cheap first gate; this is the authoritative
 * one, one indexed read per write.
 *
 * <p>Called only when a write is about to be recorded, never for an idempotent replay: a write
 * the server already accepted while the user was allowed keeps its stored answer.
 */
@Component
@RequiredArgsConstructor
public class StockWritePermissionGuard {

    /** What the phone shows for the refused write - see the UI's outbox error copy. */
    static final String REFUSED = "Your account is no longer allowed to record this.";

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public void require(UUID userId, String authority) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive() || !PermissionCodes.of(user).contains(authority)) {
            throw new AccessDeniedException(REFUSED);
        }
    }
}
