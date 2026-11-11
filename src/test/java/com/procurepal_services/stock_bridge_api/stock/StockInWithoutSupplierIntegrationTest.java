package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A delivery from no supplier in particular (STOCK_SHEETS_AND_AREA_COUNT_PLAN.md Part 3, owner 2026-11-08): allowed
 * even when the product has suppliers. Its price still moves the product's cost and it is a lot like any other; it
 * just counts towards no supplier.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockInWithoutSupplierIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aProductWithASupplierCanBeStockedInFromNoSupplier() {
        TenantLoginResponse owner = signup("No Supplier Co");
        ProductResponse rice = createProduct(owner);
        CompanyVendorResponse mama = createSupplier(owner, "Mama Nkechi Foods");

        StockMutationResponse fromMama = stockIn(owner, rice.id(), new BigDecimal("10"), new BigDecimal("100"), mama.id());
        assertThat(fromMama.product().costPrice()).isEqualByComparingTo("100");

        StockMutationResponse fromNobody = stockIn(owner, rice.id(), new BigDecimal("10"), new BigDecimal("200"), null);
        assertThat(fromNobody.movement().companyVendorId()).isNull();
        assertThat(fromNobody.product().quantityOnHand()).isEqualByComparingTo("20");
        // (10 x 100 + 10 x 200) / 20: the cost still moves.
        assertThat(fromNobody.product().costPrice()).isEqualByComparingTo("150");
        // No cheaper-elsewhere hint is offered for a delivery from nobody.
        assertThat(fromNobody.cheaperVendorHint()).isNull();
    }

    private StockMutationResponse stockIn(
            TenantLoginResponse owner, UUID productId, BigDecimal quantity, BigDecimal price, UUID vendorId) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, price, null, null, vendorId, null, null, null, null, null),
                        authHeaders(owner)),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ProductResponse createProduct(TenantLoginResponse owner) {
        CreateProductRequest request = new CreateProductRequest(
                "Rice", "NS-" + UUID.randomUUID().toString().substring(0, 8), null, null, null, "KG", null, null, null);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, part));
        HttpHeaders headers = authHeaders(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private CompanyVendorResponse createSupplier(TenantLoginResponse owner, String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "0800" + suffix, null, null, null, null, null, null, null, null,
                        null, null), authHeaders(owner)),
                CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }

    private HttpHeaders authHeaders(TenantLoginResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(response.tokens().accessToken());
        return headers;
    }
}
