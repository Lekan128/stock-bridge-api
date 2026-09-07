package com.procurepal_services.stock_bridge_api.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
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
 * Every test here works through a SECOND owner rather than the root user
 * themselves. That's the scenario root protection exists for: the last-active-
 * owner guard is satisfied (there are two owners, so the tenant isn't at risk
 * of losing its last one) and the self-service guard doesn't apply (caller
 * isn't the target), so root protection is the only thing left standing
 * between a co-owner and the account holder's account.
 *
 * Requires `docker compose up -d` at the project root - see AuthIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class RootUserProtectionIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void signupOwnerIsFlaggedAsRootAndSubUsersAreNot() {
        TenantLoginResponse root = signup("Root Flag Co");
        UserSummaryResponse subUser = createUser(root, "second-owner-" + UUID.randomUUID(), "OWNER");

        UserSummaryResponse rootAsSeenByApi = restTemplate
                .exchange(
                        "/api/users/" + root.user().id(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(root)),
                        UserSummaryResponse.class)
                .getBody();

        assertThat(rootAsSeenByApi.root()).isTrue();
        assertThat(subUser.root()).isFalse();
    }

    @Test
    void anotherOwnerCannotChangeTheRootUsersRole() {
        TenantLoginResponse root = signup("Root Role Co");
        HttpHeaders coOwner = authHeaders(secondOwner(root));

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/users/" + root.user().id(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateUserRequest(roleId("PROCUREMENT_MANAGER"), null, null, null, null, null, null), coOwner),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo("The account owner's role cannot be changed.");
    }

    @Test
    void anotherOwnerCannotDeactivateTheRootUserByUpdateOrDelete() {
        TenantLoginResponse root = signup("Root Deactivate Co");
        HttpHeaders coOwner = authHeaders(secondOwner(root));

        ResponseEntity<ApiError> viaUpdate = restTemplate.exchange(
                "/api/users/" + root.user().id(),
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateUserRequest(null, false, null, null, null, null, null), coOwner),
                ApiError.class);
        assertThat(viaUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(viaUpdate.getBody().message()).isEqualTo("The account owner cannot be deactivated or deleted.");

        ResponseEntity<ApiError> viaDelete = restTemplate.exchange(
                "/api/users/" + root.user().id(), HttpMethod.DELETE, new HttpEntity<>(coOwner), ApiError.class);
        assertThat(viaDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(viaDelete.getBody().message()).isEqualTo("The account owner cannot be deactivated or deleted.");

        // Still able to log in - the guard actually held, it didn't just report.
        assertThat(login(root.user().clientIdentifier(), root.user().username(), PASSWORD))
                .isNotNull();
    }

    @Test
    void anotherOwnerCannotResetTheRootUsersPasswordButTheRootUserCan() {
        TenantLoginResponse root = signup("Root Password Co");
        HttpHeaders coOwner = authHeaders(secondOwner(root));
        String attemptedPassword = "taken-over-by-a-co-owner";

        ResponseEntity<ApiError> foreignReset = restTemplate.exchange(
                "/api/users/" + root.user().id() + "/reset-password",
                HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest(attemptedPassword, attemptedPassword), coOwner),
                ApiError.class);
        assertThat(foreignReset.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(foreignReset.getBody().message())
                .isEqualTo("Only the account owner can change the account owner's password.");
        assertThat(loginStatus(root.user().clientIdentifier(), root.user().username(), attemptedPassword))
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // The same call from the account holder is fine - the rule is about who
        // is asking, not about the endpoint being off-limits.
        String ownPassword = "chosen-by-the-account-owner";
        ResponseEntity<Void> selfReset = restTemplate.exchange(
                "/api/users/" + root.user().id() + "/reset-password",
                HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest(ownPassword, ownPassword), authHeaders(root)),
                Void.class);
        assertThat(selfReset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(root.user().clientIdentifier(), root.user().username(), ownPassword))
                .isNotNull();
    }

    /** A co-owner is still fully manageable - root protection is about one account, not the OWNER role. */
    @Test
    void aNonRootOwnerCanStillBeDemotedByAnotherOwner() {
        TenantLoginResponse root = signup("Co Owner Demote Co");
        TenantLoginResponse coOwner = secondOwner(root);

        ResponseEntity<UserSummaryResponse> response = restTemplate.exchange(
                "/api/users/" + coOwner.user().id(),
                HttpMethod.PUT,
                new HttpEntity<>(
                        new UpdateUserRequest(roleId("FINANCE_OFFICER"), null, null, null, null, null, null),
                        authHeaders(root)),
                UserSummaryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().roleName()).isEqualTo("FINANCE_OFFICER");
        assertThat(response.getBody().root()).isFalse();
    }

    private TenantLoginResponse secondOwner(TenantLoginResponse root) {
        String username = "co-owner-" + UUID.randomUUID();
        createUser(root, username, "OWNER");
        return login(root.user().clientIdentifier(), username, PASSWORD);
    }

    private UserSummaryResponse createUser(TenantLoginResponse asOwner, String username, String role) {
        return restTemplate
                .exchange(
                        "/api/users",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateUserRequest(username, PASSWORD, roleId(role), null, null, null, null, null),
                                authHeaders(asOwner)),
                        UserSummaryResponse.class)
                .getBody();
    }

    private UUID roleId(String name) {
        return roleRepository.findByName(name).orElseThrow().getId();
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
