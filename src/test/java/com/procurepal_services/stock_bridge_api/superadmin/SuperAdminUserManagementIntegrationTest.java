package com.procurepal_services.stock_bridge_api.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UpdateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
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
 * The super-admin user surface: reading any tenant's users, and managing
 * ProcurePal's.
 *
 * Runs against the local docker-compose Postgres over the real HTTP + Spring
 * Security filter chain, like every other integration test here - see
 * AuthIntegrationTest for why local Postgres over Testcontainers. Requires
 * `docker compose up -d` at the project root.
 *
 * <h2>Why several tests move the platform-owner flag, and why that is safe</h2>
 * {@code uq_clients_single_platform_owner} means there is exactly one platform
 * owner per database, and on a local database that is the {@code procurepal} row
 * the demo seed creates - already populated with a root OWNER. Two of the
 * behaviours worth testing are only observable on a platform owner that does NOT
 * look like that: "the first user created becomes root" needs a platform owner
 * with zero users, and the endpoints' behaviour with no platform owner at all
 * needs, unsurprisingly, no platform owner at all. Neither can be reached by
 * adding a row, because the index forbids a second one.
 *
 * {@link #withTemporaryPlatformOwner} therefore borrows the flag: it demotes the
 * real platform owner, promotes a throwaway client, runs the test body, and puts
 * everything back in a finally - including deleting the throwaway and its users,
 * so a later run does not accumulate them. The alternative, mutating the seeded
 * procurepal tenant in place, would be far worse: MarketplaceFoundationIntegration
 * Test logs in as {@code procurepal/admin} with the seeded password, so a test
 * that reset that password or deactivated that user would break a sibling suite
 * in a way that looks like a bug in the sibling. Nothing here writes to the real
 * procurepal row except the flag it restores.
 *
 * These tests are order-independent but NOT parallel-safe, which matches the
 * suite: there is no junit-platform.properties enabling parallel execution, and
 * every other integration test here already shares one database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SuperAdminUserManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------------
    // (1) Viewing any tenant's users
    // ------------------------------------------------------------------------

    @Test
    void superAdminListsAnotherTenantsUsersIncludingItsRootAccountHolder() {
        String token = superAdminToken();
        TenantLoginResponse tenant = signup("Cross Tenant View Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();
        createTenantUser(tenant, "warehouse-lead", "INVENTORY_OFFICER");

        ResponseEntity<PageResponse<UserSummaryResponse>> response = restTemplate.exchange(
                "/api/superadmin/clients/" + client.getId() + "/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UserSummaryResponse> users = response.getBody().content();
        assertThat(users).hasSize(2);
        assertThat(users).extracting(UserSummaryResponse::username)
                .containsExactlyInAnyOrder(tenant.user().username(), "warehouse-lead");

        UserSummaryResponse accountHolder = users.stream()
                .filter(user -> user.username().equals(tenant.user().username()))
                .findFirst()
                .orElseThrow();
        assertThat(accountHolder.root()).isTrue();
        assertThat(accountHolder.role()).isEqualTo("OWNER");
        assertThat(accountHolder.active()).isTrue();

        // The single-user read agrees with the listing, and is reachable by the
        // (clientId, userId) pair rather than by user id alone.
        UserSummaryResponse fetched = restTemplate
                .exchange(
                        "/api/superadmin/clients/" + client.getId() + "/users/" + accountHolder.id(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(token)),
                        UserSummaryResponse.class)
                .getBody();
        assertThat(fetched.id()).isEqualTo(accountHolder.id());
        assertThat(fetched.username()).isEqualTo(accountHolder.username());
    }

    /**
     * The one assertion this whole module most needs to be true. It is made against
     * the RAW response body, not against a deserialized UserSummaryResponse: binding
     * to a record that has no passwordHash component would discard the field even if
     * the server had sent it, so a typed assertion here would pass no matter what.
     * The bcrypt prefix is checked as well as the key name, in case a future rename
     * ships the same value under a friendlier label.
     */
    @Test
    void noResponseOnTheSuperAdminUserSurfaceEverCarriesAPasswordHash() {
        String token = superAdminToken();
        TenantLoginResponse tenant = signup("No Hash Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();
        UserSummaryResponse created = createTenantUser(tenant, "hash-probe", "FINANCE_OFFICER");
        String storedHash = userRepository.findById(created.id()).orElseThrow().getPasswordHash();
        assertThat(storedHash).startsWith("$2");

        List<String> bodies = List.of(
                rawGet("/api/superadmin/clients/" + client.getId() + "/users", token),
                rawGet("/api/superadmin/clients/" + client.getId() + "/users/" + created.id(), token),
                rawGet("/api/superadmin/platform-owner/users", token));

        for (String body : bodies) {
            assertThat(body).doesNotContain("passwordHash");
            assertThat(body).doesNotContain("password_hash");
            assertThat(body).doesNotContain(storedHash);
            assertThat(body).doesNotContain("$2a$");
            assertThat(body).doesNotContain("$2b$");
            // ...and the request really did return the users, so the assertions
            // above are not passing against an error body.
            assertThat(body).contains("username");
        }
    }

    /**
     * A client id that does not exist is a 404 naming the client, not an empty page
     * - an empty page reads identically to "this tenant has no users" and would send
     * somebody hunting for a data problem that is really a mistyped id.
     */
    @Test
    void anUnknownClientIsNotFoundRatherThanAnEmptyPage() {
        String token = superAdminToken();
        UUID missing = UUID.randomUUID();

        ResponseEntity<ApiError> list = restTemplate.exchange(
                "/api/superadmin/clients/" + missing + "/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                ApiError.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(list.getBody().message()).isEqualTo("Client not found");

        ResponseEntity<ApiError> get = restTemplate.exchange(
                "/api/superadmin/clients/" + missing + "/users/" + UUID.randomUUID(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                ApiError.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The (clientId, userId) pair is what identifies a user here, so a real user id
     * asked for under the wrong client must read as "not found" rather than being
     * served under somebody else's heading. This is the cross-tenant leak the
     * findByIdAndClientId lookup exists to prevent.
     */
    @Test
    void aUserIsInvisibleUnderAnotherTenantsClientId() {
        String token = superAdminToken();
        TenantLoginResponse first = signup("Pair Lookup One Co");
        TenantLoginResponse second = signup("Pair Lookup Two Co");
        Client secondClient = clientRepository.findBySlug(second.user().clientIdentifier()).orElseThrow();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/superadmin/clients/" + secondClient.getId() + "/users/" + first.user().id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("User not found");
    }

    // ------------------------------------------------------------------------
    // (2) The audience gate
    // ------------------------------------------------------------------------

    /**
     * Every new endpoint, not a representative one: the gate is a path-prefix rule
     * in SecurityConfig, so a path that does not match it (a typo, a new prefix) is
     * silently ungated and looks perfectly normal in the controller. Enumerating
     * them is the only way that regression shows up.
     */
    @Test
    void aTenantTokenIsRefusedOnEveryNewSuperAdminEndpoint() {
        TenantLoginResponse tenant = signup("Wrong Audience Co");
        Client client = clientRepository.findBySlug(tenant.user().clientIdentifier()).orElseThrow();
        HttpHeaders tenantAuth = authHeaders(tenant.tokens().accessToken());
        String userId = tenant.user().id().toString();

        record Probe(HttpMethod method, String path, Object body) {}
        List<Probe> probes = List.of(
                new Probe(HttpMethod.GET, "/api/superadmin/clients/" + client.getId() + "/users", null),
                new Probe(HttpMethod.GET, "/api/superadmin/clients/" + client.getId() + "/users/" + userId, null),
                new Probe(HttpMethod.GET, "/api/superadmin/platform-owner/users", null),
                new Probe(HttpMethod.GET, "/api/superadmin/platform-owner/users/" + userId, null),
                new Probe(
                        HttpMethod.POST,
                        "/api/superadmin/platform-owner/users",
                        new CreateUserRequest("intruder", PASSWORD, "OWNER", null, null, null, null, null)),
                new Probe(
                        HttpMethod.PUT,
                        "/api/superadmin/platform-owner/users/" + userId,
                        new UpdateUserRequest("OWNER", null, null, null, null, null, null)),
                new Probe(
                        HttpMethod.POST,
                        "/api/superadmin/platform-owner/users/" + userId + "/reset-password",
                        new ResetPasswordRequest(PASSWORD, PASSWORD)),
                new Probe(HttpMethod.DELETE, "/api/superadmin/platform-owner/users/" + userId, null));

        for (Probe probe : probes) {
            ResponseEntity<String> response = restTemplate.exchange(
                    probe.path(), probe.method(), new HttpEntity<>(probe.body(), tenantAuth), String.class);
            assertThat(response.getStatusCode())
                    .as("%s %s", probe.method(), probe.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ------------------------------------------------------------------------
    // (3) Managing the platform owner's users
    // ------------------------------------------------------------------------

    /**
     * "The first user created is the root user" - the explicit product requirement,
     * exercised end to end on a platform owner that genuinely has no users. The
     * requested role is STOREKEEPER on purpose: an account holder who could not
     * create the users who would outrank them is not an account holder, so the
     * first user is forced to OWNER and the response says so.
     */
    @Test
    void theFirstPlatformOwnerUserBecomesRootAndOwnerAndTheSecondDoesNot() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            assertThat(userRepository.countByClientId(temporary.getId())).isZero();

            ResponseEntity<UserSummaryResponse> first = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            new CreateUserRequest(
                                    "founder", PASSWORD, "STOREKEEPER", "Ada", "Founder", null, null, null),
                            authHeaders(token)),
                    UserSummaryResponse.class);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getBody().root()).isTrue();
            assertThat(first.getBody().role()).isEqualTo("OWNER");
            assertThat(first.getBody().active()).isTrue();
            assertThat(first.getBody().firstName()).isEqualTo("Ada");
            // The flag is derived from the tenant's state, not from the body, so it
            // really did land on the row rather than only in the response.
            assertThat(userRepository.findById(first.getBody().id()).orElseThrow().isRoot())
                    .isTrue();

            ResponseEntity<UserSummaryResponse> second = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            new CreateUserRequest(
                                    "second-hire", PASSWORD, "STOREKEEPER", null, null, null, null, null),
                            authHeaders(token)),
                    UserSummaryResponse.class);

            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getBody().root()).isFalse();
            // ...and now the requested role IS honoured, which is what makes the
            // override above a rule about the first user rather than about the API.
            assertThat(second.getBody().role()).isEqualTo("STOREKEEPER");
        });
    }

    /**
     * Root protection and the last-active-owner guard, both on the platform owner.
     * They are exercised in one test because the second only becomes reachable once
     * the first has established the shape: with the root OWNER present there is
     * always a second active owner, so the headcount rule can only fire against a
     * NON-root owner - which is exactly the co-owner promoted and then demoted here.
     */
    @Test
    void rootProtectionAndTheLastActiveOwnerGuardHoldForThePlatformOwner() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            UserSummaryResponse root = createPlatformOwnerUser(token, "root-holder", "OWNER");
            assertThat(root.root()).isTrue();

            // Root cannot be demoted...
            ResponseEntity<ApiError> demote = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + root.id(),
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateUserRequest("FINANCE_OFFICER", null, null, null, null, null, null),
                            authHeaders(token)),
                    ApiError.class);
            assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(demote.getBody().message()).isEqualTo("The account owner's role cannot be changed.");

            // ...nor deactivated, by either verb.
            ResponseEntity<ApiError> viaUpdate = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + root.id(),
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateUserRequest(null, false, null, null, null, null, null), authHeaders(token)),
                    ApiError.class);
            assertThat(viaUpdate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(viaUpdate.getBody().message()).isEqualTo("The account owner cannot be deactivated or deleted.");

            ResponseEntity<ApiError> viaDelete = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + root.id(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(token)),
                    ApiError.class);
            assertThat(viaDelete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(viaDelete.getBody().message()).isEqualTo("The account owner cannot be deactivated or deleted.");
            assertThat(userRepository.findById(root.id()).orElseThrow().isActive()).isTrue();

            // A non-root owner is still fully manageable - root protection is about
            // one account, not about the OWNER role.
            UserSummaryResponse coOwner = createPlatformOwnerUser(token, "co-owner", "OWNER");
            assertThat(coOwner.root()).isFalse();
            ResponseEntity<UserSummaryResponse> demoted = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + coOwner.id(),
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateUserRequest("FINANCE_OFFICER", null, null, null, null, null, null),
                            authHeaders(token)),
                    UserSummaryResponse.class);
            assertThat(demoted.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(demoted.getBody().role()).isEqualTo("FINANCE_OFFICER");

            // And a spare owner can be deactivated outright, because the root user
            // still holds the OWNER seat the headcount rule is counting. The rule
            // firing is the next test's job - it needs a tenant whose last active
            // owner is not root, which this API cannot create.
            UserSummaryResponse deactivatable = createPlatformOwnerUser(token, "spare-owner", "OWNER");
            ResponseEntity<Void> deactivated = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + deactivatable.id(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(token)),
                    Void.class);
            assertThat(deactivated.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(userRepository.findById(deactivatable.id()).orElseThrow().isActive())
                    .isFalse();
        });
    }

    /**
     * The last-active-owner guard firing, which needs a platform owner whose only
     * active OWNER is not the root user - otherwise root protection answers first
     * and the headcount rule is never reached. That shape cannot be built through
     * the API (the first user created is always root), so the tenant's users are
     * inserted directly, which is also the honest reproduction of a tenant created
     * before the root flag existed - V5's backfill made those tenants' oldest user
     * root, but a client whose root user was never established is exactly what this
     * guard is the last line of defence for.
     */
    @Test
    void thePlatformOwnerCannotBeLeftWithoutAnActiveOwner() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            User soleOwner = insertUser(temporary, "sole-owner", "OWNER", false);

            ResponseEntity<ApiError> demote = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + soleOwner.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateUserRequest("STOREKEEPER", null, null, null, null, null, null),
                            authHeaders(token)),
                    ApiError.class);
            assertThat(demote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(demote.getBody().message()).contains("no active owner");

            ResponseEntity<ApiError> deactivate = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + soleOwner.getId(),
                    HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(token)),
                    ApiError.class);
            assertThat(deactivate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(deactivate.getBody().message()).contains("no active owner");

            // Still an OWNER, still active - the guard held rather than merely
            // reporting. Read back through the API rather than the entity: User.role
            // is a LAZY association and this thread has no open persistence context.
            UserSummaryResponse reloaded = restTemplate
                    .exchange(
                            "/api/superadmin/platform-owner/users/" + soleOwner.getId(),
                            HttpMethod.GET,
                            new HttpEntity<>(authHeaders(token)),
                            UserSummaryResponse.class)
                    .getBody();
            assertThat(reloaded.role()).isEqualTo("OWNER");
            assertThat(reloaded.active()).isTrue();

            // Promote somebody else first and the same demotion is allowed, which is
            // what makes this a headcount rule rather than a ban.
            insertUser(temporary, "second-owner", "OWNER", false);
            ResponseEntity<UserSummaryResponse> allowed = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + soleOwner.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(
                            new UpdateUserRequest("STOREKEEPER", null, null, null, null, null, null),
                            authHeaders(token)),
                    UserSummaryResponse.class);
            assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(allowed.getBody().role()).isEqualTo("STOREKEEPER");
        });
    }

    /**
     * The lockout-recovery path this endpoint largely exists for: a super admin CAN
     * reset the platform owner's root password, unlike a co-owner on the tenant
     * surface (RootUserProtectionIntegrationTest asserts the opposite there, and the
     * difference is deliberate - see SuperAdminUserService). Proven by logging in
     * with the new password, so it is the credential that changed and not just the
     * status code.
     */
    @Test
    void aSuperAdminCanResetThePlatformOwnersRootPassword() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            UserSummaryResponse root = createPlatformOwnerUser(token, "locked-out-owner", "OWNER");
            assertThat(root.root()).isTrue();
            String recovered = "recovered-by-platform-operations";

            ResponseEntity<Void> reset = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + root.id() + "/reset-password",
                    HttpMethod.POST,
                    new HttpEntity<>(new ResetPasswordRequest(recovered, recovered), authHeaders(token)),
                    Void.class);
            assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(loginStatus(temporary.getSlug(), "locked-out-owner", recovered))
                    .isEqualTo(HttpStatus.OK);
            assertThat(loginStatus(temporary.getSlug(), "locked-out-owner", PASSWORD))
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            // A mismatched confirmation is still a 400, for root as for anyone.
            ResponseEntity<ApiError> mismatch = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users/" + root.id() + "/reset-password",
                    HttpMethod.POST,
                    new HttpEntity<>(new ResetPasswordRequest(recovered, "something-else"), authHeaders(token)),
                    ApiError.class);
            assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(mismatch.getBody().message()).isEqualTo("Password and confirmation do not match");
        });
    }

    @Test
    void aDuplicateUsernameInThePlatformOwnerTenantIsAConflict() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            createPlatformOwnerUser(token, "taken-name", "OWNER");

            ResponseEntity<ApiError> response = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            new CreateUserRequest("taken-name", PASSWORD, "STOREKEEPER", null, null, null, null, null),
                            authHeaders(token)),
                    ApiError.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().message()).contains("already in use");
        });
    }

    @Test
    void anUnknownRoleIsRejectedBeforeAnythingIsWritten() {
        String token = superAdminToken();

        withTemporaryPlatformOwner(temporary -> {
            createPlatformOwnerUser(token, "already-root", "OWNER");

            ResponseEntity<ApiError> response = restTemplate.exchange(
                    "/api/superadmin/platform-owner/users",
                    HttpMethod.POST,
                    new HttpEntity<>(
                            new CreateUserRequest("bad-role", PASSWORD, "SUPREME_LEADER", null, null, null, null, null),
                            authHeaders(token)),
                    ApiError.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().message()).contains("Invalid role");
            assertThat(userRepository.countByClientId(temporary.getId())).isEqualTo(1);
        });
    }

    /**
     * The state a fresh production database is genuinely in until somebody sets
     * app.platform-owner.*. It must be a sentence naming that step, on every verb -
     * not a 500, and not an empty list that looks like ProcurePal simply has no
     * staff yet.
     */
    @Test
    void everyPlatformOwnerEndpointExplainsItselfWhenNoPlatformOwnerExists() {
        String token = superAdminToken();

        withNoPlatformOwner(() -> {
            record Probe(HttpMethod method, String path, Object body) {}
            String someUser = UUID.randomUUID().toString();
            List<Probe> probes = List.of(
                    new Probe(HttpMethod.GET, "/api/superadmin/platform-owner/users", null),
                    new Probe(HttpMethod.GET, "/api/superadmin/platform-owner/users/" + someUser, null),
                    new Probe(
                            HttpMethod.POST,
                            "/api/superadmin/platform-owner/users",
                            new CreateUserRequest("nobody", PASSWORD, "OWNER", null, null, null, null, null)),
                    new Probe(
                            HttpMethod.PUT,
                            "/api/superadmin/platform-owner/users/" + someUser,
                            new UpdateUserRequest("OWNER", null, null, null, null, null, null)),
                    new Probe(
                            HttpMethod.POST,
                            "/api/superadmin/platform-owner/users/" + someUser + "/reset-password",
                            new ResetPasswordRequest(PASSWORD, PASSWORD)),
                    new Probe(HttpMethod.DELETE, "/api/superadmin/platform-owner/users/" + someUser, null));

            for (Probe probe : probes) {
                ResponseEntity<ApiError> response = restTemplate.exchange(
                        probe.path(), probe.method(), new HttpEntity<>(probe.body(), authHeaders(token)),
                        ApiError.class);
                assertThat(response.getStatusCode())
                        .as("%s %s", probe.method(), probe.path())
                        .isEqualTo(HttpStatus.CONFLICT);
                assertThat(response.getBody().message())
                        .as("%s %s", probe.method(), probe.path())
                        .contains("No platform owner tenant exists yet")
                        .contains("app.platform-owner");
            }
        });
    }

    /** The seeded procurepal tenant is reachable through the platform-owner surface as it stands. */
    @Test
    void theSeededPlatformOwnersUsersAreListedWithoutTouchingAnything() {
        String token = superAdminToken();
        Client platformOwner = clientRepository.findByPlatformOwnerTrue().orElseThrow();

        ResponseEntity<PageResponse<UserSummaryResponse>> response = restTemplate.exchange(
                "/api/superadmin/platform-owner/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().content()).isNotEmpty();
        assertThat(response.getBody().content()).anyMatch(UserSummaryResponse::root);
        // The same rows the cross-tenant read serves for that client id - one
        // tenant, two paths, no divergence.
        ResponseEntity<PageResponse<UserSummaryResponse>> viaClientPath = restTemplate.exchange(
                "/api/superadmin/clients/" + platformOwner.getId() + "/users",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                new ParameterizedTypeReference<>() {});
        assertThat(viaClientPath.getBody().content())
                .extracting(UserSummaryResponse::id)
                .containsExactlyInAnyOrderElementsOf(
                        response.getBody().content().stream().map(UserSummaryResponse::id).toList());
    }

    // ------------------------------------------------------------------------
    // Platform-owner borrowing
    // ------------------------------------------------------------------------

    /**
     * Runs {@code work} with a brand-new, empty client temporarily holding the
     * platform-owner flag, then puts the database back exactly as it was.
     *
     * The order matters: {@code uq_clients_single_platform_owner} is a partial
     * unique index over the TRUE rows, so the incumbent has to be demoted and
     * FLUSHED before the stand-in can be promoted, and the reverse on the way out.
     * Each repository call here is its own transaction (this test class is not
     * @Transactional), so each one commits before the next runs and the index is
     * never asked to hold two.
     *
     * The finally block deletes the stand-in's users before the client, because
     * nothing cascades from clients to users in the ORM mapping, and it restores
     * the incumbent even if the body threw - a test failure must not leave the
     * database without its procurepal tenant for every suite that runs afterwards.
     */
    private void withTemporaryPlatformOwner(Consumer<Client> work) {
        Optional<Client> incumbent = clientRepository.findByPlatformOwnerTrue();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Client standIn = clientRepository.saveAndFlush(Client.builder()
                .name("Stand-In Marketplace " + unique)
                .slug("stand-in-" + unique)
                .adminContactEmail("ops-" + unique + "@standin.example")
                .active(true)
                .platformOwner(false)
                .build());

        incumbent.ifPresent(client -> setPlatformOwner(client.getId(), false));
        setPlatformOwner(standIn.getId(), true);
        try {
            work.accept(clientRepository.findById(standIn.getId()).orElseThrow());
        } finally {
            setPlatformOwner(standIn.getId(), false);
            incumbent.ifPresent(client -> setPlatformOwner(client.getId(), true));
            userRepository.deleteAll(userRepository.findAllByClientId(standIn.getId()));
            userRepository.flush();
            clientRepository.deleteById(standIn.getId());
        }
    }

    /** The unbootstrapped state: no client at all carries the flag. */
    private void withNoPlatformOwner(Runnable work) {
        Optional<Client> incumbent = clientRepository.findByPlatformOwnerTrue();
        incumbent.ifPresent(client -> setPlatformOwner(client.getId(), false));
        try {
            assertThat(clientRepository.countByPlatformOwnerTrue()).isZero();
            work.run();
        } finally {
            incumbent.ifPresent(client -> setPlatformOwner(client.getId(), true));
        }
    }

    private void setPlatformOwner(UUID clientId, boolean platformOwner) {
        Client client = clientRepository.findById(clientId).orElseThrow();
        client.setPlatformOwner(platformOwner);
        clientRepository.saveAndFlush(client);
    }

    /**
     * Inserts a user directly, for the one shape the API deliberately cannot
     * produce: a tenant whose only owner is not its root user. TenantContext is set
     * by hand because User's @PrePersist refuses to persist without one and this
     * test thread has no request behind it - the same privileged, server-side-only
     * pattern ClientSignupService and PlatformOwnerBootstrapRunner use.
     */
    private User insertUser(Client client, String username, String roleName, boolean root) {
        Role role = roleRepository.findByName(roleName).orElseThrow();
        TenantContext.set(client.getId());
        try {
            return userRepository.saveAndFlush(User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(role)
                    .active(true)
                    .root(root)
                    .build());
        } finally {
            TenantContext.clear();
        }
    }

    // ------------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------------

    private UserSummaryResponse createPlatformOwnerUser(String token, String username, String role) {
        ResponseEntity<UserSummaryResponse> response = restTemplate.exchange(
                "/api/superadmin/platform-owner/users",
                HttpMethod.POST,
                new HttpEntity<>(
                        new CreateUserRequest(username, PASSWORD, role, null, null, null, null, null),
                        authHeaders(token)),
                UserSummaryResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UserSummaryResponse createTenantUser(TenantLoginResponse asOwner, String username, String role) {
        return restTemplate
                .exchange(
                        "/api/users",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CreateUserRequest(username, PASSWORD, role, null, null, null, null, null),
                                authHeaders(asOwner.tokens().accessToken())),
                        UserSummaryResponse.class)
                .getBody();
    }

    private String rawGet(String path, String token) {
        ResponseEntity<String> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpStatus loginStatus(String clientIdentifier, String username, String password) {
        return (HttpStatus) restTemplate
                .postForEntity("/api/auth/login", new LoginRequest(clientIdentifier, username, password), String.class)
                .getStatusCode();
    }

    private String superAdminToken() {
        String uniqueUsername = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** Minimal shape to deserialize Spring Data's Page<T> JSON - see SuperAdminClientManagementIntegrationTest. */
    private record PageResponse<T>(List<T> content) {
    }
}
