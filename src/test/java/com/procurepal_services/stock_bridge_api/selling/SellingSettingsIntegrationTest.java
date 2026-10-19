package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.company.dto.CompanyResponse;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientSettingChange;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.NotificationType;
import com.procurepal_services.stock_bridge_api.entity.SettingChangeSource;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ClientSettingChangeRepository;
import com.procurepal_services.stock_bridge_api.repository.NotificationRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SupportSellingSettingsRequest;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** The company's selling switch (SELLING_PRICE_PLAN.md section 4.2) and what it changes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SellingSettingsIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ClientSettingChangeRepository changeRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SellingSettingsService sellingSettingsService;

    @Test
    void aNewCompanyStartsOffAndTheOwnerCanTurnItOn() {
        TenantLoginResponse owner = signup("Selling Switch Co");

        SellingSettingsResponse before = get(owner);
        assertThat(before.sellsStock()).isFalse();
        assertThat(before.available()).isTrue();
        assertThat(before.changedAt()).isNull();

        SellingSettingsResponse after = put(owner, new UpdateSellingSettingsRequest(true, null), SellingSettingsResponse.class).getBody();
        assertThat(after.sellsStock()).isTrue();
        assertThat(after.changedVia()).isEqualTo(SettingChangeSource.OWNER);
        assertThat(after.changedByName()).isNotBlank();
        assertThat(after.changedAt()).isNotNull();

        // The rest of the app reads it from the company, not from the token.
        assertThat(company(owner).sellsStock()).isTrue();
    }

    @Test
    void settingTheSameValueAgainLogsNothing() {
        TenantLoginResponse owner = signup("Selling Twice Co");
        UUID clientId = clientIdOf(owner);

        put(owner, new UpdateSellingSettingsRequest(true, SettingChangeSource.FIRST_WEEK), SellingSettingsResponse.class);
        put(owner, new UpdateSellingSettingsRequest(true, SettingChangeSource.OWNER), SellingSettingsResponse.class);

        assertThat(changeRepository.findAllByClientIdAndSettingOrderByChangedAtDesc(clientId, ClientSettingChange.SELLS_STOCK))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.getSource()).isEqualTo(SettingChangeSource.FIRST_WEEK);
                    assertThat(change.getOldValue()).isEqualTo("false");
                    assertThat(change.getNewValue()).isEqualTo("true");
                });
    }

    @Test
    void aCompanyCannotClaimToBeSupport() {
        TenantLoginResponse owner = signup("Selling Claim Co");

        ResponseEntity<String> response =
                put(owner, new UpdateSellingSettingsRequest(true, SettingChangeSource.SUPPORT), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get(owner).sellsStock()).isFalse();
    }

    @Test
    void aVendorCannotTurnItOn() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Client vendor = clientRepository.save(Client.builder()
                .name("Selling Vendor " + unique)
                .slug("selling-vendor-" + unique)
                .adminContactEmail("vendor-" + unique + "@example.com")
                .clientType(ClientType.VENDOR)
                .active(true)
                .build());
        TenantContext.set(vendor.getId());
        try {
            assertThat(sellingSettingsService.get().available()).isFalse();
            assertThatThrownBy(() -> sellingSettingsService.update(true, null, UUID.randomUUID()))
                    .isInstanceOf(SellingNotAvailableException.class);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void supportCanChangeItWithAReasonAndTheOwnerIsTold() {
        TenantLoginResponse owner = signup("Selling Support Co");
        UUID clientId = clientIdOf(owner);
        String token = superAdminToken();

        ResponseEntity<String> noReason = supportPut(token, clientId, new SupportSellingSettingsRequest(true, " "), String.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        SellingSettingsResponse changed = supportPut(
                        token, clientId, new SupportSellingSettingsRequest(true, "Asked on the phone"), SellingSettingsResponse.class)
                .getBody();
        assertThat(changed.sellsStock()).isTrue();
        assertThat(changed.changedVia()).isEqualTo(SettingChangeSource.SUPPORT);
        // The owner sees "ProcurePal support", never the support account's own username.
        assertThat(changed.changedByName()).isEqualTo(SellingSettingsService.SUPPORT_NAME);

        // Scoped by client in the query itself - outside a request the tenant filter is not on.
        assertThat(notificationRepository.findAllByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, 20)).getContent())
                .filteredOn(n -> NotificationType.ACCOUNT_SETTING_CHANGED == n.getType())
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getTitle()).contains("turned on selling");
                    assertThat(n.getBody()).isEqualTo("Asked on the phone");
                });

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        SuperAdminClientDetail detail = restTemplate.exchange(
                        "/api/superadmin/clients/" + clientId, HttpMethod.GET, new HttpEntity<>(headers), SuperAdminClientDetail.class)
                .getBody();
        assertThat(detail.sellsStock()).isTrue();
    }

    @Test
    void withSellingOnACompanyKeepsAnOptionalSellingPrice() {
        TenantLoginResponse owner = signup("Selling Price Co");

        // Off: a sent price is discarded, exactly as before.
        assertThat(createProduct(owner, "OFF-" + UUID.randomUUID().toString().substring(0, 6), new BigDecimal("500.00")).unitPrice())
                .isNull();

        put(owner, new UpdateSellingSettingsRequest(true, null), SellingSettingsResponse.class);

        assertThat(createProduct(owner, "ON-" + UUID.randomUUID().toString().substring(0, 6), new BigDecimal("500.00")).unitPrice())
                .isEqualByComparingTo("500.00");
        // ...and never required: a product the company only uses has no price to give.
        assertThat(createProduct(owner, "NONE-" + UUID.randomUUID().toString().substring(0, 6), null).unitPrice()).isNull();
    }

    // ------------------------------------------------------------------------------ helpers ----

    private SellingSettingsResponse get(TenantLoginResponse owner) {
        return restTemplate.exchange("/api/company/selling", HttpMethod.GET, new HttpEntity<>(auth(owner)), SellingSettingsResponse.class)
                .getBody();
    }

    private <T> ResponseEntity<T> put(TenantLoginResponse owner, UpdateSellingSettingsRequest request, Class<T> type) {
        return restTemplate.exchange("/api/company/selling", HttpMethod.PUT, new HttpEntity<>(request, auth(owner)), type);
    }

    private <T> ResponseEntity<T> supportPut(String token, UUID clientId, SupportSellingSettingsRequest request, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/selling", HttpMethod.PUT, new HttpEntity<>(request, headers), type);
    }

    private CompanyResponse company(TenantLoginResponse owner) {
        return restTemplate.exchange("/api/company", HttpMethod.GET, new HttpEntity<>(auth(owner)), CompanyResponse.class).getBody();
    }

    private ProductResponse createProduct(TenantLoginResponse owner, String sku, BigDecimal unitPrice) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest("Product " + sku, sku, null, unitPrice, null, null, null, null, null), part));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private UUID clientIdOf(TenantLoginResponse owner) {
        return clientRepository.findBySlug(owner.user().clientIdentifier()).orElseThrow().getId();
    }

    private String superAdminToken() {
        String username = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder().username(username).passwordHash(passwordEncoder.encode(PASSWORD)).build());
        return restTemplate.postForObject(
                        "/api/superadmin/auth/login", new SuperAdminLoginRequest(username, PASSWORD), SuperAdminLoginResponse.class)
                .tokens()
                .accessToken();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders auth(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
