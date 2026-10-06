package com.procurepal_services.stock_bridge_api.onboarding;

import com.procurepal_services.stock_bridge_api.auth.AuthService;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantScopeExecutor;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "We load your products for you" (the founding offer's Bonus 1), done with the shop's own tools.
 *
 * <p>A super admin opens a shop's workspace as its <em>Procurepaddy support</em> staff account, and
 * loads the shop's list with the same import the shop would use. The account is an ordinary,
 * visible user: every product and opening stock it records carries its name, it shows on the
 * shop's Users page, and the owner can switch it off there. Its role
 * ({@value #ROLE}) covers products, stock and suppliers, nothing else (V42).
 *
 * <p>Only for a shop that asked: one with a setup request linked to it. And never against the
 * owner's wishes: once they have switched the account off, it stays off.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportAccessService {

    public static final String ROLE = "PROCUREPADDY_SUPPORT";
    public static final String USERNAME = "procurepaddy-support";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantScopeExecutor tenantScopeExecutor;
    private final AuthService authService;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public TenantLoginResponse openSession(UUID clientId, UUID superAdminId) {
        Client client = clientRepository.findById(clientId).orElseThrow(SupportAccessException::notFound);
        if (!client.isActive()) {
            throw SupportAccessException.suspended();
        }
        Integer asked = jdbc.queryForObject(
                "SELECT count(*) FROM setup_requests WHERE client_id = :clientId", Map.of("clientId", clientId), Integer.class);
        if (asked == null || asked == 0) {
            throw SupportAccessException.notAsked();
        }

        User support = userRepository.findByClientIdAndUsername(clientId, USERNAME)
                .map(existing -> {
                    if (!ROLE.equals(existing.getRole().getName())) {
                        throw SupportAccessException.nameTaken();
                    }
                    if (!existing.isActive()) {
                        throw SupportAccessException.switchedOff();
                    }
                    return existing;
                })
                .orElseGet(() -> create(clientId));

        log.info("Super admin {} opened {} as Procurepaddy support", superAdminId, client.getSlug());
        return authService.issueLoginResponse(support, client);
    }

    private User create(UUID clientId) {
        Role role = roleRepository.findFirstByNameAndClientIdIsNull(ROLE)
                .orElseThrow(() -> new IllegalStateException(ROLE + " role not seeded - run the Flyway migrations"));
        // Nobody ever types this password: the account is only entered through openSession.
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return tenantScopeExecutor.callAs(clientId, () -> userRepository.save(User.builder()
                .username(USERNAME)
                .passwordHash(passwordEncoder.encode(Base64.getEncoder().encodeToString(secret)))
                .role(role)
                .active(true)
                .root(false)
                .firstName("Procurepaddy")
                .lastName("support")
                .jobTitle("Loads your products (setup team)")
                .build()));
    }
}
