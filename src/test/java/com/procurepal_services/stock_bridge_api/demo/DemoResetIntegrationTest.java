package com.procurepal_services.stock_bridge_api.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.user.dto.CreateUserRequest;
import com.procurepal_services.stock_bridge_api.user.dto.ResetPasswordRequest;
import com.procurepal_services.stock_bridge_api.user.dto.UserSummaryResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** SuperAdmin's demo company (V86): marking it, resetting it, and what a demo refuses to do. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class DemoResetIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyCompanyTableIsEitherWipedOrDeliberatelyKept() {
        Set<String> tables = new HashSet<>(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'client_id'
                """, String.class));
        Set<String> known = new HashSet<>(DemoResetService.KEPT);
        known.addAll(DemoResetService.WIPED);
        tables.removeAll(known);
        assertThat(tables).as("tables with a client_id the demo reset doesn't know about").isEmpty();
    }

    /** The guard that keeps a reset away from real customers: a company that has been used can't become a demo. */
    @Test
    void aCompanyWithDataCannotBeMadeADemo() {
        String token = superAdminToken();
        TenantLoginResponse owner = signup("Real Customer");
        UUID clientId = clientIdOf(owner);
        String slug = owner.user().clientIdentifier();
        UUID ownerRole = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'OWNER' AND client_id IS NULL", UUID.class);
        addUser(owner, "staff-" + UUID.randomUUID() + "@example.com", ownerRole);

        assertThat(restTemplate.exchange("/api/superadmin/clients/" + clientId, HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), SuperAdminClientDetail.class).getBody().canBecomeDemo()).isFalse();
        ResponseEntity<ApiError> refused = setDemoError(token, clientId, true, slug);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().message()).contains("Only a brand-new, empty company");
        assertThat(jdbc.queryForObject("SELECT is_demo FROM clients WHERE id = ?", Boolean.class, clientId)).isFalse();
    }

    @Test
    void anOrdinaryCompanyCannotBeReset() {
        String token = superAdminToken();
        TenantLoginResponse owner = signup("Not A Demo");
        UUID clientId = clientIdOf(owner);

        ResponseEntity<ApiError> refused = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/demo-reset", HttpMethod.POST,
                new HttpEntity<>(Map.of("confirm", owner.user().clientIdentifier()), bearer(token)),
                ApiError.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().message()).contains("Only a demo company");
    }

    @Test
    void theDemoIsFilledWithNinetyDaysAndAResetKeepsEveryonesLogin() {
        String token = superAdminToken();
        TenantLoginResponse owner = signup("Paddy's Kitchen");
        UUID clientId = clientIdOf(owner);
        String slug = owner.user().clientIdentifier();

        // Not without the company's code typed back.
        assertThat(setDemo(token, clientId, true, "wrong").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(restTemplate.exchange("/api/superadmin/clients/" + clientId, HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), SuperAdminClientDetail.class).getBody().canBecomeDemo()).isTrue();
        assertThat(setDemo(token, clientId, true, slug).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(count("""
                SELECT count(*) FROM client_setting_changes WHERE client_id = ? AND setting = 'DEMO' AND new_value = 'true'
                """, clientId)).isEqualTo(1);

        // A wrong code is refused before anything happens.
        ResponseEntity<ApiError> unconfirmed = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/demo-reset", HttpMethod.POST,
                new HttpEntity<>(Map.of("confirm", "nope"), bearer(token)), ApiError.class);
        assertThat(unconfirmed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Map<String, Object> first = resetAndWait(token, clientId, slug);
        assertThat(first.get("state")).as(String.valueOf(first)).isEqualTo("DONE");

        assertThat(count("SELECT count(*) FROM products WHERE client_id = ?", clientId)).isEqualTo(DemoCatalog.ITEMS.size());
        assertThat(count("SELECT count(*) FROM branches WHERE client_id = ?", clientId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM company_vendors WHERE client_id = ?", clientId)).isEqualTo(DemoCatalog.SUPPLIERS.size());
        assertThat(count("SELECT count(*) FROM stock_movements WHERE client_id = ?", clientId)).isGreaterThan(1000);
        // History really spans the ninety days, ending today.
        assertThat(count("""
                SELECT count(*) FROM stock_movements WHERE client_id = ? AND occurred_at < now() - interval '85 days'
                """, clientId)).isPositive();
        assertThat(count("""
                SELECT count(*) FROM stock_movements WHERE client_id = ? AND occurred_at > now() - interval '2 days'
                """, clientId)).isPositive();
        // Sales, spoilt stock, counts, transfers and open orders to suppliers all show up.
        assertThat(count("SELECT count(*) FROM stock_movements WHERE client_id = ? AND sale_id IS NOT NULL", clientId)).isPositive();
        assertThat(count("""
                SELECT count(*) FROM stock_movements WHERE client_id = ? AND out_reason IN ('DAMAGED', 'EXPIRED')
                """, clientId)).isPositive();
        assertThat(count("SELECT count(*) FROM stock_transfers WHERE client_id = ?", clientId)).isGreaterThan(3);
        assertThat(count("SELECT count(*) FROM expected_deliveries WHERE client_id = ? AND status = 'OPEN'", clientId)).isEqualTo(4);
        // One of each thing a fresh company has none of: an order waiting for approval, a price request two
        // suppliers have answered, and the kitchen asking the store for stock.
        assertThat(count("SELECT count(*) FROM expected_deliveries WHERE client_id = ? AND approval = 'WAITING'", clientId)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM price_request_suppliers s JOIN price_requests r ON r.id = s.request_id
                WHERE r.client_id = ? AND s.replied_at IS NOT NULL
                """, clientId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM stock_requests WHERE client_id = ?", clientId)).isEqualTo(1);
        // The history is recorded by the kitchen staff, not all by the owner.
        assertThat(count("""
                SELECT count(DISTINCT m.created_by) FROM stock_movements m WHERE m.client_id = ?
                """, clientId)).isGreaterThanOrEqualTo(3);
        // Some things are low, so the reorder screens have something to say.
        assertThat(count("""
                SELECT count(*) FROM branch_stock bs JOIN products p ON p.id = bs.product_id
                WHERE bs.client_id = ? AND bs.quantity_on_hand < p.low_stock_threshold
                """, clientId)).isPositive();

        // A prospect, added by the owner, signs in with their own email.
        UUID ownerRole = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'OWNER' AND client_id IS NULL", UUID.class);
        UserSummaryResponse prospect = addUser(owner, "chef-" + UUID.randomUUID() + "@example.com", ownerRole);
        TenantLoginResponse prospectLogin = login(slug, prospect.username());
        UserSummaryResponse second = addUser(owner, "manager-" + UUID.randomUUID() + "@example.com", ownerRole);

        // One prospect can't change another's password, nor the owner's.
        ResponseEntity<ApiError> blocked = restTemplate.exchange(
                "/api/users/" + second.id() + "/reset-password", HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest("another-password-1", "another-password-1"), bearer(prospectLogin.tokens().accessToken())),
                ApiError.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody().message()).contains("people you added yourself");
        // But they can manage someone they added themselves.
        UserSummaryResponse theirs = addUser(prospectLogin, "sous-" + UUID.randomUUID() + "@example.com", ownerRole);
        ResponseEntity<Void> allowed = restTemplate.exchange(
                "/api/users/" + theirs.id() + "/reset-password", HttpMethod.POST,
                new HttpEntity<>(new ResetPasswordRequest("another-password-1", "another-password-1"), bearer(prospectLogin.tokens().accessToken())),
                Void.class);
        assertThat(allowed.getStatusCode().is2xxSuccessful()).isTrue();

        // A purchase order "sent" by email from the demo is marked sent, and nothing is handed to the provider.
        UUID orderId = jdbc.queryForObject(
                "SELECT id FROM expected_deliveries WHERE client_id = ? AND sent_at IS NULL AND approval IS DISTINCT FROM 'WAITING' LIMIT 1", UUID.class, clientId);
        restTemplate.exchange("/api/expected-deliveries/" + orderId + "/email", HttpMethod.POST,
                new HttpEntity<>(null, bearer(owner.tokens().accessToken())), String.class);
        assertThat(jdbc.queryForObject(
                "SELECT provider_message_id FROM purchase_order_emails WHERE expected_delivery_id = ? ORDER BY created_at DESC LIMIT 1",
                String.class, orderId)).isEqualTo("demo");

        // Switched back to an ordinary company and on again: allowed, though it now has data, because
        // that data is the demo's own.
        assertThat(setDemo(token, clientId, false, slug).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setDemo(token, clientId, true, slug).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Reset again: same company, same people, a fresh demo.
        Map<String, Object> again = resetAndWait(token, clientId, slug);
        assertThat(again.get("state")).as(String.valueOf(again)).isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM products WHERE client_id = ?", clientId)).isEqualTo(DemoCatalog.ITEMS.size());
        assertThat(count("SELECT count(*) FROM purchase_order_emails WHERE client_id = ?", clientId)).isZero();
        // The owner, the three sample staff (made once, not again) and the three people added here.
        assertThat(count("SELECT count(*) FROM users WHERE client_id = ?", clientId)).isEqualTo(7);
        assertThat(count("SELECT count(*) FROM user_branches WHERE client_id = ?", clientId))
                .isEqualTo(count("SELECT count(DISTINCT (user_id, branch_id)) FROM user_branches WHERE client_id = ?", clientId));
        assertThat(login(slug, prospect.username()).tokens().accessToken()).isNotBlank();
    }

    // ------------------------------------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> resetAndWait(String token, UUID clientId, String slug) {
        ResponseEntity<Map> started = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/demo-reset", HttpMethod.POST,
                new HttpEntity<>(Map.of("confirm", slug), bearer(token)), Map.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(8));
        while (Instant.now().isBefore(deadline)) {
            Map<String, Object> status = restTemplate.exchange(
                    "/api/superadmin/clients/" + clientId + "/demo-reset", HttpMethod.GET,
                    new HttpEntity<>(null, bearer(token)), Map.class).getBody();
            if (status != null && !"RUNNING".equals(status.get("state"))) {
                return status;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("The demo reset didn't finish in time");
    }

    private ResponseEntity<String> setDemo(String token, UUID clientId, boolean demo, String confirm) {
        return restTemplate.exchange("/api/superadmin/clients/" + clientId + "/demo", HttpMethod.PUT,
                new HttpEntity<>(Map.of("demo", demo, "confirm", confirm), bearer(token)), String.class);
    }

    private ResponseEntity<ApiError> setDemoError(String token, UUID clientId, boolean demo, String confirm) {
        return restTemplate.exchange("/api/superadmin/clients/" + clientId + "/demo", HttpMethod.PUT,
                new HttpEntity<>(Map.of("demo", demo, "confirm", confirm), bearer(token)), ApiError.class);
    }

    private UserSummaryResponse addUser(TenantLoginResponse as, String email, UUID roleId) {
        ResponseEntity<UserSummaryResponse> created = restTemplate.exchange("/api/users", HttpMethod.POST,
                new HttpEntity<>(new CreateUserRequest(email, PASSWORD, roleId, "Demo", "Person", email, null, null,
                        null, null), bearer(as.tokens().accessToken())),
                UserSummaryResponse.class);
        assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
        return created.getBody();
    }

    private TenantLoginResponse login(String slug, String username) {
        ResponseEntity<TenantLoginResponse> response = restTemplate.postForEntity(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private UUID clientIdOf(TenantLoginResponse login) {
        return jdbc.queryForObject("SELECT id FROM clients WHERE slug = ?", UUID.class, login.user().clientIdentifier());
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null,
                        "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder().username(username).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        SuperAdminLoginResponse response = restTemplate.postForObject("/api/superadmin/auth/login",
                new SuperAdminLoginRequest(username, PASSWORD), SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

}
