package com.procurepal_services.stock_bridge_api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Layer 1 of tenant isolation, through a real HTTP request and the whole filter chain.
 *
 * <p>{@link TenantIsolationIntegrationTest} enables the Hibernate filter by hand, so it proves the
 * filter's condition and nothing about whether a request ever turns it on. Until 2026-11 none did:
 * the filter was enabled on a throwaway Session before the request's own was opened (see
 * {@link RequestSessionConfig}), and a plain {@code findAll()} returned every company's users.
 * This asks from inside a request, on the Session that request's queries use.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Import(TenantFilterLiveRequestIntegrationTest.Probe.class)
class TenantFilterLiveRequestIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** Under a public path (SecurityConfig.permitAllPaths), and under ProcurePal's admin screens. */
    private static final String PUBLIC_PROBE = "/api/marketplace/catalog/test-probe/tenant-filter";
    private static final String ADMIN_PROBE = "/api/marketplace/admin/test-probe/tenant-filter";

    /** Test-only endpoint: what the request's own Session and an unscoped repository call see. */
    @RestController
    static class Probe {

        @PersistenceContext
        private EntityManager entityManager;

        @Autowired
        private UserRepository userRepository;

        @GetMapping({"/api/test-probe/tenant-filter", PUBLIC_PROBE, ADMIN_PROBE})
        Map<String, Object> probe() {
            Session session = entityManager.unwrap(Session.class);
            boolean filterEnabled = session.getEnabledFilter(TenantAwareEntity.TENANT_FILTER_NAME) != null;
            return Map.of(
                    "filterEnabled", filterEnabled,
                    "tenantContextSet", TenantContext.get() != null,
                    // Plain inherited findAll(): no client_id predicate of its own. Only asked with
                    // the filter on - unfiltered it is every user in the database.
                    "userClientIds", filterEnabled
                            ? userRepository.findAll().stream().map(user -> user.getClientId().toString()).distinct().toList()
                            : List.of());
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aRequestsQueriesRunWithTheTenantFilterOn() {
        TenantLoginResponse a = signup("Filter A");
        signup("Filter B");
        UUID clientA = jdbc.queryForObject("SELECT client_id FROM users WHERE id = ?", UUID.class, a.user().id());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT client_id) FROM users", Long.class)).isGreaterThan(1);

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/test-probe/tenant-filter", HttpMethod.GET, new HttpEntity<>(bearer(a)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("filterEnabled")).isEqualTo(true);
        assertThat((List<Object>) response.getBody().get("userClientIds")).containsExactly(clientA.toString());
    }

    /**
     * The public storefront and ProcurePal's admin screens keep running exactly as before: no
     * Hibernate filter, even for a signed-in company, so a buyer browsing the shop still sees every
     * seller's listings. Their handlers scope every query themselves (see TenantResolutionFilter).
     */
    @Test
    void publicAndAdminPathsRunWithTheFilterOffButKeepTheirTenant() {
        TenantLoginResponse a = signup("Filter Public");
        for (String path : new String[] {PUBLIC_PROBE, ADMIN_PROBE}) {
            ResponseEntity<Map> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(a)), Map.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().get("filterEnabled")).as(path).isEqualTo(false);
            assertThat(response.getBody().get("tenantContextSet")).as(path).isEqualTo(true);
        }
        ResponseEntity<Map> anonymous = restTemplate.getForEntity(PUBLIC_PROBE, Map.class);
        assertThat(anonymous.getBody().get("filterEnabled")).isEqualTo(false);
        assertThat(anonymous.getBody().get("tenantContextSet")).isEqualTo(false);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "filter-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders bearer(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
