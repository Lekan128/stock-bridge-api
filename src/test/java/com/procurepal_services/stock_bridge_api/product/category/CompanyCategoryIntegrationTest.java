package com.procurepal_services.stock_bridge_api.product.category;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryRequest;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
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
 * A company's own product categories (BULK_IMPORT_CX_PLAN.md task 1.6), through the real HTTP
 * stack: create, rename, assign to products, filter by, delete - and never another company's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CompanyCategoryIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aCategoryIsCreatedRenamedAssignedFilteredByAndDeleted() {
        TenantLoginResponse tenant = signup("Category Co");

        CompanyCategoryResponse grains = create(tenant, "  Grains ").getBody();
        assertThat(grains.name()).as("trimmed").isEqualTo("Grains");
        ResponseEntity<String> duplicate = restTemplate.exchange("/api/company-categories", HttpMethod.POST,
                new HttpEntity<>(new CompanyCategoryRequest("grains"), json(tenant)), String.class);
        assertThat(duplicate.getBody()).contains("already have a category called");
        assertThat(duplicate.getStatusCode())
                .as("one Grains per company, whatever its capitalisation")
                .isEqualTo(HttpStatus.CONFLICT);

        ProductResponse rice = createProduct(tenant, new CreateProductRequest(
                "Rice", "CAT-RICE", null, null, null, "KG", null, null, null, grains.id()));
        createProduct(tenant, new CreateProductRequest("Soap", "CAT-SOAP", null, null, null, "PIECE", null, null, null));
        assertThat(rice.categoryId()).isEqualTo(grains.id());
        assertThat(rice.categoryName()).isEqualTo("Grains");

        assertThat(list(tenant)).singleElement().satisfies(category -> assertThat(category.productCount()).isEqualTo(1));
        assertThat(productsIn(tenant, grains.id())).extracting(ProductResponse::sku).containsExactly("CAT-RICE");

        ResponseEntity<CompanyCategoryResponse> renamed = restTemplate.exchange(
                "/api/company-categories/" + grains.id(), HttpMethod.PUT,
                new HttpEntity<>(new CompanyCategoryRequest("Grains & cereals"), json(tenant)),
                CompanyCategoryResponse.class);
        assertThat(renamed.getBody().name()).isEqualTo("Grains & cereals");
        assertThat(renamed.getBody().productCount()).isEqualTo(1);

        ProductResponse cleared = updateProduct(tenant, rice.id(), new UpdateProductRequest(
                null, null, null, null, null, null, null, null, null, null, null, true));
        assertThat(cleared.categoryId()).isNull();
        updateProduct(tenant, rice.id(), new UpdateProductRequest(
                null, null, null, null, null, null, null, null, null, null, grains.id(), null));

        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/company-categories/" + grains.id(), HttpMethod.DELETE, new HttpEntity<>(json(tenant)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list(tenant)).isEmpty();
        ProductResponse after = restTemplate.exchange(
                "/api/products/" + rice.id(), HttpMethod.GET, new HttpEntity<>(json(tenant)), ProductResponse.class)
                .getBody();
        assertThat(after.categoryId()).as("the product stays, uncategorised").isNull();
    }

    @Test
    void anotherCompanysCategoryCannotBeUsedOrSeen() {
        TenantLoginResponse owner = signup("Category Owner Co");
        TenantLoginResponse other = signup("Category Other Co");
        CompanyCategoryResponse theirs = create(owner, "Drinks").getBody();

        assertThat(list(other)).isEmpty();
        ResponseEntity<Map<String, Object>> refused = restTemplate.exchange(
                "/api/products", HttpMethod.POST,
                multipart(other, new CreateProductRequest("Malt", "CAT-MALT", null, null, null, "PIECE", null, null, null, theirs.id())),
                new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.exchange("/api/company-categories/" + theirs.id(), HttpMethod.DELETE,
                new HttpEntity<>(json(other)), Void.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<CompanyCategoryResponse> create(TenantLoginResponse tenant, String name) {
        return restTemplate.exchange("/api/company-categories", HttpMethod.POST,
                new HttpEntity<>(new CompanyCategoryRequest(name), json(tenant)), CompanyCategoryResponse.class);
    }

    private List<CompanyCategoryResponse> list(TenantLoginResponse tenant) {
        return restTemplate.exchange("/api/company-categories", HttpMethod.GET, new HttpEntity<>(json(tenant)),
                new ParameterizedTypeReference<List<CompanyCategoryResponse>>() {}).getBody();
    }

    private List<ProductResponse> productsIn(TenantLoginResponse tenant, UUID categoryId) {
        Map<String, Object> page = restTemplate.exchange("/api/products?categoryId=" + categoryId, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        return content.stream()
                .map(row -> new tools.jackson.databind.json.JsonMapper().convertValue(row, ProductResponse.class))
                .toList();
    }

    private ProductResponse createProduct(TenantLoginResponse tenant, CreateProductRequest request) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, multipart(tenant, request), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ProductResponse updateProduct(TenantLoginResponse tenant, UUID id, UpdateProductRequest request) {
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products/" + id, HttpMethod.PUT, multipart(tenant, request), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
