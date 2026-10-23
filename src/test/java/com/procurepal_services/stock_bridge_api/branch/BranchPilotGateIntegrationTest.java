package com.procurepal_services.stock_bridge_api.branch;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The pilot gate (MULTI_BRANCH_BUILD_PLAN.md D-B12), as a deploy runs it: only a company support has
 * enabled may add a second branch, and the app is told not to offer it to anyone else.
 *
 * <p>The rollout is switched to "pilot" on the shared context's {@link BranchService} for the test and
 * back afterwards, rather than with a property override: an override builds a second application
 * context, with its own connection pool and its own scheduled jobs running beside every later test
 * (see the pool note in application-local.yml).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BranchPilotGateIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private BranchService branchService;

    @Test
    void inThePilotOnlyACompanySupportEnabledMayAddABranch() {
        Object rollout = ReflectionTestUtils.getField(branchService, "rollout");
        ReflectionTestUtils.setField(branchService, "rollout", "pilot");
        try {
            assertThePilotGate();
        } finally {
            ReflectionTestUtils.setField(branchService, "rollout", rollout);
        }
    }

    private void assertThePilotGate() {
        TenantLoginResponse owner = signup("Pilot Gate");

        assertThat(branches(owner).mayAddBranch()).isFalse();
        ResponseEntity<String> refused = add(owner, "Oshodi");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("isn't switched on for your company yet");
        assertThat(branches(owner).multiBranch()).as("not left half-switched").isFalse();

        jdbc.update("UPDATE clients SET branches_enabled = TRUE WHERE slug = :slug",
                new MapSqlParameterSource("slug", owner.user().clientIdentifier()));

        assertThat(branches(owner).mayAddBranch()).isTrue();
        assertThat(add(owner, "Oshodi").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(branches(owner).multiBranch()).isTrue();
    }

    private BranchListResponse branches(TenantLoginResponse who) {
        return restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(who)), BranchListResponse.class)
                .getBody();
    }

    private ResponseEntity<String> add(TenantLoginResponse who, String name) {
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/branches", HttpMethod.POST, new HttpEntity<>(Map.of("name", name), headers),
                String.class);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com",
                        PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private HttpHeaders auth(TenantLoginResponse who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(who.tokens().accessToken());
        return headers;
    }
}
