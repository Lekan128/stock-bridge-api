package com.procurepal_services.stock_bridge_api.auth;

import com.procurepal_services.stock_bridge_api.auth.dto.AuthTokens;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantUserSummary;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.founding.WhatsAppNumbers;
import com.procurepal_services.stock_bridge_api.jwt.JwtService;
import com.procurepal_services.stock_bridge_api.jwt.RefreshTokenService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant user login/refresh/logout. Deliberately does not go through
 * TenantScopedRepository's ...ForCurrentTenant() methods: at login time there
 * is no tenant context yet (establishing one is the whole point), so the user
 * lookup uses the explicit findByClientIdAndUsername(clientId, username)
 * instead, with the client id taken from the just-verified client, not from
 * any trusted context.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public TenantLoginResponse login(LoginRequest request) {
        Client client = clientRepository.findBySlug(request.clientIdentifier())
                .orElseThrow(InvalidCredentialsException::new);
        if (!client.isActive()) {
            throw new ClientSuspendedException();
        }

        // The exact username first, so no existing username (an email, "warehouse-lead", digits a
        // sub-user was given) changes meaning; then the phone typed as the username, however it's
        // written (0803 123 4567, +234...). Then, for anyone (owner, 2026-11-01: staff were refused
        // with their own email and number, and "barman" for "Barman"): the username in any case, the
        // email on their account, or their phone - each only when it points at exactly one active
        // person here, the account holder first where two share an email or a number.
        String typed = request.username().trim();
        Optional<String> typedNumber = WhatsAppNumbers.normalise(typed);
        User user = userRepository.findByClientIdAndUsername(client.getId(), typed)
                .or(() -> typedNumber
                        .filter(number -> !number.equals(typed))
                        .flatMap(number -> userRepository.findByClientIdAndUsername(client.getId(), number)))
                .filter(User::isActive)
                .or(() -> byAnyName(client.getId(), typed, typedNumber))
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueLoginResponse(user, client);
    }

    /**
     * Who {@code typed} names among the company's active people when it isn't an exact username: the username in
     * any case, then the email on someone's account, then their phone (however written). Each counts only when
     * exactly one person matches - or the account holder is one of them - so a shared email or number never
     * guesses between two people.
     */
    private Optional<User> byAnyName(UUID clientId, String typed, Optional<String> typedNumber) {
        List<User> people = userRepository.findByClientIdAndActiveTrue(clientId);
        List<Predicate<User>> ways = List.of(
                person -> typed.equalsIgnoreCase(person.getUsername()),
                person -> person.getEmail() != null && typed.equalsIgnoreCase(person.getEmail().trim()),
                person -> person.getPhone() != null && typedNumber.isPresent()
                        && typedNumber.equals(WhatsAppNumbers.normalise(person.getPhone())));
        for (Predicate<User> way : ways) {
            List<User> matched = people.stream().filter(way).toList();
            if (matched.size() == 1) {
                return Optional.of(matched.getFirst());
            }
            Optional<User> owner = matched.stream().filter(User::isRoot).findFirst();
            if (owner.isPresent()) {
                return owner;
            }
        }
        return Optional.empty();
    }

    /**
     * Issues a fresh access + refresh token pair for an already-verified user
     * and builds the login response shape. Shared with ClientSignupService,
     * which calls this right after creating the client/admin user so signup
     * can log the new admin in immediately without a second round trip.
     */
    @Transactional
    public TenantLoginResponse issueLoginResponse(User user, Client client) {
        List<String> permissionCodes = PermissionCodes.of(user);
        String accessToken = jwtService.issueTenantAccessToken(user, client, permissionCodes);
        String refreshToken = refreshTokenService.issue(SubjectType.USER, user.getId());

        AuthTokens tokens = new AuthTokens(accessToken, refreshToken, jwtService.accessTokenExpirationSeconds());
        TenantUserSummary summary = new TenantUserSummary(
                user.getId(),
                user.getUsername(),
                user.getRole().getName(),
                permissionCodes,
                client.getName(),
                client.getSlug(),
                client.isPlatformOwner(),
                ClientType.orDefault(client.getClientType()));
        return new TenantLoginResponse(tokens, summary);
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        // Usable rather than merely valid: also accepts a token whose replacement the client never
        // received (a lost reply) - see RefreshTokenService for the rule and its limits.
        RefreshTokenService.UsableToken existing = refreshTokenService.findUsable(rawRefreshToken)
                .filter(token -> token.subjectType() == SubjectType.USER)
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = userRepository.findById(existing.subjectId())
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        // A client suspended after this refresh token was issued must not be
        // able to keep minting fresh access tokens through it - same intent as
        // the active check in login(), just surfaced as a generic invalid-token
        // failure here since this isn't a credentials-entry flow.
        //
        // The row is now kept rather than discarded: the refreshed token has to
        // carry up-to-date platformOwner and clientType claims, so a client whose
        // flag or kind changed picks it up on the next refresh instead of at the
        // next full login.
        Client client = clientRepository.findById(user.getClientId())
                .filter(Client::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        // Rotate: the old refresh token is single-use, limiting replay if it leaked.
        String newRefreshToken = refreshTokenService.rotate(existing);
        String accessToken = jwtService.issueTenantAccessToken(user, client, PermissionCodes.of(user));

        return new AuthTokens(accessToken, newRefreshToken, jwtService.accessTokenExpirationSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeSession(rawRefreshToken);
    }
}
