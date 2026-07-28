package com.procurepal_services.stock_bridge_api.profile;

import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.profile.dto.ChangePasswordRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.profile.dto.UpdateProfileRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The self-only half of user management. Every method takes the caller's own
 * id, resolved from the authenticated principal by ProfileController - there
 * is no path variable to point at somebody else, which is why these endpoints
 * can be open to every authenticated tenant user with no permission check.
 *
 * The lookup still goes through findByIdForCurrentTenant rather than
 * findById: the id comes from a token, and a token outliving its user's tenant
 * (or a future flow that mints one differently) should fail closed the same
 * way every other tenant-scoped read does.
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public ProfileResponse get(UUID callerId) {
        User user = findSelfOrThrow(callerId);
        return ProfileResponse.from(user, findClientOrThrow(user));
    }

    /**
     * Replaces the profile fields wholesale (see UpdateProfileRequest) - a
     * field the user left empty is cleared, not silently preserved.
     */
    @Transactional
    public ProfileResponse update(UUID callerId, UpdateProfileRequest request) {
        User user = findSelfOrThrow(callerId);
        user.setFirstName(normalize(request.firstName()));
        user.setLastName(normalize(request.lastName()));
        user.setEmail(normalize(request.email()));
        user.setPhone(normalize(request.phone()));
        user.setJobTitle(normalize(request.jobTitle()));
        return ProfileResponse.from(user, findClientOrThrow(user));
    }

    @Transactional
    public void changePassword(UUID callerId, ChangePasswordRequest request) {
        User user = findSelfOrThrow(callerId);

        // Current password first: a caller who can't prove who they are gets the
        // same answer whether or not their new password was well-formed.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IncorrectCurrentPasswordException();
        }
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new PasswordMismatchException();
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    }

    private User findSelfOrThrow(UUID callerId) {
        return userRepository.findByIdForCurrentTenant(callerId).orElseThrow(UserNotFoundException::new);
    }

    private Client findClientOrThrow(User user) {
        return clientRepository.findById(user.getClientId())
                .orElseThrow(() -> new IllegalStateException("User " + user.getId() + " has no client"));
    }

    /** Blank is how a form says "empty"; the database should say NULL. */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
