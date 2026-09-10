package com.procurepal_services.stock_bridge_api.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ChangePasswordRequest;
import com.procurepal_services.stock_bridge_api.profile.dto.ProfileResponse;
import com.procurepal_services.stock_bridge_api.profile.dto.UpdateProfileRequest;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers. Requires `docker compose up -d` at the project root.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProfileIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void meReturnsIdentityPermissionsAndTenantForTheRootOwner() {
        TenantLoginResponse owner = signup("Me Owner Co");

        ResponseEntity<ProfileResponse> response = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), ProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProfileResponse me = response.getBody();
        assertThat(me).isNotNull();
        assertThat(me.id()).isEqualTo(owner.user().id());
        assertThat(me.username()).isEqualTo(owner.user().username());
        assertThat(me.role()).isEqualTo("OWNER");
        assertThat(me.root()).isTrue();
        assertThat(me.active()).isTrue();
        assertThat(me.createdAt()).isNotNull();
        assertThat(me.clientName()).isEqualTo(owner.user().clientName());
        assertThat(me.clientIdentifier()).isEqualTo(owner.user().clientIdentifier());
        // Must agree with what the token was minted with, or the UI gates on a
        // different answer than the API enforces.
        assertThat(me.permissions()).containsExactlyElementsOf(owner.user().permissions());
        assertThat(me.permissions())
                .containsExactly(
                        "BROWSE_MARKETPLACE",
                        "MANAGE_COMPANY_PROFILE",
                        "MANAGE_DELIVERY_ADDRESSES",
                        "MANAGE_INVENTORY",
                        "MANAGE_MARKETPLACE",
                        "MANAGE_MARKETPLACE_ORDERS",
                        "MANAGE_PRODUCTS",
                        "MANAGE_ROLES",
                        "MANAGE_USERS",
                        "MANAGE_VENDORS",
                        "PLACE_ORDERS",
                        "RECEIVE_DELIVERIES",
                        "STOCK_IN",
                        "STOCK_OUT",
                        "VIEW_ALL_BRANCHES",
                        "VIEW_ANALYTICS",
                        "VIEW_MARKETPLACE_ANALYTICS",
                        "VIEW_ORDERS",
                        "VIEW_PRODUCTS",
                        "VIEW_VENDORS");
        // Same value the login response and the access token carry - the three must
        // never disagree, or the UI shows marketplace admin to someone the API will
        // refuse.
        assertThat(me.platformOwner()).isEqualTo(owner.user().platformOwner()).isFalse();
    }

    /** /api/me carries no permission requirement, so the least-privileged role must reach it. */
    @Test
    void meIsAvailableToASubUserWithNoAdminPermissions() {
        TenantLoginResponse owner = signup("Me Sub User Co");
        createUser(owner, "floor-hand", "STOREKEEPER");
        TenantLoginResponse storekeeper = login(owner.user().clientIdentifier(), "floor-hand", PASSWORD);

        ResponseEntity<ProfileResponse> response = restTemplate.exchange(
                "/api/me", HttpMethod.GET, new HttpEntity<>(authHeaders(storekeeper)), ProfileResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().role()).isEqualTo("STOREKEEPER");
        assertThat(response.getBody().root()).isFalse();
        assertThat(response.getBody().permissions())
                .containsExactly(
                        "BROWSE_MARKETPLACE",
                        "MANAGE_INVENTORY",
                        "RECEIVE_DELIVERIES",
                        "STOCK_IN",
                        "STOCK_OUT",
                        "VIEW_PRODUCTS");
    }

    @Test
    void updatingOwnProfilePersistsAndLeavesRoleAndRootUntouched() {
        TenantLoginResponse owner = signup("Me Update Co");
        HttpHeaders auth = authHeaders(owner);

        ResponseEntity<ProfileResponse> updateResponse = restTemplate.exchange(
                "/api/me",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateProfileRequest(
                                "Ada", "Okafor", "ada@example.com", "+2348011112222", "Managing Director"),
                        auth),
                ProfileResponse.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        ProfileResponse updated = updateResponse.getBody();
        assertThat(updated.firstName()).isEqualTo("Ada");
        assertThat(updated.lastName()).isEqualTo("Okafor");
        assertThat(updated.email()).isEqualTo("ada@example.com");
        assertThat(updated.phone()).isEqualTo("+2348011112222");
        assertThat(updated.jobTitle()).isEqualTo("Managing Director");
        assertThat(updated.role()).isEqualTo("OWNER");
        assertThat(updated.root()).isTrue();

        ProfileResponse reloaded = restTemplate
                .exchange("/api/me", HttpMethod.GET, new HttpEntity<>(auth), ProfileResponse.class)
                .getBody();
        assertThat(reloaded.firstName()).isEqualTo("Ada");
        assertThat(reloaded.jobTitle()).isEqualTo("Managing Director");
    }

    @Test
    void updatingOwnProfileWithAMalformedEmailIsRejected() {
        TenantLoginResponse owner = signup("Me Bad Email Co");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/me",
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateProfileRequest("Ada", null, "not-an-email", null, null), authHeaders(owner)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void changingOwnPasswordSucceedsAndTheOldPasswordStopsWorking() {
        TenantLoginResponse owner = signup("Me Password Co");
        String newPassword = "a-brand-new-password-1";

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/me/password",
                HttpMethod.POST,
                new HttpEntity<>(new ChangePasswordRequest(PASSWORD, newPassword, newPassword), authHeaders(owner)),
                Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(owner.user().clientIdentifier(), owner.user().username(), newPassword))
                .isNotNull();
        assertThat(loginStatus(owner.user().clientIdentifier(), owner.user().username(), PASSWORD))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void changingOwnPasswordWithTheWrongCurrentPasswordIsRejected() {
        TenantLoginResponse owner = signup("Me Wrong Current Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/me/password",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ChangePasswordRequest("not-my-password", "a-new-password-1", "a-new-password-1"),
                        authHeaders(owner)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Your current password is incorrect.");
        // The password must be unchanged - a failed attempt can't lock anyone out.
        assertThat(login(owner.user().clientIdentifier(), owner.user().username(), PASSWORD))
                .isNotNull();
    }

    @Test
    void changingOwnPasswordWithAMismatchedConfirmationIsRejected() {
        TenantLoginResponse owner = signup("Me Mismatch Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/me/password",
                HttpMethod.POST,
                new HttpEntity<>(
                        new ChangePasswordRequest(PASSWORD, "a-new-password-1", "a-different-password-2"),
                        authHeaders(owner)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("do not match");
        assertThat(login(owner.user().clientIdentifier(), owner.user().username(), PASSWORD))
                .isNotNull();
    }

    @Test
    void changingOwnPasswordToAShortOneIsRejected() {
        TenantLoginResponse owner = signup("Me Short Password Co");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/me/password",
                HttpMethod.POST,
                new HttpEntity<>(new ChangePasswordRequest(PASSWORD, "short", "short"), authHeaders(owner)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * 403, not 401 - no AuthenticationEntryPoint is configured, so an anonymous
     * request is an access-denied one app-wide (see the cross-audience cases in
     * AuthIntegrationTest, which assert the same). The point of this test is
     * that /api/me is not accidentally public just because it carries no
     * @PreAuthorize.
     */
    @Test
    void meIsNotReachableWithoutAToken() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void createUser(TenantLoginResponse asOwner, String username, String role) {
        restTemplate.exchange(
                "/api/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(
                                username,
                                PASSWORD,
                                roleRepository.findByName(role).orElseThrow().getId(),
                                null,
                                null,
                                null,
                                null,
                                null),
                        authHeaders(asOwner)),
                UserSummaryResponse.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private TenantLoginResponse login(String clientIdentifier, String username, String password) {
        return restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(clientIdentifier, username, password), TenantLoginResponse.class);
    }

    private HttpStatus loginStatus(String clientIdentifier, String username, String password) {
        return (HttpStatus) restTemplate
                .postForEntity("/api/auth/login", new LoginRequest(clientIdentifier, username, password), String.class)
                .getStatusCode();
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
