package com.procurepal_services.stock_bridge_api.profile;

import com.procurepal_services.stock_bridge_api.email.verification.EmailVerificationService;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.profile.dto.ChangePasswordRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.profile.dto.UpdateProfileRequest;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Objects;
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
    private final EmailVerificationService emailVerificationService;

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
        // Before the field is overwritten, because the decision below is about the
        // difference between the old value and the new one.
        applyAddressChange(user, normalize(request.email()));
        user.setFirstName(normalize(request.firstName()));
        user.setLastName(normalize(request.lastName()));
        user.setEmail(normalize(request.email()));
        user.setPhone(normalize(request.phone()));
        user.setJobTitle(normalize(request.jobTitle()));
        return ProfileResponse.from(user, findClientOrThrow(user));
    }

    /**
     * Sets the email field back to unverified when this edit actually moves the
     * account to a different address, and retires any link that is still in flight.
     *
     * <h2>Why this is not optional</h2>
     * Without it, PUT /api/me is a way to mark ANY address verified. Verify
     * ada@mine.example, which is legitimate and easy; then edit the profile to
     * finance@somebodyelse.example. The row still says is_email_verified = TRUE, and
     * EmailEligibility reads that flag against whatever address the row currently
     * holds - so the new address is now eligible for this company's order receipts
     * on the strength of a click that concerned a completely different inbox. Every
     * other part of this feature exists to stop exactly that, and the profile form
     * would be the one door left open.
     *
     * <p>It is the same hole the token's address binding closes from the other
     * direction (see {@code EmailVerificationService.verify}), which is why the two
     * are written together: the binding stops an in-flight link being redirected at
     * a new address, and this stops an already-redeemed one being reused for one.
     *
     * <h2>Why the comparison is on the EFFECTIVE address, not on users.email</h2>
     * The naive check - "did the email column change" - gets the account holder
     * wrong, and account holders are most of the verified rows on the platform. A
     * tenant's first user signs up with an email address as their USERNAME and
     * frequently has users.email null; EmailEligibility matches either column, so
     * for that row the username IS the verified address. Such a user typing their
     * own address into the email field, or clearing it again, would look like a
     * change under the naive check and would pointlessly un-verify an address they
     * have proved twice over.
     *
     * <p>So both sides are reduced to "the address this row actually asserts" -
     * email if there is one, otherwise username - and verification survives when
     * that is unchanged. Note the username itself cannot be edited here, which is
     * what makes this comparison total.
     *
     * <h2>What it deliberately does not do</h2>
     * It does not send a new verification email. A PUT that mails an
     * attacker-chosen address is precisely the unbounded send primitive
     * {@code EmailVerificationRateLimiter} exists to prevent, and rate-limiting a
     * profile save would mean a user could be refused a name change because of
     * their email history. The user gets emailVerified=false back in this very
     * response, the frontend renders the prompt, and the rate-limited resend
     * endpoint is one click away.
     *
     * <p>It also clears the flag for the OLD address as a side effect, since the
     * flag is per-row rather than per-address. That is a real, accepted cost - a
     * grandfathered account holder who adds a different contact email goes dark
     * until they confirm it. The alternative is trusting an address nobody has
     * confirmed, and between the two only one of them can be undone by the user.
     */
    private void applyAddressChange(User user, String newEmail) {
        String before = effectiveAddress(user.getEmail(), user.getUsername());
        String after = effectiveAddress(newEmail, user.getUsername());
        if (Objects.equals(before, after)) {
            return;
        }

        // Any live token is bound to the address it was mailed to, so after this
        // edit it can never be redeemed anyway - verify() would refuse it on the
        // binding check. Retiring it explicitly is what turns that dead end into
        // something answerable: the row records superseded_at, so "I clicked the
        // link and it said invalid" has an explanation instead of looking like an
        // expiry that had not happened yet.
        emailVerificationService.supersedeOutstanding(user.getId(), OffsetDateTime.now());

        if (user.isEmailVerified()) {
            user.setEmailVerified(false);
            user.setEmailVerifiedAt(null);
        }
    }

    /**
     * The address a users row asserts: its email if it has one, otherwise its
     * username. Lowercased, because that is how EmailEligibility, EmailMessage and
     * EmailVerificationService all compare addresses, and a comparison here that
     * disagreed with them would be deciding about a different string than the one
     * being mailed.
     *
     * @return null when the row asserts no address at all - a sub-user called
     *     "warehouse-lead" with no email, for whom there is nothing to verify and
     *     so nothing to un-verify
     */
    private static String effectiveAddress(String email, String username) {
        String candidate = email != null && !email.isBlank() ? email : username;
        return candidate == null || candidate.isBlank() ? null : candidate.trim().toLowerCase(Locale.ROOT);
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
