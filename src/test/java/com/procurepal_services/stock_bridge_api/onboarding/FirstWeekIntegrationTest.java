package com.procurepal_services.stock_bridge_api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.founding.SetupRequestAlerts;
import com.procurepal_services.stock_bridge_api.onboarding.dto.FirstWeekReport;
import com.procurepal_services.stock_bridge_api.onboarding.dto.FirstWeekShop;
import com.procurepal_services.stock_bridge_api.onboarding.dto.OnboardingStatus;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A shop's first week (LANDING_PAGE_PLAN.md, step 5): the checklist's numbers, "Send us your
 * list", Procurepaddy support loading products inside the shop, and the team's first-week list.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.founding.total=1000000", "app.founding.submit-limit=1000"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class FirstWeekIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private SetupRequestAlerts alerts;

    private final String local = "0803" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999);
    private final String e164 = "+234" + local.substring(1);
    private final String unique = UUID.randomUUID().toString().substring(0, 8);

    @AfterEach
    void removeSetupRequests() {
        jdbc.update("DELETE FROM setup_requests WHERE whatsapp = ?", e164);
    }

    @Test
    void aNewShopsChecklistStartsEmptyAndSendingTheListBooksASetupAndTellsTheTeamOnce() {
        TenantLoginResponse owner = signup();
        String token = owner.tokens().accessToken();

        OnboardingStatus before = status(token);
        assertThat(before.products()).isZero();
        assertThat(before.stockChanges()).isZero();
        assertThat(before.staff()).isZero();
        assertThat(before.setupStatus()).isNull();
        assertThat(before.supportAccess()).isNull();

        assertThat(sendFile(token, "stock.csv", "Rice,10\nBeans,4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sendFile(token, "shelf.jpg", "not really a photo").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<String> refused = sendFile(token, "virus.exe", "MZ");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody()).contains("Excel or CSV");

        OnboardingStatus after = status(token);
        assertThat(after.listFiles()).isEqualTo(2);
        assertThat(after.files()).extracting(file -> file.fileName()).containsExactly("stock.csv", "shelf.jpg");
        assertThat(after.setupStatus()).isEqualTo("LIST_RECEIVED");
        Map<String, Object> request = jdbc.queryForMap(
                "SELECT source, whatsapp, contacted_at FROM setup_requests WHERE client_id = (SELECT id FROM clients WHERE slug = ?)",
                owner.user().clientIdentifier());
        assertThat(request).containsEntry("source", "app").containsEntry("whatsapp", e164);
        assertThat(request.get("contacted_at")).as("a list arriving is not the team replying").isNull();
        verify(alerts, times(1)).listReceived(eq("List Shop " + unique), eq(owner.user().clientIdentifier()), eq(e164), anyInt());

        // The team downloads exactly what was sent.
        String admin = superAdminToken();
        UUID clientId = clientId(owner);
        List<Map<String, Object>> files = restTemplate.exchange("/api/superadmin/clients/" + clientId + "/product-list",
                HttpMethod.GET, new HttpEntity<>(auth(admin)), new ParameterizedTypeReference<List<Map<String, Object>>>() {}).getBody();
        ResponseEntity<byte[]> download = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/product-list/" + files.getFirst().get("id"),
                HttpMethod.GET, new HttpEntity<>(auth(admin)), byte[].class);
        assertThat(new String(download.getBody(), StandardCharsets.UTF_8)).isEqualTo("Rice,10\nBeans,4");
        assertThat(download.getHeaders().getContentDisposition().getFilename()).isEqualTo("stock.csv");
    }

    @Test
    void supportLoadsProductsInsideAShopThatAskedAndOnlyTheShopsOwnStockChangesCount() {
        TenantLoginResponse owner = signup();
        UUID clientId = clientId(owner);
        String admin = superAdminToken();

        // Not until the shop has asked for a setup.
        assertThat(supportSession(admin, clientId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        sendFile(owner.tokens().accessToken(), "list.xlsx", "x");

        ResponseEntity<TenantLoginResponse> session = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/support-session", HttpMethod.POST,
                new HttpEntity<>(auth(admin)), TenantLoginResponse.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantLoginResponse support = session.getBody();
        assertThat(support.user().username()).isEqualTo("procurepaddy-support");
        assertThat(support.user().clientIdentifier()).isEqualTo(owner.user().clientIdentifier());
        assertThat(support.user().permissions())
                .contains("MANAGE_PRODUCTS", "MANAGE_INVENTORY", "STOCK_IN")
                .doesNotContain("MANAGE_USERS", "MANAGE_ROLES", "PLACE_ORDERS", "MANAGE_COMPANY_PROFILE", "MANAGE_MARKETPLACE");

        // Support loads a product and its opening stock: the shop is loaded, not yet using it.
        ProductResponse rice = createProduct(support.tokens().accessToken(), "Rice (50 kg bag) " + unique);
        stockIn(support.tokens().accessToken(), rice.id(), 10);
        FirstWeekShop loaded = shop(admin, clientId);
        assertThat(loaded.activity().products()).isEqualTo(1);
        assertThat(loaded.activity().stockChanges()).isZero();
        assertThat(loaded.activated()).isFalse();
        assertThat(loaded.supportAccess()).isEqualTo("ON");
        assertThat(status(owner.tokens().accessToken()).staff()).as("support is not the shop's staff").isZero();

        // The shop's own first delivery activates it.
        stockIn(owner.tokens().accessToken(), rice.id(), 5);
        FirstWeekShop active = shop(admin, clientId);
        assertThat(active.activity().stockChanges()).isEqualTo(1);
        assertThat(active.activated()).isTrue();

        // The role is nobody's to give, and the owner can switch the account off for good.
        List<Map<String, Object>> roles = restTemplate.exchange("/api/roles", HttpMethod.GET,
                new HttpEntity<>(auth(owner.tokens().accessToken())), new ParameterizedTypeReference<List<Map<String, Object>>>() {}).getBody();
        assertThat(roles).extracting(role -> role.get("name")).doesNotContain(SupportAccessService.ROLE);
        UUID supportUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE client_id = ? AND username = 'procurepaddy-support'", UUID.class, clientId);
        assertThat(restTemplate.exchange("/api/users/" + supportUserId, HttpMethod.DELETE,
                new HttpEntity<>(auth(owner.tokens().accessToken())), String.class).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<String> refused = restTemplate.exchange("/api/superadmin/clients/" + clientId + "/support-session",
                HttpMethod.POST, new HttpEntity<>(auth(admin)), String.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody()).contains("switched off");
        assertThat(status(owner.tokens().accessToken()).supportAccess()).isEqualTo("OFF");
    }

    @Test
    void theTeamRecordsTheFirstWeekMessagesItSends() {
        TenantLoginResponse owner = signup();
        UUID clientId = clientId(owner);
        String admin = superAdminToken();

        FirstWeekShop fresh = shop(admin, clientId);
        assertThat(fresh.whatsapp()).isEqualTo(e164);
        assertThat(fresh.messages()).isEmpty();

        assertThat(restTemplate.exchange("/api/superadmin/first-week/" + clientId + "/messages/WELCOME", HttpMethod.POST,
                new HttpEntity<>(auth(admin)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(shop(admin, clientId).messages()).containsKey("WELCOME");
        assertThat(restTemplate.exchange("/api/superadmin/first-week/" + clientId + "/messages/HELLO", HttpMethod.POST,
                new HttpEntity<>(auth(admin)), String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        restTemplate.exchange("/api/superadmin/first-week/" + clientId + "/messages/WELCOME", HttpMethod.DELETE,
                new HttpEntity<>(auth(admin)), Void.class);
        assertThat(shop(admin, clientId).messages()).isEmpty();

        assertThat(restTemplate.getForEntity("/api/superadmin/first-week", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.exchange("/api/superadmin/first-week", HttpMethod.GET,
                new HttpEntity<>(auth(owner.tokens().accessToken())), String.class).getStatusCode().is4xxClientError()).isTrue();
    }

    private TenantLoginResponse signup() {
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest("List Shop " + unique, null, null, PASSWORD, null, local, null), TenantLoginResponse.class);
    }

    private UUID clientId(TenantLoginResponse owner) {
        return jdbc.queryForObject("SELECT id FROM clients WHERE slug = ?", UUID.class, owner.user().clientIdentifier());
    }

    private OnboardingStatus status(String token) {
        return restTemplate.exchange("/api/onboarding", HttpMethod.GET, new HttpEntity<>(auth(token)), OnboardingStatus.class).getBody();
    }

    private ResponseEntity<String> sendFile(String token, String name, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/onboarding/product-list", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> supportSession(String admin, UUID clientId) {
        return restTemplate.exchange("/api/superadmin/clients/" + clientId + "/support-session", HttpMethod.POST,
                new HttpEntity<>(auth(admin)), String.class);
    }

    private FirstWeekShop shop(String admin, UUID clientId) {
        FirstWeekReport report = restTemplate.exchange("/api/superadmin/first-week?days=2&search=" + unique, HttpMethod.GET,
                new HttpEntity<>(auth(admin)), FirstWeekReport.class).getBody();
        return report.shops().stream().filter(shop -> shop.clientId().equals(clientId)).findFirst().orElseThrow();
    }

    private ProductResponse createProduct(String token, String name) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, "RICE-" + unique, null, new BigDecimal("45000"), 2, null, null, null, null), part));
        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("create product: %s %s", response.getStatusCode(), response.getBody()).isTrue();
        return restTemplate.exchange("/api/products?search=" + name.substring(name.length() - 8), HttpMethod.GET,
                new HttpEntity<>(auth(token)), new ParameterizedTypeReference<PageOf<ProductResponse>>() {}).getBody().content().getFirst();
    }

    private void stockIn(String token, UUID productId, int quantity) {
        ResponseEntity<String> response = restTemplate.exchange("/api/products/" + productId + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, new BigDecimal("40000"), null), auth(token)), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("stock in: %s", response.getBody()).isTrue();
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder().username(username).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        SuperAdminLoginResponse login = restTemplate.postForObject("/api/superadmin/auth/login",
                new SuperAdminLoginRequest(username, PASSWORD), SuperAdminLoginResponse.class);
        return login.tokens().accessToken();
    }

    private record PageOf<T>(List<T> content) {
    }

    private HttpHeaders auth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
