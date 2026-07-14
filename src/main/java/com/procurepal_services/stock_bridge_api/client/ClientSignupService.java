package com.procurepal_services.stock_bridge_api.client;

import com.procurepal_services.stock_bridge_api.auth.AuthService;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service tenant signup: creates a client and its first (admin) user in
 * one transaction, then logs that user in immediately. The client-identifier
 * uniqueness check is pre-checked (clean 409 for the common case) and also
 * backstopped by the DB's unique constraint via ClientSignupExceptionHandler
 * for the rare concurrent-signup race - see that class for why it isn't
 * caught inline here (Postgres poisons the transaction on a constraint
 * violation, so catching and continuing in the same transaction isn't safe;
 * letting it propagate lets Spring roll back cleanly before translation).
 */
@Service
@RequiredArgsConstructor
public class ClientSignupService {

    private final ClientRepository clientRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    @Transactional
    public TenantLoginResponse signup(ClientSignupRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new PasswordMismatchException();
        }

        String slug = resolveSlug(request);
        if (clientRepository.findBySlug(slug).isPresent()) {
            throw new ClientIdentifierTakenException(slug);
        }

        Role adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("ADMIN role not seeded - run the Flyway migrations"));

        Client client = clientRepository.save(Client.builder()
                .name(request.name())
                .slug(slug)
                .adminContactEmail(request.adminEmail())
                .active(true)
                .build());

        // No tenant context exists yet for a brand-new client (there's no
        // authenticated principal on this public endpoint) - set it explicitly
        // for this one privileged, server-side write, same pattern as any other
        // system-initiated first-user creation. See User's class doc.
        TenantContext.set(client.getId());
        User adminUser;
        try {
            adminUser = userRepository.save(User.builder()
                    .username(request.adminEmail())
                    .passwordHash(passwordEncoder.encode(request.password()))
                    .role(adminRole)
                    .active(true)
                    .build());
        } finally {
            TenantContext.clear();
        }

        return authService.issueLoginResponse(adminUser, client);
    }

    private String resolveSlug(ClientSignupRequest request) {
        String source = (request.clientIdentifier() != null && !request.clientIdentifier().isBlank())
                ? request.clientIdentifier()
                : request.name();
        return slugify(source);
    }

    private String slugify(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "client-" + UUID.randomUUID() : normalized;
    }
}
