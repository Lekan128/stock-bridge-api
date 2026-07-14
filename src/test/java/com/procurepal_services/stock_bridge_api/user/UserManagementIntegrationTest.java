package com.procurepal_services.stock_bridge_api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers. Requires `docker compose up -d` at the project root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class UserManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserManagementService userManagementService;

    @Test
    void adminCanCreateUser() {
        TenantLoginResponse admin = signup("Create Co");

        ResponseEntity<UserSummaryResponse> response = restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest("bob", PASSWORD, "STAFF"), authHeaders(admin)),
                UserSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserSummaryResponse created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.username()).isEqualTo("bob");
        assertThat(created.role()).isEqualTo("STAFF");
        assertThat(created.active()).isTrue();
    }

    @Test
    void listIsScopedToCallersTenantOnly() {
        TenantLoginResponse tenantA = signup("Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Tenant B " + UUID.randomUUID());
        createUser(tenantA, "a-staff");
        createUser(tenantB, "b-staff");

        ResponseEntity<TestPage<UserSummaryResponse>> response = restTemplate.exchange(
                "/api/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantA)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> usernames = response.getBody().content().stream().map(UserSummaryResponse::username).toList();
        assertThat(usernames).contains(tenantA.user().username(), "a-staff");
        assertThat(usernames).doesNotContain(tenantB.user().username(), "b-staff");
    }

    @Test
    void staffTokenGets403OnEveryUserManagementEndpoint() {
        TenantLoginResponse admin = signup("Staff Perms Co");
        createUser(admin, "staffer");
        TenantLoginResponse staff = login(admin.user().clientIdentifier(), "staffer");
        HttpHeaders staffAuth = authHeaders(staff);
        UUID someUserId = admin.user().id();

        assertForbidden("/api/users", HttpMethod.GET, staffAuth, null);
        assertForbidden("/api/users/" + someUserId, HttpMethod.GET, staffAuth, null);
        assertForbidden("/api/users", HttpMethod.POST, staffAuth, new CreateUserRequest("x", PASSWORD, "STAFF"));
        assertForbidden("/api/users/" + someUserId, HttpMethod.PUT, staffAuth, new UpdateUserRequest("MANAGER", null));
        assertForbidden(
                "/api/users/" + someUserId + "/reset-password",
                HttpMethod.POST,
                staffAuth,
                new ResetPasswordRequest("new-password-123", "new-password-123"));
        assertForbidden("/api/users/" + someUserId, HttpMethod.DELETE, staffAuth, null);
    }

    @Test
    void adminCannotChangeOwnRoleOrDeactivateSelf() {
        TenantLoginResponse admin = signup("Self Lockout Co");
        HttpHeaders auth = authHeaders(admin);
        UUID ownId = admin.user().id();

        ResponseEntity<ApiError> roleChangeResponse = restTemplate.exchange(
                "/api/users/" + ownId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateUserRequest("MANAGER", null), auth),
                ApiError.class);
        assertThat(roleChangeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(roleChangeResponse.getBody().message()).contains("own role");

        ResponseEntity<ApiError> deactivateResponse = restTemplate.exchange(
                "/api/users/" + ownId, HttpMethod.DELETE, new HttpEntity<>(auth), ApiError.class);
        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Calls UserManagementService directly rather than through HTTP. With the
     * current fixed roles, MANAGE_USERS is ADMIN-only, so the only way to reach
     * "caller != target, target is the last active admin" over HTTP is a stale
     * (but still unexpired) JWT from an admin who was demoted/deactivated after
     * issuance - a real scenario given the stateless access-token design (see
     * AuthenticatedUserPrincipal), but awkward to construct reliably in a test.
     * Calling the service directly tests the rule itself without that plumbing.
     */
    @Test
    void lastActiveAdminCannotBeRemovedByAnotherCaller() {
        Role adminRole = roleRepository.findByName("ADMIN").orElseThrow();
        Client client = clientRepository.save(Client.builder()
                .name("Last Admin Co")
                .slug("last-admin-co-" + UUID.randomUUID())
                .adminContactEmail("owner@example.com")
                .active(true)
                .build());

        TenantContext.set(client.getId());
        try {
            User adminA = userRepository.save(User.builder()
                    .username("admin-a")
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(adminRole)
                    .active(true)
                    .build());
            User adminB = userRepository.save(User.builder()
                    .username("admin-b")
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(adminRole)
                    .active(true)
                    .build());

            // Two active admins: A deactivating B is fine, A remains.
            userManagementService.deactivate(adminB.getId(), adminA.getId());

            // Now only A is an active admin. A different caller (B, even though B
            // is no longer an active admin themselves - see method doc) attempts
            // to deactivate A, the last one - must be blocked.
            assertThatThrownBy(() -> userManagementService.deactivate(adminA.getId(), adminB.getId()))
                    .isInstanceOf(LastActiveAdminException.class);
        } finally {
            TenantContext.clear();
        }
    }

    private void assertForbidden(String path, HttpMethod method, HttpHeaders auth, Object body) {
        ResponseEntity<String> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, auth), String.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private TenantLoginResponse login(String clientIdentifier, String username) {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(clientIdentifier, username, PASSWORD), TenantLoginResponse.class);
    }

    private void createUser(TenantLoginResponse asAdmin, String username) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest(username, PASSWORD, "STAFF"), authHeaders(asAdmin)),
                UserSummaryResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }

    private record TestPage<T>(List<T> content) {
    }
}
