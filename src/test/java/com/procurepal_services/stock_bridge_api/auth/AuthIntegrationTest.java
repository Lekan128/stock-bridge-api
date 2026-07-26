package com.procurepal_services.stock_bridge_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.LogoutRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real HTTP + Spring Security filter chain (not repositories
 * directly) against the local docker-compose Postgres - see
 * TenantIsolationIntegrationTest for why local Postgres over Testcontainers.
 * Requires `docker compose up -d` to be running at the project root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class AuthIntegrationTest {

    private static final String RAW_PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Client client;

    @BeforeEach
    void setUp() {
        Role storekeeperRole = roleRepository.findByName("STOREKEEPER")
                .orElseThrow(
                        () -> new IllegalStateException("STOREKEEPER role not seeded - run the Flyway migrations"));

        String unique = UUID.randomUUID().toString();
        client = clientRepository.save(Client.builder()
                .name("Acme " + unique)
                .slug("acme-" + unique)
                .adminContactEmail("owner-" + unique + "@example.com")
                .active(true)
                .build());

        TenantContext.set(client.getId());
        try {
            userRepository.save(User.builder()
                    .username("alice")
                    .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                    .role(storekeeperRole)
                    .active(true)
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void loginWithCorrectCredentialsSucceedsAndReturnsUserInfo() {
        LoginRequest request = new LoginRequest(client.getSlug(), "alice", RAW_PASSWORD);

        ResponseEntity<TenantLoginResponse> response =
                restTemplate.postForEntity("/api/auth/login", request, TenantLoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantLoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.tokens().accessToken()).isNotBlank();
        assertThat(body.tokens().refreshToken()).isNotBlank();
        assertThat(body.user().username()).isEqualTo("alice");
        assertThat(body.user().role()).isEqualTo("STOREKEEPER");
        assertThat(body.user().permissions()).containsExactly("MANAGE_INVENTORY", "VIEW_PRODUCTS");
        assertThat(body.user().clientName()).isEqualTo(client.getName());
    }

    @Test
    void loginWithWrongPasswordFailsWithGenericMessage() {
        LoginRequest request = new LoginRequest(client.getSlug(), "alice", "wrong-password");

        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/auth/login", request, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Invalid credentials");
    }

    @Test
    void loginWithUnknownClientFailsWithSameGenericMessageAsWrongPassword() {
        LoginRequest request = new LoginRequest("no-such-client-" + UUID.randomUUID(), "alice", RAW_PASSWORD);

        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/auth/login", request, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Invalid credentials");
    }

    @Test
    void loginAgainstASuspendedClientFailsWithAClearSuspensionMessageNotGenericInvalidCredentials() {
        client.setActive(false);
        clientRepository.save(client);

        LoginRequest request = new LoginRequest(client.getSlug(), "alice", RAW_PASSWORD);
        ResponseEntity<ApiError> response = restTemplate.postForEntity("/api/auth/login", request, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("This account has been suspended. Please contact support.");
    }

    @Test
    void tenantTokenIsRejectedOnSuperAdminRoute() {
        String tenantAccessToken = login().tokens().accessToken();

        ResponseEntity<String> response = postWithBearerToken(
                "/api/superadmin/auth/logout", tenantAccessToken, new LogoutRequest("irrelevant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdminTokenIsRejectedOnTenantRoute() {
        String uniqueUsername = "admin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(RAW_PASSWORD))
                .build());

        ResponseEntity<SuperAdminLoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, RAW_PASSWORD),
                SuperAdminLoginResponse.class);
        String superAdminAccessToken = loginResponse.getBody().tokens().accessToken();

        ResponseEntity<String> response = postWithBearerToken(
                "/api/auth/logout", superAdminAccessToken, new LogoutRequest("irrelevant"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private TenantLoginResponse login() {
        LoginRequest request = new LoginRequest(client.getSlug(), "alice", RAW_PASSWORD);
        return restTemplate.postForObject("/api/auth/login", request, TenantLoginResponse.class);
    }

    private ResponseEntity<String> postWithBearerToken(String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }
}
