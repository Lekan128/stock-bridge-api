package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.storage.S3ImageService;
import com.procurepal_services.stock_bridge_api.storage.UploadResult;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers. Requires `docker compose up -d` at the project root.
 *
 * S3ImageService is mocked here rather than the raw S3Client: it's the seam
 * ProductManagementService actually depends on, so controlling its return
 * value directly exercises the "create succeeds regardless of upload outcome"
 * behavior without needing real/fake AWS wiring. S3ImageService's own upload
 * logic (validation, key generation, catching SDK failures) is covered by
 * S3ImageServiceTest instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductManagementIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @MockitoBean
    private S3ImageService s3ImageService;

    @Test
    void createWithSuccessfulImageUploadReturnsNoWarnings() {
        TenantLoginResponse admin = signup("Image Upload Co");
        when(s3ImageService.uploadProductImage(any())).thenReturn(UploadResult.success("https://cdn.example.com/x.jpg"));

        ResponseEntity<ProductResponse> response = createProduct(
                admin, new CreateProductRequest("Widget", "WID-1", "A widget", new BigDecimal("9.99"), null, 5, null), true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imageUrl()).isEqualTo("https://cdn.example.com/x.jpg");
        assertThat(body.warnings()).isNull();
    }

    @Test
    void createWithS3FailureStillReturns201WithWarning() {
        TenantLoginResponse admin = signup("Broken S3 Co");
        when(s3ImageService.uploadProductImage(any())).thenReturn(UploadResult.failure("S3 is not configured"));

        ResponseEntity<ProductResponse> response = createProduct(
                admin, new CreateProductRequest("Gadget", "GAD-1", "A gadget", new BigDecimal("14.99"), null, 5, null), true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.imageUrl()).isNull();
        assertThat(body.warnings()).isNotNull().anyMatch(w -> w.contains("S3 is not configured"));
    }

    @Test
    void skuMustBeUniqueWithinTenant() {
        TenantLoginResponse admin = signup("Sku Uniqueness Co");
        CreateProductRequest request =
                new CreateProductRequest("First", "DUP-1", null, new BigDecimal("1.00"), null, null, null);
        assertThat(createProduct(admin, request, false).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<ApiError> secondResponse = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(admin, new CreateProductRequest("Second", "DUP-1", null, new BigDecimal("2.00"), null, null, null), false),
                ApiError.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void lowStockFlagAndEndpointReflectQuantityVsThreshold() {
        TenantLoginResponse admin = signup("Low Stock Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        TenantContext.set(client.getId());
        Product lowStockProduct;
        Product healthyProduct;
        try {
            lowStockProduct = productRepository.save(Product.builder()
                    .name("Low")
                    .sku("LOW-1")
                    .unitPrice(new BigDecimal("5.00"))
                    .quantityOnHand(2)
                    .lowStockThreshold(10)
                    .active(true)
                    .build());
            healthyProduct = productRepository.save(Product.builder()
                    .name("Healthy")
                    .sku("HEALTHY-1")
                    .unitPrice(new BigDecimal("5.00"))
                    .quantityOnHand(50)
                    .lowStockThreshold(10)
                    .active(true)
                    .build());
        } finally {
            TenantContext.clear();
        }

        ResponseEntity<ProductResponse> lowStockGet =
                restTemplate.exchange("/api/products/" + lowStockProduct.getId(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
        assertThat(lowStockGet.getBody().isLowStock()).isTrue();

        ResponseEntity<ProductResponse> healthyGet =
                restTemplate.exchange("/api/products/" + healthyProduct.getId(), HttpMethod.GET, new HttpEntity<>(authHeaders(admin)), ProductResponse.class);
        assertThat(healthyGet.getBody().isLowStock()).isFalse();

        ResponseEntity<List<ProductResponse>> lowStockList = restTemplate.exchange(
                "/api/products/low-stock",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        List<String> lowStockSkus = lowStockList.getBody().stream().map(ProductResponse::sku).toList();
        assertThat(lowStockSkus).contains("LOW-1").doesNotContain("HEALTHY-1");
    }

    @Test
    void listAndDetailAreScopedToCallersTenantOnly() {
        TenantLoginResponse tenantA = signup("Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Tenant B " + UUID.randomUUID());
        createProduct(tenantA, new CreateProductRequest("A Product", "A-SKU", null, BigDecimal.ONE, null, null, null), false);
        ResponseEntity<ProductResponse> bProductResponse = createProduct(
                tenantB, new CreateProductRequest("B Product", "B-SKU", null, BigDecimal.ONE, null, null, null), false);
        UUID bProductId = bProductResponse.getBody().id();

        ResponseEntity<TestPage<ProductResponse>> listAsA = restTemplate.exchange(
                "/api/products",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenantA)),
                new ParameterizedTypeReference<>() {});
        List<String> skusVisibleToA =
                listAsA.getBody().content().stream().map(ProductResponse::sku).toList();
        assertThat(skusVisibleToA).contains("A-SKU").doesNotContain("B-SKU");

        ResponseEntity<ApiError> detailAsA = restTemplate.exchange(
                "/api/products/" + bProductId, HttpMethod.GET, new HttpEntity<>(authHeaders(tenantA)), ApiError.class);
        assertThat(detailAsA.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<ProductResponse> createProduct(
            TenantLoginResponse asAdmin, CreateProductRequest request, boolean withImage) {
        return restTemplate.exchange(
                "/api/products", HttpMethod.POST, multipartEntity(asAdmin, request, withImage), ProductResponse.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(
            TenantLoginResponse asAdmin, CreateProductRequest request, boolean withImage) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));

        if (withImage) {
            HttpHeaders imagePartHeaders = new HttpHeaders();
            imagePartHeaders.setContentType(MediaType.IMAGE_JPEG);
            ByteArrayResource imageResource = new ByteArrayResource(new byte[] {1, 2, 3}) {
                @Override
                public String getFilename() {
                    return "photo.jpg";
                }
            };
            body.add("image", new HttpEntity<>(imageResource, imagePartHeaders));
        }

        HttpHeaders headers = authHeaders(asAdmin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
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

    private record TestPage<T>(List<T> content) {
    }
}
