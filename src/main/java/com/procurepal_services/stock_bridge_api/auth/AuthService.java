package com.procurepal_services.stock_bridge_api.auth;

import com.procurepal_services.stock_bridge_api.auth.dto.AuthTokens;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantUserSummary;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Permission;
import com.procurepal_services.stock_bridge_api.entity.RefreshToken;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.jwt.JwtService;
import com.procurepal_services.stock_bridge_api.jwt.RefreshTokenService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.List;
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

        User user = userRepository.findByClientIdAndUsername(client.getId(), request.username())
                .filter(User::isActive)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return issueLoginResponse(user, client);
    }

    /**
     * Issues a fresh access + refresh token pair for an already-verified user
     * and builds the login response shape. Shared with ClientSignupService,
     * which calls this right after creating the client/admin user so signup
     * can log the new admin in immediately without a second round trip.
     */
    @Transactional
    public TenantLoginResponse issueLoginResponse(User user, Client client) {
        List<String> permissionCodes = permissionCodesOf(user);
        String accessToken = jwtService.issueTenantAccessToken(user, permissionCodes);
        String refreshToken = refreshTokenService.issue(SubjectType.USER, user.getId());

        AuthTokens tokens = new AuthTokens(accessToken, refreshToken, jwtService.accessTokenExpirationSeconds());
        TenantUserSummary summary = new TenantUserSummary(
                user.getId(),
                user.getUsername(),
                user.getRole().getName(),
                permissionCodes,
                client.getName(),
                client.getSlug());
        return new TenantLoginResponse(tokens, summary);
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshToken existing = refreshTokenService.findValid(rawRefreshToken)
                .filter(token -> token.getSubjectType() == SubjectType.USER)
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = userRepository.findById(existing.getSubjectId())
                .filter(User::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        // A client suspended after this refresh token was issued must not be
        // able to keep minting fresh access tokens through it - same intent as
        // the active check in login(), just surfaced as a generic invalid-token
        // failure here since this isn't a credentials-entry flow.
        clientRepository.findById(user.getClientId())
                .filter(Client::isActive)
                .orElseThrow(InvalidRefreshTokenException::new);

        // Rotate: the old refresh token is single-use, limiting replay if it leaked.
        refreshTokenService.revoke(existing);
        String newRefreshToken = refreshTokenService.issue(SubjectType.USER, user.getId());
        String accessToken = jwtService.issueTenantAccessToken(user, permissionCodesOf(user));

        return new AuthTokens(accessToken, newRefreshToken, jwtService.accessTokenExpirationSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private List<String> permissionCodesOf(User user) {
        return user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .sorted()
                .toList();
    }
}
