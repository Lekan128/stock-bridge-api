package com.procurepal_services.stock_bridge_api.product.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
import com.procurepal_services.stock_bridge_api.product.quality.dto.FixProductCodeRequest;
import com.procurepal_services.stock_bridge_api.product.quality.dto.ProductDataIssueResponse;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The one-time tidy-up (BULK_IMPORT_CX_PLAN.md task 1.8): damaged codes, products with no unit and
 * implausible setups are listed, and each can be fixed - after which it leaves the list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductDataIssueIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void theProductsThatNeedAFixAreListedAndEachFixClearsIt() {
        TenantLoginResponse tenant = signup("Tidy Up Co");
        ProductResponse rice = create(tenant, new CreateProductRequest(
                "Bag of Rice", "28.0", null, null, null, "KG", null, null, null));
        ProductResponse yam = create(tenant, new CreateProductRequest(
                "Yam", "YAM-OK", null, null, null, "KG", null, null, null));
        create(tenant, new CreateProductRequest("Tomatoes", "TOM-1", null, null, null, "MM", null, null, null));
        create(tenant, new CreateProductRequest("Pawpaw", "PAW-1", null, null, null, "MG", "BAG", new BigDecimal("12"), null));
        create(tenant, new CreateProductRequest("Soap", "SOAP-1", null, null, null, "PIECE", null, null, null));

        // Yam predates units, and has stock counted in plain units already.
        stockIn(tenant, yam.id(), 7);
        Product stored = productRepository.findById(yam.id()).orElseThrow();
        stored.setUnitOfMeasure(null);
        productRepository.saveAndFlush(stored);

        Map<String, List<String>> codesByName = new java.util.HashMap<>();
        for (ProductDataIssueResponse item : issues(tenant)) {
            codesByName.put(item.name(), item.issues().stream().map(ProductDataIssueResponse.Issue::code).toList());
        }
        assertThat(codesByName).containsOnlyKeys("Bag of Rice", "Yam", "Tomatoes", "Pawpaw");
        assertThat(codesByName.get("Bag of Rice")).containsExactly("DAMAGED_CODE");
        assertThat(codesByName.get("Yam")).containsExactly("NO_STOCK_UNIT");
        assertThat(codesByName.get("Tomatoes")).containsExactly("UNIT_LOOKS_WRONG");
        assertThat(codesByName.get("Pawpaw")).containsExactly("PACK_LOOKS_TOO_SMALL");

        ProductDataIssueResponse.Issue damaged = issues(tenant).stream()
                .filter(item -> item.name().equals("Bag of Rice")).findFirst().orElseThrow().issues().get(0);
        assertThat(damaged.suggestedCode()).as("28.0 was 28").isEqualTo("28");
        assertThat(damaged.message()).contains("28.0").contains("probably 28");

        // One click: the suggestion.
        ResponseEntity<ProductResponse> fixed = restTemplate.exchange(
                "/api/products/" + rice.id() + "/fix-code", HttpMethod.POST,
                new HttpEntity<>(new FixProductCodeRequest(null), json(tenant)), ProductResponse.class);
        assertThat(fixed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fixed.getBody().sku()).isEqualTo("28");

        // A working code is not this screen's to change.
        ResponseEntity<Map<String, Object>> refused = restTemplate.exchange(
                "/api/products/" + rice.id() + "/fix-code", HttpMethod.POST,
                new HttpEntity<>(new FixProductCodeRequest("RICE"), json(tenant)),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(refused.getBody().get("message"))).contains("isn't damaged");

        // A first unit is allowed even with stock already recorded; the 7 are now 7 kg.
        ResponseEntity<ProductResponse> unitSet = restTemplate.exchange(
                "/api/products/" + yam.id(), HttpMethod.PUT,
                multipart(tenant, new UpdateProductRequest(null, null, null, null, null, null, null, "KG", null, null)),
                ProductResponse.class);
        assertThat(unitSet.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unitSet.getBody().unitOfMeasure()).isEqualTo("KG");
        assertThat(unitSet.getBody().quantityOnHand()).isEqualTo(7);

        // ...but a unit it has cannot be swapped once stock is recorded.
        ResponseEntity<String> swap = restTemplate.exchange(
                "/api/products/" + yam.id(), HttpMethod.PUT,
                multipart(tenant, new UpdateProductRequest(null, null, null, null, null, null, null, "G", null, null)),
                String.class);
        assertThat(swap.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(issues(tenant)).extracting(ProductDataIssueResponse::name)
                .containsExactlyInAnyOrder("Tomatoes", "Pawpaw");
    }

    @Test
    void aDamagedCodeWhoseCleanFormIsTakenGetsOneFromTheName() {
        TenantLoginResponse tenant = signup("Taken Code Co");
        create(tenant, new CreateProductRequest("Garri", "50", null, null, null, "KG", null, null, null));
        create(tenant, new CreateProductRequest("Yam tuber", "50.0", null, null, null, "KG", null, null, null));

        assertThat(issues(tenant)).singleElement().satisfies(item ->
                assertThat(item.issues().get(0).suggestedCode()).isEqualTo("YAM-TUBER"));
    }

    private List<ProductDataIssueResponse> issues(TenantLoginResponse tenant) {
        return restTemplate.exchange("/api/products/data-issues", HttpMethod.GET, new HttpEntity<>(json(tenant)),
                new ParameterizedTypeReference<List<ProductDataIssueResponse>>() {}).getBody();
    }

    private ProductResponse create(TenantLoginResponse tenant, CreateProductRequest request) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, multipart(tenant, request), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void stockIn(TenantLoginResponse tenant, UUID productId, int quantity) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in", HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, null, null), json(tenant)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(TenantLoginResponse tenant, Object product) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(product, partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        ClientSignupRequest request = new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD);
        return restTemplate.postForObject("/api/clients/signup", request, TenantLoginResponse.class);
    }
}
