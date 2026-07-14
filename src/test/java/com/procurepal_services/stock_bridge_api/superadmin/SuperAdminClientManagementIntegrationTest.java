package com.procurepal_services.stock_bridge_api.superadmin;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.analytics.dto.AnalyticsSummaryResponse;
import com.procurepal_services.stock_bridge_api.analytics.dto.MovementsOverTimePoint;
import com.procurepal_services.stock_bridge_api.analytics.dto.TopProductEntry;
import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import com.procurepal_services.stock_bridge_api.superadmin.dto.PlatformAggregateResponse;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientDetail;
import com.procurepal_services.stock_bridge_api.superadmin.dto.SuperAdminClientSummary;
import com.procurepal_services.stock_bridge_api.superadmin.dto.TenantBreakdownEntry;
import com.procurepal_services.stock_bridge_api.superadmin.dto.UpdateClientStatusRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Exercises the real HTTP + Spring Security filter chain for /api/superadmin's
 * data endpoints against the local docker-compose Postgres - see
 * AuthIntegrationTest for why local Postgres over Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class SuperAdminClientManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void listReturnsTenantWithCoreFieldsAndSupportsSearchAndActiveFilter() {
        String superAdminToken = superAdminToken();
        String unique = UUID.randomUUID().toString().substring(0, 8);
        TenantLoginResponse admin = signup("List Fields Co " + unique);
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        createProduct(admin, "LIST-1", null);

        ResponseEntity<PageResponse<SuperAdminClientSummary>> response = restTemplate.exchange(
                "/api/superadmin/clients?search=" + unique,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SuperAdminClientSummary> content = response.getBody().content();
        assertThat(content).hasSize(1);
        SuperAdminClientSummary summary = content.get(0);
        assertThat(summary.id()).isEqualTo(client.getId());
        assertThat(summary.name()).isEqualTo(client.getName());
        assertThat(summary.slug()).isEqualTo(client.getSlug());
        assertThat(summary.active()).isTrue();
        assertThat(summary.adminEmail()).isEqualTo(client.getAdminContactEmail());
        assertThat(summary.userCount()).isEqualTo(1);
        assertThat(summary.productCount()).isEqualTo(1);
        assertThat(summary.createdAt()).isNotNull();

        // is_active filter: excluded once suspended.
        updateStatus(superAdminToken, client.getId(), false);
        ResponseEntity<PageResponse<SuperAdminClientSummary>> activeOnly = restTemplate.exchange(
                "/api/superadmin/clients?search=" + unique + "&active=true",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(activeOnly.getBody().content()).isEmpty();

        ResponseEntity<PageResponse<SuperAdminClientSummary>> inactiveOnly = restTemplate.exchange(
                "/api/superadmin/clients?search=" + unique + "&active=false",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                new ParameterizedTypeReference<>() {});
        assertThat(inactiveOnly.getBody().content()).extracting(SuperAdminClientSummary::id).containsExactly(client.getId());
    }

    @Test
    void detailIncludesQuickGlanceSummary() {
        String superAdminToken = superAdminToken();
        TenantLoginResponse admin = signup("Detail Quick Glance Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        ProductResponse low = createProduct(admin, "DQ-LOW", 100);
        createProduct(admin, "DQ-OK", null);
        stockIn(admin, low.id(), 5, new BigDecimal("1.00"));

        ResponseEntity<SuperAdminClientDetail> response = restTemplate.exchange(
                "/api/superadmin/clients/" + client.getId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                SuperAdminClientDetail.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SuperAdminClientDetail detail = response.getBody();
        assertThat(detail.id()).isEqualTo(client.getId());
        assertThat(detail.userCount()).isEqualTo(1);
        assertThat(detail.productCount()).isEqualTo(2);
        assertThat(detail.activeUserCount()).isEqualTo(1);
        assertThat(detail.activeProductCount()).isEqualTo(2);
        assertThat(detail.lowStockProductCount()).isEqualTo(1);
    }

    @Test
    void statusToggleActivatesAndDeactivatesTenant() {
        String superAdminToken = superAdminToken();
        TenantLoginResponse admin = signup("Status Toggle Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        SuperAdminClientDetail deactivated = updateStatus(superAdminToken, client.getId(), false);
        assertThat(deactivated.active()).isFalse();

        SuperAdminClientDetail reactivated = updateStatus(superAdminToken, client.getId(), true);
        assertThat(reactivated.active()).isTrue();
    }

    @Test
    void suspendedTenantIsRejectedAtLoginWithClearMessage() {
        String superAdminToken = superAdminToken();
        TenantLoginResponse admin = signup("Suspended Login Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        updateStatus(superAdminToken, client.getId(), false);

        LoginRequest loginRequest = new LoginRequest(client.getSlug(), admin.user().username(), PASSWORD);
        ResponseEntity<ApiError> response =
                restTemplate.postForEntity("/api/auth/login", loginRequest, ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("This account has been suspended. Please contact support.");
    }

    @Test
    void perTenantAnalyticsMatchesWhatThatTenantSeesViaItsOwnEndpoint() {
        String superAdminToken = superAdminToken();
        TenantLoginResponse admin = signup("Parity Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();
        ProductResponse product = createProduct(admin, "PARITY-1", 0);
        stockIn(admin, product.id(), 10, new BigDecimal("4.00"));
        stockOut(admin, product.id(), 3, new BigDecimal("6.00"));

        AnalyticsSummaryResponse tenantSummary = restTemplate.exchange(
                        "/api/analytics/summary?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(admin.tokens().accessToken())),
                        AnalyticsSummaryResponse.class)
                .getBody();
        AnalyticsSummaryResponse superAdminSummary = restTemplate.exchange(
                        "/api/superadmin/clients/" + client.getId() + "/analytics/summary?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(superAdminToken)),
                        AnalyticsSummaryResponse.class)
                .getBody();
        assertThat(superAdminSummary).isEqualTo(tenantSummary);

        List<MovementsOverTimePoint> tenantPoints = restTemplate.exchange(
                        "/api/analytics/movements-over-time?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(admin.tokens().accessToken())),
                        new ParameterizedTypeReference<List<MovementsOverTimePoint>>() {})
                .getBody();
        List<MovementsOverTimePoint> superAdminPoints = restTemplate.exchange(
                        "/api/superadmin/clients/" + client.getId() + "/analytics/movements-over-time?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(superAdminToken)),
                        new ParameterizedTypeReference<List<MovementsOverTimePoint>>() {})
                .getBody();
        assertThat(superAdminPoints).isEqualTo(tenantPoints);

        List<TopProductEntry> tenantTop = restTemplate.exchange(
                        "/api/analytics/top-products?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(admin.tokens().accessToken())),
                        new ParameterizedTypeReference<List<TopProductEntry>>() {})
                .getBody();
        List<TopProductEntry> superAdminTop = restTemplate.exchange(
                        "/api/superadmin/clients/" + client.getId() + "/analytics/top-products?" + wideRange(),
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(superAdminToken)),
                        new ParameterizedTypeReference<List<TopProductEntry>>() {})
                .getBody();
        assertThat(superAdminTop).isEqualTo(tenantTop);
    }

    @Test
    void aggregateCorrectlySumsAcrossMultipleTenants() {
        String superAdminToken = superAdminToken();
        TenantLoginResponse tenantA = signup("Aggregate Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Aggregate Tenant B " + UUID.randomUUID());
        Client clientA = clientRepository.findBySlug(tenantA.user().clientIdentifier()).orElseThrow();
        Client clientB = clientRepository.findBySlug(tenantB.user().clientIdentifier()).orElseThrow();

        ProductResponse productA = createProduct(tenantA, "AGG-A", 0);
        ProductResponse productB = createProduct(tenantB, "AGG-B", 0);
        stockIn(tenantA, productA.id(), 10, new BigDecimal("2.00")); // 20.00 in
        stockOut(tenantA, productA.id(), 4, new BigDecimal("3.00")); // 12.00 out
        stockIn(tenantB, productB.id(), 5, new BigDecimal("10.00")); // 50.00 in

        ResponseEntity<PlatformAggregateResponse> response = restTemplate.exchange(
                "/api/superadmin/analytics/aggregate?" + wideRange(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken)),
                PlatformAggregateResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlatformAggregateResponse body = response.getBody();
        assertThat(body.totalActiveClients()).isGreaterThanOrEqualTo(2);
        assertThat(body.totalActiveUsers()).isGreaterThanOrEqualTo(2);
        assertThat(body.totalActiveProducts()).isGreaterThanOrEqualTo(2);
        assertThat(body.totalStockInValue()).isGreaterThanOrEqualTo(new BigDecimal("70.00"));
        assertThat(body.totalStockOutValue()).isGreaterThanOrEqualTo(new BigDecimal("12.00"));

        TenantBreakdownEntry breakdownA = findBreakdown(body, clientA.getId());
        assertThat(breakdownA.stockInValue()).isEqualByComparingTo("20.00");
        assertThat(breakdownA.stockOutValue()).isEqualByComparingTo("12.00");
        assertThat(breakdownA.activeUserCount()).isEqualTo(1);
        assertThat(breakdownA.activeProductCount()).isEqualTo(1);

        TenantBreakdownEntry breakdownB = findBreakdown(body, clientB.getId());
        assertThat(breakdownB.stockInValue()).isEqualByComparingTo("50.00");
        assertThat(breakdownB.stockOutValue()).isEqualByComparingTo("0.00");
    }

    @Test
    void tenantTokenIsRejectedOnSuperAdminDataEndpoints() {
        TenantLoginResponse admin = signup("Cross Audience Tenant Co");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/superadmin/clients",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin.tokens().accessToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void superAdminTokenIsRejectedOnTenantDataEndpoints() {
        String superAdminToken = superAdminToken();

        ResponseEntity<String> productsResponse = restTemplate.exchange(
                "/api/products", HttpMethod.GET, new HttpEntity<>(authHeaders(superAdminToken)), String.class);
        assertThat(productsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> analyticsResponse = restTemplate.exchange(
                "/api/analytics/summary", HttpMethod.GET, new HttpEntity<>(authHeaders(superAdminToken)), String.class);
        assertThat(analyticsResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private TenantBreakdownEntry findBreakdown(PlatformAggregateResponse response, UUID clientId) {
        return response.tenantBreakdown().stream()
                .filter(entry -> entry.clientId().equals(clientId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No breakdown entry for client " + clientId));
    }

    private SuperAdminClientDetail updateStatus(String superAdminToken, UUID clientId, boolean active) {
        HttpHeaders headers = authHeaders(superAdminToken);
        ResponseEntity<SuperAdminClientDetail> response = restTemplate.exchange(
                "/api/superadmin/clients/" + clientId + "/status",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateClientStatusRequest(active), headers),
                SuperAdminClientDetail.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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

    private String wideRange() {
        return "from=2020-01-01T00:00:00Z&to=2035-01-01T00:00:00Z";
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, unitPrice, null), authHeaders(admin.tokens().accessToken())),
                StockMutationResponse.class);
    }

    private void stockOut(TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice) {
        restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, unitPrice, null), authHeaders(admin.tokens().accessToken())),
                StockMutationResponse.class);
    }

    private ProductResponse createProduct(TenantLoginResponse asAdmin, String sku, Integer lowStockThreshold) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest("Product " + sku, sku, null, new BigDecimal("9.99"), null, lowStockThreshold),
                        productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin.tokens().accessToken());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        return response.getBody();
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

    /** Minimal shape to deserialize Spring Data's Page<T> JSON without pulling in the full PagedModel type. */
    private record PageResponse<T>(List<T> content) {
    }
}
