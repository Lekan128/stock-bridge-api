package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import com.procurepal_services.stock_bridge_api.auth.AuthService;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.passwordreset.dto.PasswordResetCheckResponse;
import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.EmailProperties;
import com.procurepal_services.stock_bridge_api.email.template.AccountEmails;
import com.procurepal_services.stock_bridge_api.email.verification.VerificationLink;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.PasswordResetToken;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.jwt.RefreshTokenService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.PasswordResetTokenRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service password reset by email (PASSWORD_RESET_PLAN.md): request a link,
 * check it, use it.
 *
 * <h2>Nothing here tells a stranger who has an account</h2>
 * {@link #request} answers the same way for an unknown address, an address whose
 * accounts are all deactivated, and a real one; the rate limit applies to all of
 * them alike; and every bad link fails with the same message. The only party who
 * learns anything is whoever reads the inbox, which is the person entitled to.
 *
 * <h2>Checking a link does not spend it</h2>
 * Mail-security scanners open links, people open them twice, pages get refreshed.
 * {@link #check} is read-only so none of that burns the link; only {@link #complete}
 * consumes it - the same reason the verification page redeems by POST rather than
 * on load.
 *
 * <h2>The link is bound to the address it was mailed to</h2>
 * It proves control of one inbox at one moment. If the account's email or username
 * no longer is that address when the link is used, the proof is about somebody
 * else's inbox and the link is refused.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final int TOKEN_BYTE_LENGTH = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String RESET_PATH = "/reset-password?token=";

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final AuthService authService;
    private final EmailNotificationService emailNotificationService;
    private final EmailProperties emailProperties;
    private final PasswordResetProperties properties;
    private final PasswordResetRateLimiter rateLimiter;

    /**
     * Mails one reset link per active account using this address, or nothing.
     *
     * @throws PasswordResetThrottledException when the address or the caller has asked
     *     too often - regardless of whether the address has any accounts
     */
    @Transactional
    public void request(String rawEmail, String clientIp) {
        String address = normalize(rawEmail);
        if (address == null) {
            return;
        }
        Duration wait = rateLimiter.tryAcquire(address, clientIp);
        if (!wait.isZero()) {
            throw new PasswordResetThrottledException(wait);
        }
        String baseUrl = emailProperties.normalizedAppBaseUrl();
        if (baseUrl.isBlank()) {
            log.warn("Not sending a password reset link: app.email.app-base-url is not configured, so the link "
                    + "would have no host.");
            return;
        }

        List<UUID> userIds = userRepository.findActiveUserIdsMatchingEmailAddress(address, properties.maxAccounts());
        if (userIds.isEmpty()) {
            log.debug("Password reset requested for an address with no active account; nothing sent.");
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<AccountEmails.ResetLink> links = new ArrayList<>();
        for (UUID userId : userIds) {
            User user = userRepository.findById(userId).orElse(null);
            Client client = user == null ? null : clientRepository.findById(user.getClientId()).orElse(null);
            if (user == null || client == null) {
                continue;
            }
            String rawToken = mintToken(user.getId(), address, now);
            links.add(new AccountEmails.ResetLink(client.getName(), client.getSlug(), user.getUsername(),
                    baseUrl + RESET_PATH + URLEncoder.encode(rawToken, StandardCharsets.UTF_8)));
        }
        if (links.isEmpty()) {
            return;
        }
        emailNotificationService.passwordReset(address, links, VerificationLink.humanise(properties.tokenTtl()));
        log.info("Sent a password reset email covering {} account(s).", links.size());
    }

    /** Which account a link is for, without using it up. */
    @Transactional(readOnly = true)
    public PasswordResetCheckResponse check(String rawToken) {
        PasswordResetToken token = tokenRepository.findByTokenHash(hashOrRefuse(rawToken))
                .orElseThrow(() -> refuse("no token matches that hash"));
        Account account = redeemable(token, OffsetDateTime.now());
        return new PasswordResetCheckResponse(
                account.client().getName(), account.client().getSlug(), account.user().getUsername());
    }

    /**
     * Sets the new password, uses the link up, logs out every other session, and signs
     * the person in - they just proved they read the inbox, and sending them back to a
     * login form that wants a Company ID they may not remember is the friction this
     * flow exists to remove (owners' decision, 2026-10-06).
     */
    @Transactional
    public TenantLoginResponse complete(String rawToken, String newPassword) {
        OffsetDateTime now = OffsetDateTime.now();
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(hashOrRefuse(rawToken))
                .orElseThrow(() -> refuse("no token matches that hash"));
        Account account = redeemable(token, now);
        User user = account.user();

        token.setConsumedAt(now);
        supersedeOutstanding(user.getId(), now);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Using the link proves this inbox is theirs - the same proof a verification
        // link gives - so the address is confirmed, but only when it is the address
        // their mail actually goes to.
        if (!user.isEmailVerified() && token.getEmailAddress().equals(primaryAddress(user))) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(now);
        }
        int ended = refreshTokenService.revokeAll(SubjectType.USER, user.getId());
        emailNotificationService.passwordChangedBySelf(token.getEmailAddress(), user);
        log.info("Password reset completed for user {}; ended {} other session(s).", user.getId(), ended);

        return authService.issueLoginResponse(user, account.client());
    }

    // ------------------------------------------------------------------------

    private record Account(User user, Client client) {
    }

    private Account redeemable(PasswordResetToken token, OffsetDateTime now) {
        if (!token.isRedeemableAt(now)) {
            throw refuse(token.getConsumedAt() != null ? "already used"
                    : token.getSupersededAt() != null ? "superseded by a newer link" : "expired");
        }
        User user = userRepository.findById(token.getUserId())
                .filter(User::isActive)
                .orElseThrow(() -> refuse("the user no longer exists or is deactivated"));
        Client client = clientRepository.findById(user.getClientId())
                .filter(Client::isActive)
                .orElseThrow(() -> refuse("the company no longer exists or is suspended"));
        if (!token.getEmailAddress().equals(lower(user.getEmail()))
                && !token.getEmailAddress().equals(lower(user.getUsername()))) {
            throw refuse("the account's address has changed since the link was sent");
        }
        return new Account(user, client);
    }

    private String mintToken(UUID userId, String address, OffsetDateTime now) {
        supersedeOutstanding(userId, now);
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        tokenRepository.save(PasswordResetToken.builder()
                .userId(userId)
                .emailAddress(address)
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(properties.tokenTtl()))
                .build());
        return rawToken;
    }

    /** Only the newest link works: asking again, or using one, retires the rest. */
    private void supersedeOutstanding(UUID userId, OffsetDateTime now) {
        List<PasswordResetToken> outstanding =
                tokenRepository.findAllByUserIdAndConsumedAtIsNullAndSupersededAtIsNull(userId);
        for (PasswordResetToken token : outstanding) {
            if (token.getConsumedAt() == null) {
                token.setSupersededAt(now);
            }
        }
        tokenRepository.saveAll(outstanding);
    }

    /**
     * The address mail to this user goes to, as {@code EmailVerificationService}
     * resolves it: the email column, falling back to a username that is an address.
     */
    private static String primaryAddress(User user) {
        String email = lower(user.getEmail());
        return email != null ? email : lower(user.getUsername());
    }

    private static String hashOrRefuse(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw refuse("the request carried no token");
        }
        return hash(rawToken.trim());
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String normalize(String address) {
        return lower(address);
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static InvalidPasswordResetTokenException refuse(String reason) {
        log.debug("Refusing a password reset link: {}", reason);
        return new InvalidPasswordResetTokenException();
    }
}
