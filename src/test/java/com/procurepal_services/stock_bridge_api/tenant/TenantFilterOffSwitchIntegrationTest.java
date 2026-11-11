package com.procurepal_services.stock_bridge_api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code TENANT_HIBERNATE_FILTER=false} really is the off switch: a company's request runs without
 * layer 1, as every request did before 2026-11, and still knows its tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.tenancy.hibernate-filter=false")
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Import(TenantFilterLiveRequestIntegrationTest.Probe.class)
class TenantFilterOffSwitchIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void switchedOffACompanysRequestRunsWithoutTheFilter() {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse login = restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest("Switch " + unique.substring(0, 8), null, "switch-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());

        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/test-probe/tenant-filter", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getBody().get("filterEnabled")).isEqualTo(false);
        assertThat(response.getBody().get("tenantContextSet")).isEqualTo(true);
    }
}
