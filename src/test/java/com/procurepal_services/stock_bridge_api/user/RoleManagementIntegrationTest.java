package com.procurepal_services.stock_bridge_api.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.PermissionResponse;
import com.procurepal_services.stock_bridge_api.user.dto.RoleResponse;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateRoleRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * The Roles & Privileges screen's backend: custom-role CRUD scoped to the caller's own tenant,
 * the STOCK_IN/STOCK_OUT split (V27), and that a custom role actually gates what it claims to.
 *
 * Requires `docker compose up -d` at the project root - see AuthIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class RoleManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void ownerCanCreateACustomRoleWithOnlyStockIn() {
        TenantLoginResponse owner = signup("Custom Role Co");
        HttpHeaders auth = authHeaders(owner);

        ResponseEntity<RoleResponse> created = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Receiving Clerk", "Only receives stock", Set.of("STOCK_IN")), auth),
                RoleResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        RoleResponse role = created.getBody();
        assertThat(role.name()).isEqualTo("Receiving Clerk");
        assertThat(role.isSystem()).isFalse();
        assertThat(role.permissions()).containsExactly("STOCK_IN");

        TenantLoginResponse clerk = createUserWithRoleId(owner, "clerk", role.id());

        assertStatus(
                HttpStatus.NOT_FOUND,
                "/api/products/" + UUID.randomUUID() + "/stock/stock-in",
                HttpMethod.POST,
                clerk,
                new StockInRequest(1, new BigDecimal("1.00"), null));
        assertStatus(
                HttpStatus.FORBIDDEN,
                "/api/products/" + UUID.randomUUID() + "/stock/stock-out",
                HttpMethod.POST,
                clerk,
                new StockOutRequest(1, null, null));
    }

    @Test
    void systemRolesAreVisibleButNotEditableOrDeletable() {
        TenantLoginResponse owner = signup("System Role Lock Co");
        HttpHeaders auth = authHeaders(owner);
        UUID storekeeperId = roleRepository.findByName("STOREKEEPER").orElseThrow().getId();

        ResponseEntity<ApiError> updateAttempt = restTemplate.exchange(
                "/api/roles/" + storekeeperId,
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateRoleRequest("STOREKEEPER", "hijacked", Set.of("MANAGE_USERS")), auth),
                ApiError.class);
        assertThat(updateAttempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<ApiError> deleteAttempt =
                restTemplate.exchange("/api/roles/" + storekeeperId, HttpMethod.DELETE, new HttpEntity<>(auth), ApiError.class);
        assertThat(deleteAttempt.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<List<RoleResponse>> list = restTemplate.exchange(
                "/api/roles", HttpMethod.GET, new HttpEntity<>(auth), new ParameterizedTypeReference<>() {});
        assertThat(list.getBody())
                .filteredOn(r -> r.name().equals("STOREKEEPER"))
                .singleElement()
                .satisfies(r -> assertThat(r.isSystem()).isTrue());
    }

    @Test
    void customRoleNamesAreUniquePerTenantNotGlobally() {
        TenantLoginResponse tenantA = signup("Duplicate Name Co A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Duplicate Name Co B " + UUID.randomUUID());

        ResponseEntity<RoleResponse> first = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Warehouse Clerk", null, Set.of("STOCK_IN")), authHeaders(tenantA)),
                RoleResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same name, different tenant: allowed.
        ResponseEntity<RoleResponse> sameNameOtherTenant = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Warehouse Clerk", null, Set.of("STOCK_OUT")), authHeaders(tenantB)),
                RoleResponse.class);
        assertThat(sameNameOtherTenant.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same name, same tenant: rejected.
        ResponseEntity<ApiError> duplicate = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Warehouse Clerk", null, Set.of("STOCK_OUT")), authHeaders(tenantA)),
                ApiError.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void aRoleStillAssignedToAUserCannotBeDeleted() {
        TenantLoginResponse owner = signup("Role In Use Co");
        HttpHeaders auth = authHeaders(owner);

        RoleResponse role = restTemplate
                .exchange(
                        "/api/roles",
                        HttpMethod.POST,
                        new HttpEntity<>(new CreateRoleRequest("Floor Staff", null, Set.of("STOCK_IN", "STOCK_OUT")), auth),
                        RoleResponse.class)
                .getBody();
        createUserWithRoleId(owner, "floor-staff", role.id());

        ResponseEntity<ApiError> deleteAttempt =
                restTemplate.exchange("/api/roles/" + role.id(), HttpMethod.DELETE, new HttpEntity<>(auth), ApiError.class);
        assertThat(deleteAttempt.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<RoleResponse> unusedRole = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Unused Role", null, Set.of()), auth),
                RoleResponse.class);
        ResponseEntity<Void> deleteUnused = restTemplate.exchange(
                "/api/roles/" + unusedRole.getBody().id(), HttpMethod.DELETE, new HttpEntity<>(auth), Void.class);
        assertThat(deleteUnused.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void anUnknownPermissionCodeIsRejected() {
        TenantLoginResponse owner = signup("Bad Permission Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Bogus Role", null, Set.of("DELETE_THE_DATABASE")), authHeaders(owner)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyManageRolesHoldersCanReachTheRoleAndPermissionEndpoints() {
        TenantLoginResponse owner = signup("Locked Out Co");
        TenantLoginResponse storekeeper = login(owner, createUserWithRole(owner, "no-perms", "STOREKEEPER"));
        HttpHeaders staffAuth = authHeaders(storekeeper);

        assertStatus(HttpStatus.FORBIDDEN, "/api/permissions", HttpMethod.GET, storekeeper, null);
        ResponseEntity<String> createAttempt = restTemplate.exchange(
                "/api/roles",
                HttpMethod.POST,
                new HttpEntity<>(new CreateRoleRequest("Sneaky Role", null, Set.of()), staffAuth),
                String.class);
        assertThat(createAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void permissionCatalogueIncludesStockInAndStockOut() {
        TenantLoginResponse owner = signup("Permission Catalogue Co");

        ResponseEntity<List<PermissionResponse>> response = restTemplate.exchange(
                "/api/permissions", HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(PermissionResponse::code).contains("STOCK_IN", "STOCK_OUT");
    }

    private TenantLoginResponse createUserWithRoleId(TenantLoginResponse owner, String username, UUID roleId) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, roleId, null, null, null, null, null), authHeaders(owner)),
                UserSummaryResponse.class);
        return login(owner, username);
    }

    private String createUserWithRole(TenantLoginResponse owner, String username, String roleName) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                username,
                                PASSWORD,
                                roleRepository.findByName(roleName).orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        authHeaders(owner)),
                UserSummaryResponse.class);
        return username;
    }

    private TenantLoginResponse login(TenantLoginResponse owner, String username) {
        return restTemplate.postForObject(
                "/api/auth/login",
                new LoginRequest(owner.user().clientIdentifier(), username, PASSWORD),
                TenantLoginResponse.class);
    }

    private void assertStatus(HttpStatus expected, String path, HttpMethod method, TenantLoginResponse as, Object body) {
        ResponseEntity<String> response =
                restTemplate.exchange(path, method, new HttpEntity<>(body, authHeaders(as)), String.class);
        assertThat(response.getStatusCode()).as("%s %s", method, path).isEqualTo(expected);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
