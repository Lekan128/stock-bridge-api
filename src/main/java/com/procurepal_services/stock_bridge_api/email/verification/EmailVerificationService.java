package com.procurepal_services.stock_bridge_api.email.verification;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import com.procurepal_services.stock_bridge_api.email.EmailProperties;
import com.procurepal_services.stock_bridge_api.email.verification.dto.ResendVerificationResponse;
import com.procurepal_services.stock_bridge_api.email.verification.dto.VerifyEmailResponse;
import com.procurepal_services.stock_bridge_api.entity.EmailVerificationToken;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.EmailVerificationTokenRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.user.UserNotFoundException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the links that turn {@code users.is_email_verified} from FALSE
 * into TRUE. V8 shipped that column and the enforcement that reads it; this class is
 * the only thing in the application that can ever satisfy it.
 *
 * <p>That is worth stating plainly because of what happens without it. Every user
 * created after V8 is unverified by default, and {@code EmailEligibility} refuses
 * TRANSACTIONAL mail to an address no user row has verified. So absent this class,
 * every company that signs up from today onward silently receives no order receipt,
 * no delivery update and no payment confirmation, forever, with nothing anywhere
 * telling them why.
 *
 * <h2>The token is a bearer credential, and is treated like one</h2>
 * 64 bytes of {@link SecureRandom}, base64url-encoded, emailed once, and never
 * stored - only its SHA-256 hash reaches the database. Identical construction to
 * {@code RefreshTokenService}, deliberately: same threat (a database dump handing
 * out working credentials), same answer. No salt and no bcrypt, which is right for
 * 512 bits of CSPRNG output and would be wrong for a human-chosen secret - there is
 * no dictionary to attack, and a slow KDF would only slow redemption.
 *
 * <h2>Tenancy: which precedent this follows, and why</h2>
 * Three modules have now had to read or write across tenants for this feature, and
 * they took two different routes. Module A put native queries on {@code
 * UserRepository}, because it matches an ADDRESS against every row on the platform
 * and needs the same answer regardless of who is asking. Module C used a native
 * UPDATE through an {@code EntityManager}, because an unsubscribe flips a flag on
 * every row sharing an address without wanting to load any of them.
 *
 * <p>This class follows <strong>module A's precedent - filter-independent access,
 * no native SQL of its own</strong> - and it gets there without needing a query at
 * all, which is the point:
 * <ul>
 *   <li>{@link EmailVerificationToken} is not a {@code TenantAwareEntity}, so
 *       Hibernate's tenant filter never applies to it. {@code findByTokenHash} is
 *       an ordinary derived query that reads identically on an authenticated
 *       thread and an anonymous one. See the repository for why that is safe here
 *       and is not on {@code User}.</li>
 *   <li>The user is then loaded by {@code UserRepository.findById}, which goes
 *       through {@code EntityManager.find}. Hibernate does not apply {@code @Filter}
 *       to a primary-key load - the same property {@code EmailRecipients} and
 *       {@code OrderResponseAssembler} already rely on - so this works with
 *       {@code TenantContext} empty, which on {@code POST /api/email/verify} it
 *       always is.</li>
 *   <li>The write is then an ordinary dirty-check on that managed entity. No native
 *       UPDATE is needed because exactly one row changes and it has already been
 *       loaded; {@code TenantAwareEntity}'s tenant stamping is {@code @PrePersist}
 *       only, so an update carries no tenant requirement.</li>
 * </ul>
 *
 * <p>Neither {@code TenantScopeExecutor} nor {@code findByIdForCurrentTenant} could
 * be used on the redemption path: both need a tenant to name, and the whole premise
 * of that endpoint is that nobody has authenticated, so there is none. The
 * authenticated resend path is the opposite and does use
 * {@code findByIdForCurrentTenant}, matching {@code ProfileService} and
 * {@code EmailPreferenceService}.
 *
 * <h2>What is deliberately NOT annotated @Transactional</h2>
 * {@link #issueLink} has no annotation, and that is load-bearing rather than an
 * oversight. It is called from inside {@code ClientSignupService.signup} and
 * {@code UserManagementService.create} - business transactions that must not fail
 * because of email. If it carried its own {@code @Transactional}, an exception
 * escaping it would cross a Spring transaction boundary and mark the caller's
 * transaction <em>rollback-only</em> permanently: the caller would catch the
 * exception, return successfully, and then fail its commit with
 * {@code UnexpectedRollbackException}. A signup would fail because of a
 * verification email. That exact failure has happened in this codebase before, on
 * {@code EmailRecipients}, and the fix was removing the annotation; see that class.
 *
 * <p>{@link #verify} and {@link #resend} ARE annotated, because they are top-level
 * entry points from controllers - there is no outer transaction to poison, and the
 * supersede-then-insert sequence needs to be atomic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    /** Same length RefreshTokenService uses, for the same reason: unguessable, cheaply. */
    private static final int TOKEN_BYTE_LENGTH = 64;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * The frontend route that receives the token and POSTs it back here. A constant
     * rather than configuration: it is a path in a React router this repository
     * also owns, so a deploy that could change it would only ever be able to break
     * it.
     */
    private static final String VERIFY_PATH = "/verify-email?token=";

    /**
     * Deliberately the same expression as {@code EmailMessage}'s, duplicated rather
     * than shared. That field is private and {@code EmailMessage} is module A's
     * file, which this module must not widen. The duplication is safe in the one
     * direction that matters: if the two ever drift, the worst outcome is that this
     * class issues a token for an address {@code EmailMessage} then discards, so the
     * mail is not sent and the user sees the link never arrived - not that an
     * unverifiable address gets verified.
     */
    private static final Pattern PLAUSIBLE_ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailNotificationService emailNotificationService;
    private final EmailProperties emailProperties;
    private final EmailVerificationProperties verificationProperties;
    private final EmailVerificationRateLimiter rateLimiter;

    // ========================================================================
    // ISSUE
    // ========================================================================

    /**
     * Mints a token for this user and returns the link to put in an email - without
     * sending anything itself.
     *
     * <p>Splitting "issue" from "send" is what lets a brand-new account receive ONE
     * email rather than two. The welcome and the invitation are already being sent
     * at exactly the moment a token is needed, so the caller asks for a link and
     * hands it to the message it was already going to send. Sending a second,
     * near-identical mail seconds later would give a new user two unread items, make
     * the important one compete for attention with the friendly one, and double the
     * chance a spam filter treats the pair as bulk.
     *
     * <p>Every prior outstanding token for this user is superseded first - see
     * {@link #supersedeOutstanding}.
     *
     * <h2>Never throws. Returns null when there is nothing to link to.</h2>
     * Three ordinary situations produce no link: the user has no plausible address,
     * {@code app.email.app-base-url} is unset (so any URL built would be a relative
     * fragment no mail client can follow), or the insert failed. Every caller treats
     * null as "send the email without a confirm block", which is exactly what the
     * templates do with a blank URL.
     *
     * <p>The catch-all is the same contract the whole email package runs on:
     * nothing about email may break a business transaction. One honest limit -
     * if the insert fails with a constraint violation, Postgres has already poisoned
     * the caller's transaction and no catch here can rescue it. That is not what
     * this guard is for. It is for the failures that are actually plausible: a null
     * nobody expected, a user row in a shape a future refactor introduces.
     *
     * @return a link, or null if none could be produced
     */
    public VerificationLink issueLink(User user) {
        try {
            String address = resolveAddress(user);
            if (address == null) {
                log.debug("Not issuing a verification token: the user has no plausible email address.");
                return null;
            }
            String baseUrl = emailProperties.normalizedAppBaseUrl();
            if (baseUrl.isBlank()) {
                // Matches EmailLayout.button's rule exactly: no link at all beats a
                // link that 404s. A deploy in this state has bigger problems, and
                // they are already logged at startup.
                log.warn("Not issuing a verification token: app.email.app-base-url is not configured, so "
                        + "the link would have no host.");
                return null;
            }

            String rawToken = mintToken(user, address);
            String url = baseUrl + VERIFY_PATH + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
            return new VerificationLink(url, VerificationLink.humanise(verificationProperties.tokenTtl()));
        } catch (Exception e) {
            log.warn("Could not issue an email verification token; the email will be sent without a "
                    + "confirmation link: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Generates, hashes and persists, returning the raw value the caller must not
     * store anywhere.
     *
     * <p>Superseding happens here rather than in {@link #issueLink} so that it is
     * inseparable from the insert: the invariant is "at most one live token per
     * user", and a code path that could insert without retiring the previous one
     * would break it silently.
     */
    private String mintToken(User user, String address) {
        OffsetDateTime now = OffsetDateTime.now();
        supersedeOutstanding(user.getId(), now);

        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        tokenRepository.save(EmailVerificationToken.builder()
                .userId(user.getId())
                .emailAddress(address)
                .tokenHash(hash(rawToken))
                .expiresAt(now.plus(verificationProperties.tokenTtl()))
                .build());
        return rawToken;
    }

    /**
     * Retires every live token this user holds.
     *
     * <h2>Why a new link must kill the old ones</h2>
     * Three reasons, in increasing order of how much they matter.
     *
     * <p>First, it makes "how many working links to this account exist right now"
     * have the answer <em>at most one</em>, at every instant. Without it the answer
     * is "one per resend in the last 24 hours", and every one of them is a
     * credential sitting in an inbox, a mail archive, and whatever scans them.
     *
     * <p>Second, it is what makes the resend button mean what a user thinks it
     * means. Somebody clicks resend precisely because they believe something is
     * wrong with the mail they already have; leaving the old one live means the
     * broken state they were trying to escape persists alongside the fix.
     *
     * <p>Third and most important, it closes the address-change hole from the other
     * side. A user who corrects a typo in their address and requests a new link
     * would otherwise still hold a live token bound to the wrong address. The
     * binding check in {@link #verify} would refuse it, so nothing unsafe happens -
     * but it would refuse it with a message that says "expired or already used",
     * which is exactly the confusing dead end that generates a support ticket.
     * Retiring it means the row records {@code superseded_at}, and support can say
     * so.
     *
     * <p>Called on address change too, from {@code ProfileService} - see there.
     *
     * @param now the single instant the caller is stamping everything with, so a
     *     superseded token and its replacement cannot disagree about ordering
     */
    public void supersedeOutstanding(UUID userId, OffsetDateTime now) {
        List<EmailVerificationToken> outstanding =
                tokenRepository.findAllByUserIdAndConsumedAtIsNullAndSupersededAtIsNull(userId);
        for (EmailVerificationToken token : outstanding) {
            token.setSupersededAt(now);
        }
        if (!outstanding.isEmpty()) {
            tokenRepository.saveAll(outstanding);
        }
    }

    // ========================================================================
    // REDEEM
    // ========================================================================

    /**
     * Confirms a token. This runs on {@code POST /api/email/verify}, which is
     * permit-all: no principal, no {@code TenantContext}, no Hibernate tenant
     * filter. See the class doc for why every lookup below is nonetheless
     * deterministic.
     *
     * <h2>Every failure is the same failure</h2>
     * Unknown, expired, consumed, superseded, user deleted, address no longer
     * matching - six outcomes, one exception, one message. See
     * {@link InvalidVerificationTokenException} for the enumeration and
     * brute-force-feedback arguments in full. The specific reason is logged here
     * and stored on the row; it never crosses the wire.
     *
     * <h2>Replay</h2>
     * A second click on a link that worked fails, because {@code consumed_at} is
     * no longer null. That is deliberately not made idempotent-and-successful: a
     * "yes, verified" for any previously-valid token would make a leaked link a
     * permanent probe for whether an account still exists, and the second click
     * genuinely has nothing left to do.
     *
     * <h2>The address binding</h2>
     * The token carries the address it was mailed to. If the user has since changed
     * their profile email, the current address will not match and the link is
     * refused. Without this check, the sequence "request a link to an inbox I
     * control, then edit my profile to an address I do not" would mark somebody
     * else's address verified on my say-so - and being verified is what makes an
     * address eligible to receive that company's order mail.
     *
     * @throws InvalidVerificationTokenException for every failure mode
     */
    @Transactional
    public VerifyEmailResponse verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw refuse("the request carried no token");
        }
        OffsetDateTime now = OffsetDateTime.now();

        EmailVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken.trim()))
                .orElseThrow(() -> refuse("no token matches that hash"));

        if (!token.isRedeemableAt(now)) {
            throw refuse(describeDeadToken(token, now));
        }

        // findById, not a derived query: see the class doc. This is the one lookup
        // on this path that touches a tenant-scoped entity, and it must behave the
        // same with no tenant context as with one.
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> refuse("the token's user no longer exists"));

        String currentAddress = resolveAddress(user);
        if (currentAddress == null || !currentAddress.equals(token.getEmailAddress())) {
            throw refuse("the account's address has changed since the token was issued");
        }

        // Stamped before the user is touched so that a token can never be spent
        // without something having been verified, nor a user verified by a token
        // that still looks live.
        token.setConsumedAt(now);
        if (!user.isEmailVerified()) {
            user.setEmailVerified(true);
            user.setEmailVerifiedAt(now);
        }
        // No explicit save: both entities are managed inside this transaction and
        // Hibernate's dirty check flushes them at commit. Note TenantAwareEntity
        // stamps client_id on @PrePersist only, so updating a User with no tenant
        // context is fine - persisting one would not be.

        log.info("Confirmed an email address for user {} - transactional mail to it is now eligible.",
                user.getId());
        return VerifyEmailResponse.confirmed();
    }

    // ========================================================================
    // RESEND
    // ========================================================================

    /**
     * "Send me that link again", for a signed-in user, about their own address.
     *
     * <h2>Order of checks, which is not arbitrary</h2>
     * The two no-op cases - already verified, and no address on file - are answered
     * BEFORE the rate limiter is consulted. Neither sends an email, so neither is
     * abuse, and throttling them would mean a user who is already verified could
     * exhaust a budget they never spent and then be refused a link they later
     * genuinely need. The limiter is asked exactly once, immediately before the only
     * action that actually sends mail.
     *
     * <h2>Why the limiter is asked before the send rather than after</h2>
     * A slot is spent whether or not SES ultimately accepts the message. Counting
     * only successful sends would let a deploy where every send fails - a revoked
     * IAM permission, an unverified sending identity - be hammered without limit,
     * which is exactly when nobody is watching.
     *
     * @throws UserNotFoundException if the principal's user row is gone
     * @throws VerificationResendThrottledException when the budget is spent
     */
    @Transactional
    public ResendVerificationResponse resend(UUID callerId) {
        // Tenant-scoped, matching ProfileService and EmailPreferenceService: the id
        // comes from a token, and a token outliving its user's tenant should fail
        // closed the same way every other authenticated read does. The redemption
        // path above cannot do this and explains why.
        User user = userRepository.findByIdForCurrentTenant(callerId).orElseThrow(UserNotFoundException::new);

        if (user.isEmailVerified()) {
            return ResendVerificationResponse.alreadyVerified();
        }
        String address = resolveAddress(user);
        if (address == null) {
            return ResendVerificationResponse.noAddress();
        }

        EmailVerificationRateLimiter.Verdict verdict = rateLimiter.tryAcquire(user.getId());
        if (!verdict.allowed()) {
            log.info("Throttled a verification resend for user {}.", user.getId());
            throw new VerificationResendThrottledException(verdict.retryAfter());
        }

        VerificationLink link = issueLink(user);
        if (link == null) {
            // issueLink already logged why. Reported as "no address" rather than as
            // an error, because the only remaining cause is an unconfigured base URL
            // and there is nothing the user can do about either - a 500 here would
            // just be a 500 on a courtesy action.
            return ResendVerificationResponse.noAddress();
        }
        emailNotificationService.verifyEmailAddress(user, link);
        return ResendVerificationResponse.sent(address);
    }

    // ========================================================================
    // SHARED
    // ========================================================================

    /**
     * The address a token is bound to and mailed to: the profile email if there is
     * one, otherwise the username.
     *
     * <p>The username fallback is not a nicety. A tenant's first user signs up with
     * an email address AS their username and {@code users.email} is frequently
     * never filled in, so for the account holder of most companies on the platform
     * the username is the only address there is. {@code EmailRecipients.forUser}
     * resolves recipients the same way and {@code EmailEligibility} matches both
     * columns; if this method disagreed with either, the token would be bound to an
     * address the mail was not sent to, and every link would fail its own binding
     * check.
     *
     * @return the lowercased address, or null when the user has nothing that could
     *     plausibly be one - a sub-user whose username is "warehouse-lead" and who
     *     never filled in an email
     */
    private static String resolveAddress(User user) {
        if (user == null) {
            return null;
        }
        String email = user.getEmail();
        String candidate = email != null && !email.isBlank() ? email : user.getUsername();
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT);
        return PLAUSIBLE_ADDRESS.matcher(normalized).matches() ? normalized : null;
    }

    /**
     * SHA-256, hex. Identical to {@code RefreshTokenService.hash} and duplicated for
     * the same reason the address pattern above is: sharing it would mean one of the
     * two packages depending on the other for four lines, and the cost of them
     * drifting is nil - each hashes only values it produced itself, and a hash that
     * changed would simply invalidate that flow's own outstanding tokens.
     */
    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Which of the three dead states a token is in, for the log and nothing else.
     * The caller is told none of this - see
     * {@link InvalidVerificationTokenException}.
     */
    private static String describeDeadToken(EmailVerificationToken token, OffsetDateTime now) {
        if (token.getConsumedAt() != null) {
            return "the token was already used at " + token.getConsumedAt();
        }
        if (token.getSupersededAt() != null) {
            return "the token was superseded by a newer one at " + token.getSupersededAt();
        }
        return "the token expired at " + token.getExpiresAt() + " (now " + now + ")";
    }

    /**
     * One place that builds the refusal, so no throw site can accidentally put its
     * reason where a caller would see it.
     */
    private static InvalidVerificationTokenException refuse(String reason) {
        log.debug("Refusing an email verification: {}", reason);
        return new InvalidVerificationTokenException(reason);
    }
}
