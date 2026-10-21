package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingHealthResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SupportSellingSettingsRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Phase 7's staged rollout (SELLING_PRICE_PLAN.md section 8): before launch, selling is offered only
 * to the companies support has enrolled - every other company sees exactly what it saw before - and
 * the health figures section 9 is judged on come off the ledger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.selling.rollout=pilot")
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SellingPilotIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void beforeLaunchACompanyOutsideThePilotIsNotOfferedSellingAndCannotTurnItOn() {
        TenantLoginResponse shop = signup("Not Pilot Co");
        assertThat(settings(shop).available()).isFalse();
        assertThat(put(shop, true).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // A sheet's selling prices are not offered either; a bare Price is still asked about, with
        // no "turn on selling" answer.
        ImportSessionResponse keep = upload(shop, "name,sku,selling price\nSoap,SOAP-P,350\n");
        assertThat(keep.priceQuestion()).isNull();
        ImportSessionResponse bare = upload(shop, "name,sku,price\nBeans,BEANS-P,1200\n");
        assertThat(bare.priceQuestion().kind()).isEqualTo("PRICE_MEANING");
        assertThat(bare.priceQuestion().options()).containsExactly("COST", "IGNORE");
        assertThat(bare.priceQuestion().sellingAvailable()).isFalse();
    }

    @Test
    void supportEnrolsACompanyAndItsOwnerCanThenTurnSellingOffAndBackOn() {
        TenantLoginResponse shop = signup("Pilot Co");
        UUID clientId = clientRepository.findBySlug(shop.user().clientIdentifier()).orElseThrow().getId();
        HttpHeaders support = new HttpHeaders();
        support.setBearerAuth(superAdminToken());
        assertThat(restTemplate.exchange("/api/superadmin/clients/" + clientId + "/selling", HttpMethod.PUT,
                new HttpEntity<>(new SupportSellingSettingsRequest(true, "Selling pilot"), support), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(settings(shop).available()).isTrue();
        assertThat(put(shop, false).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Off is not out of the pilot: the Undo on that toast has to work.
        assertThat(settings(shop).available()).isTrue();
        assertThat(put(shop, true).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theHealthFiguresComeOffTheLedger() {
        TenantLoginResponse shop = signup("Health Co");
        UUID clientId = clientRepository.findBySlug(shop.user().clientIdentifier()).orElseThrow().getId();
        HttpHeaders support = new HttpHeaders();
        support.setBearerAuth(superAdminToken());
        restTemplate.exchange("/api/superadmin/clients/" + clientId + "/selling", HttpMethod.PUT,
                new HttpEntity<>(new SupportSellingSettingsRequest(true, "Selling pilot"), support), String.class);

        UUID rice = product(shop, "Rice", "200");
        restTemplate.exchange("/api/products/" + rice + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", 20, "unitPrice", 100), json(shop)), String.class);
        // Four lines: two at the usual 200, one at 150 (an edit), one with no price.
        sell(shop, rice, "200");
        sell(shop, rice, "200");
        sell(shop, rice, "150");
        sell(shop, rice, null);

        SellingHealthResponse health = restTemplate.exchange("/api/superadmin/selling/health", HttpMethod.GET,
                new HttpEntity<>(support), SellingHealthResponse.class).getBody();
        assertThat(health.rollout()).isEqualTo("pilot");
        assertThat(health.turnedOnVia()).containsKey("SUPPORT");
        SellingHealthResponse.CompanyHealth mine = health.companies().stream()
                .filter(c -> c.clientId().equals(clientId)).findFirst().orElseThrow();
        assertThat(mine.lineCount()).isEqualTo(4);
        assertThat(mine.pricedShare()).isEqualByComparingTo("0.75");
        assertThat(mine.knownCostShare()).isEqualByComparingTo("1");
        assertThat(mine.priceEditShare()).isEqualByComparingTo("0.3333");

        // Tenants never reach it.
        assertThat(restTemplate.exchange("/api/superadmin/selling/health", HttpMethod.GET,
                new HttpEntity<>(json(shop)), String.class).getStatusCode().is2xxSuccessful()).isFalse();
    }

    // ------------------------------------------------------------------------------------------

    private void sell(TenantLoginResponse shop, UUID productId, String price) {
        Map<String, Object> body = new HashMap<>(Map.of("quantity", 1, "reason", "SALE", "saleId", UUID.randomUUID()));
        if (price != null) {
            body.put("unitPrice", price);
        }
        assertThat(restTemplate.exchange("/api/products/" + productId + "/stock/stock-out", HttpMethod.POST,
                new HttpEntity<>(body, json(shop)), String.class).getStatusCode().is2xxSuccessful()).isTrue();
    }

    private UUID product(TenantLoginResponse shop, String name, String price) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(Map.of("name", name, "sku", name.toUpperCase() + "-" + UUID.randomUUID().toString().substring(0, 6),
                "unitOfMeasure", "PIECE", "unitPrice", price), part));
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        Map<?, ?> created = restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class).getBody();
        return UUID.fromString(String.valueOf(created.get("id")));
    }

    private ImportSessionResponse upload(TenantLoginResponse shop, String csv) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "sheet.csv";
            }
        }, new HttpHeaders()));
        body.add("kind", "PRODUCT_CATALOG");
        body.add("mode", "CREATE_ONLY");
        HttpHeaders headers = auth(shop);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers), ImportSessionResponse.class).getBody();
    }

    private SellingSettingsResponse settings(TenantLoginResponse shop) {
        return restTemplate.exchange("/api/company/selling", HttpMethod.GET, new HttpEntity<>(auth(shop)), SellingSettingsResponse.class).getBody();
    }

    private org.springframework.http.ResponseEntity<String> put(TenantLoginResponse shop, boolean on) {
        return restTemplate.exchange("/api/company/selling", HttpMethod.PUT,
                new HttpEntity<>(new UpdateSellingSettingsRequest(on, null), json(shop)), String.class);
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder().username(username).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        return restTemplate.postForObject("/api/superadmin/auth/login", new SuperAdminLoginRequest(username, PASSWORD),
                SuperAdminLoginResponse.class).tokens().accessToken();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders auth(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }

    private static HttpHeaders json(TenantLoginResponse login) {
        HttpHeaders headers = auth(login);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
