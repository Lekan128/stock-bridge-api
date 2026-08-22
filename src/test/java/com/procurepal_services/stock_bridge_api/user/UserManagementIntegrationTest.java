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
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
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
                new HttpEntity<>(new CreateUserRequest("bob", PASSWORD, "STOREKEEPER", "Bob", "Barker", "bob@example.com", "+2348012345678", "Storekeeper"), authHeaders(admin)),
                UserSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UserSummaryResponse created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.username()).isEqualTo("bob");
        assertThat(created.role()).isEqualTo("STOREKEEPER");
        assertThat(created.firstName()).isEqualTo("Bob");
        assertThat(created.lastName()).isEqualTo("Barker");
        assertThat(created.email()).isEqualTo("bob@example.com");
        assertThat(created.phone()).isEqualTo("+2348012345678");
        assertThat(created.jobTitle()).isEqualTo("Storekeeper");
        assertThat(created.root()).isFalse();
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
    void storekeeperTokenGets403OnEveryUserManagementEndpoint() {
        TenantLoginResponse admin = signup("Storekeeper Perms Co");
        createUser(admin, "storekeeper-user");
        TenantLoginResponse staff = login(admin.user().clientIdentifier(), "storekeeper-user");
        HttpHeaders staffAuth = authHeaders(staff);
        UUID someUserId = admin.user().id();

        assertForbidden("/api/users", HttpMethod.GET, staffAuth, null);
        assertForbidden("/api/users/" + someUserId, HttpMethod.GET, staffAuth, null);
        assertForbidden("/api/roles", HttpMethod.GET, staffAuth, null);
        assertForbidden(
                "/api/users",
                HttpMethod.POST,
                staffAuth,
                new CreateUserRequest("x", PASSWORD, "STOREKEEPER", null, null, null, null, null));
        assertForbidden(
                "/api/users/" + someUserId,
                HttpMethod.PUT,
                staffAuth,
                new UpdateUserRequest("PROCUREMENT_MANAGER", null, null, null, null, null, null));
        assertForbidden(
                "/api/users/" + someUserId + "/reset-password",
                HttpMethod.POST,
                staffAuth,
                new ResetPasswordRequest("new-password-123", "new-password-123"));
        assertForbidden("/api/users/" + someUserId, HttpMethod.DELETE, staffAuth, null);
    }

    @Test
    void ownerCannotChangeOwnRoleOrDeactivateSelf() {
        TenantLoginResponse admin = signup("Self Lockout Co");
        HttpHeaders auth = authHeaders(admin);
        UUID ownId = admin.user().id();

        ResponseEntity<ApiError> roleChangeResponse = restTemplate.exchange(
                "/api/users/" + ownId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateUserRequest("PROCUREMENT_MANAGER", null, null, null, null, null, null), auth),
                ApiError.class);
        assertThat(roleChangeResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(roleChangeResponse.getBody().message()).contains("own role");

        ResponseEntity<ApiError> deactivateResponse = restTemplate.exchange(
                "/api/users/" + ownId, HttpMethod.DELETE, new HttpEntity<>(auth), ApiError.class);
        assertThat(deactivateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Calls UserManagementService directly rather than through HTTP. MANAGE_USERS
     * is OWNER-only, so the only way to reach "caller != target, target is the
     * last active owner" over HTTP is a stale (but still unexpired) JWT from an
     * owner who was demoted/deactivated after issuance - a real scenario given
     * the stateless access-token design (see AuthenticatedUserPrincipal), but
     * awkward to construct reliably in a test. Calling the service directly
     * tests the rule itself without that plumbing. Neither user here is root, so
     * this exercises the headcount guard in isolation from root protection.
     */
    @Test
    void lastActiveOwnerCannotBeRemovedByAnotherCaller() {
        Role adminRole = roleRepository.findByName("OWNER").orElseThrow();
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

            // Two active owners: A deactivating B is fine, A remains.
            userManagementService.deactivate(adminB.getId(), adminA.getId());

            // Now only A is an active owner. A different caller (B, even though B
            // is no longer an active owner themselves - see method doc) attempts
            // to deactivate A, the last one - must be blocked.
            assertThatThrownBy(() -> userManagementService.deactivate(adminA.getId(), adminB.getId()))
                    .isInstanceOf(LastActiveOwnerException.class);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * The partial-update contract that makes it safe for the users table to
     * offer an inline "deactivate" toggle: touching one field must not blank
     * out the fields it didn't mention.
     */
    @Test
    void adminUpdateOfSubUserPatchesOnlyTheFieldsProvided() {
        TenantLoginResponse admin = signup("Sub User Edit Co");
        HttpHeaders auth = authHeaders(admin);

        UserSummaryResponse created = restTemplate.exchange(
                        "/api/users",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateUserRequest(
                                        "sub-" + UUID.randomUUID(),
                                        PASSWORD,
                                        "STOREKEEPER",
                                        "Original",
                                        "Name",
                                        "original@example.com",
                                        "+2340000000000",
                                        "Storekeeper"),
                                auth),
                        UserSummaryResponse.class)
                .getBody();

        UserSummaryResponse afterNameChange = restTemplate.exchange(
                        "/api/users/" + created.id(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                new UpdateUserRequest(null, null, "Renamed", null, null, null, "Inventory Officer"),
                                auth),
                        UserSummaryResponse.class)
                .getBody();
        assertThat(afterNameChange.firstName()).isEqualTo("Renamed");
        assertThat(afterNameChange.jobTitle()).isEqualTo("Inventory Officer");
        assertThat(afterNameChange.lastName()).isEqualTo("Name");
        assertThat(afterNameChange.email()).isEqualTo("original@example.com");
        assertThat(afterNameChange.phone()).isEqualTo("+2340000000000");
        assertThat(afterNameChange.role()).isEqualTo("STOREKEEPER");
        assertThat(afterNameChange.active()).isTrue();

        UserSummaryResponse afterRoleChange = restTemplate.exchange(
                        "/api/users/" + created.id(),
                        HttpMethod.PUT,
                        new HttpEntity<>(
                                new UpdateUserRequest("FINANCE_OFFICER", false, null, null, null, null, null), auth),
                        UserSummaryResponse.class)
                .getBody();
        assertThat(afterRoleChange.role()).isEqualTo("FINANCE_OFFICER");
        assertThat(afterRoleChange.active()).isFalse();
        assertThat(afterRoleChange.firstName()).isEqualTo("Renamed");
        assertThat(afterRoleChange.email()).isEqualTo("original@example.com");
    }

    @Test
    void rolesEndpointServesTheSeededCatalogueWithItsPermissions() {
        TenantLoginResponse admin = signup("Role Catalogue Co");

        ResponseEntity<List<RoleResponse>> response = restTemplate.exchange(
                "/api/roles", HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RoleResponse> roles = response.getBody();
        // Exactly five, and VENDOR is deliberately not among them even though V11
        // seeds it into the roles table: this endpoint serves ASSIGNABLE roles
        // (TenantRoles.ALL), and a vendor's single account is provisioned by a
        // super admin, never picked from here. Offering an option that the very
        // next request rejects with a 400 would be worse than not offering it.
        assertThat(roles).extracting(RoleResponse::name)
                .containsExactly(
                        "FINANCE_OFFICER", "INVENTORY_OFFICER", "OWNER", "PROCUREMENT_MANAGER", "STOREKEEPER");
        assertThat(roles).allSatisfy(role -> assertThat(role.description()).isNotBlank());

        RoleResponse finance = roles.stream().filter(r -> r.name().equals("FINANCE_OFFICER")).findFirst().orElseThrow();
        assertThat(finance.permissions())
                .containsExactly(
                        "BROWSE_MARKETPLACE", "VIEW_ANALYTICS", "VIEW_ORDERS", "VIEW_PRODUCTS", "VIEW_VENDORS");
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
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, "STOREKEEPER", null, null, null, null, null),
                        authHeaders(asAdmin)),
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
