package com.procurepal_services.stock_bridge_api.auth;

import com.procurepal_services.stock_bridge_api.auth.dto.AuthTokens;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminSummary;
import com.procurepal_services.stock_bridge_api.entity.RefreshToken;
import com.procurepal_services.stock_bridge_api.entity.SubjectType;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.jwt.JwtService;
import com.procurepal_services.stock_bridge_api.jwt.RefreshTokenService;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SuperAdminAuthService {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public SuperAdminLoginResponse login(SuperAdminLoginRequest request) {
        SuperAdmin admin = superAdminRepository.findByUsername(request.username())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtService.issueSuperAdminAccessToken(admin);
        String refreshToken = refreshTokenService.issue(SubjectType.SUPER_ADMIN, admin.getId());

        AuthTokens tokens = new AuthTokens(accessToken, refreshToken, jwtService.accessTokenExpirationSeconds());
        return new SuperAdminLoginResponse(tokens, new SuperAdminSummary(admin.getId(), admin.getUsername()));
    }

    @Transactional
    public AuthTokens refresh(String rawRefreshToken) {
        RefreshToken existing = refreshTokenService.findValid(rawRefreshToken)
                .filter(token -> token.getSubjectType() == SubjectType.SUPER_ADMIN)
                .orElseThrow(InvalidRefreshTokenException::new);

        SuperAdmin admin = superAdminRepository.findById(existing.getSubjectId())
                .orElseThrow(InvalidRefreshTokenException::new);

        refreshTokenService.revoke(existing);
        String newRefreshToken = refreshTokenService.issue(SubjectType.SUPER_ADMIN, admin.getId());
        String accessToken = jwtService.issueSuperAdminAccessToken(admin);

        return new AuthTokens(accessToken, newRefreshToken, jwtService.accessTokenExpirationSeconds());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }
}
