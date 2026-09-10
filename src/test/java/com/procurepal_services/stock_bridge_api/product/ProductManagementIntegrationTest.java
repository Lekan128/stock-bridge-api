package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // -----------------------------------------------------------------------------------
    // unitPrice: optional and discarded for a buying company, required for a seller.
    // See ProductManagementService.create/.update and UnitPriceRequiredException.
    // -----------------------------------------------------------------------------------

    @Test
    void companyCreateNeverRequiresUnitPriceAndDiscardsOneIfSent() {
        TenantLoginResponse company = signup("No Price Needed Co");

        // No unitPrice at all - the ordinary case going forward for a buying company.
        ResponseEntity<ProductResponse> withoutPrice = createProduct(
                company,
                new CreateProductRequest("Napkins", "NAP-1", null, null, null, null, null, null, null),
                false);
        assertThat(withoutPrice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(withoutPrice.getBody().unitPrice()).isNull();

        // A stale client still sending one is tolerated, not rejected - and the value is
        // simply never stored, since a company has no selling price for it to mean.
        ResponseEntity<ProductResponse> withStalePrice = createProduct(
                company,
                new CreateProductRequest(
                        "Cooking Oil", "OIL-1", null, new BigDecimal("999.00"), null, null, null, null, null),
                false);
        assertThat(withStalePrice.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(withStalePrice.getBody().unitPrice()).isNull();
    }

    @Test
    void companyUpdateNeverRequiresUnitPriceAndDiscardsOneIfSent() {
        TenantLoginResponse company = signup("No Price Needed On Update Co");
        ProductResponse created = createProduct(
                company,
                new CreateProductRequest("Broom", "BROOM-1", null, null, null, null, null, null, null),
                false).getBody();
        assertThat(created.unitPrice()).isNull();

        ResponseEntity<ProductResponse> updated = restTemplate.exchange(
                "/api/products/" + created.id(),
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                null, null, null, new BigDecimal("50.00"), null, null, null,
                                null, null, null),
                        company),
                ProductResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().unitPrice()).isNull();
    }

    @Test
    void vendorCreateRequiresUnitPriceAndFailsClearly() {
        TenantLoginResponse vendor = createVendor("Needs A Price Ltd");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        vendor,
                        new CreateProductRequest("Rice", "RICE-1", null, null, null, null, null, null, null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("unit price");
    }

    @Test
    void vendorCreateWithUnitPriceSucceedsAndRoundTrips() {
        TenantLoginResponse vendor = createVendor("Has A Price Ltd");

        ResponseEntity<ProductResponse> response = createProduct(
                vendor,
                new CreateProductRequest(
                        "Beans", "BEANS-1", null, new BigDecimal("15000.00"), null, null, null, null, null),
                false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().unitPrice()).isEqualByComparingTo("15000.00");
    }

    /**
     * A vendor product can only end up with a null unitPrice by bypassing the service (a
     * direct row, standing in for data from before this rule existed) - create() itself
     * refuses to produce one. update() must still refuse to leave it that way.
     */
    @Test
    void vendorUpdateCannotLeaveAProductWithoutAUnitPrice() {
        TenantLoginResponse vendor = createVendor("Loses Its Price Ltd");
        Client vendorClient = clientRepository.findBySlug(vendor.user().clientIdentifier()).orElseThrow();
        UUID productId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, is_active) "
                        + "VALUES (?, ?, ?, ?, NULL, 0, TRUE)",
                productId, vendorClient.getId(), "Priceless Rice", "PRICELESS-1");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + productId,
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                "Renamed", null, null, null, null, null, null, null, null, null),
                        vendor),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("unit price");
    }

    // -----------------------------------------------------------------------------------
    // unitOfMeasure / packagingUnit / packagingSize: open to either tenant kind, each
    // validated against the fixed catalog with its own role (BASE for unitOfMeasure,
    // PACKAGING for packagingUnit), packagingUnit/packagingSize required as a pair, and
    // packaging requires unitOfMeasure. See ProductManagementService and UnitOfMeasure/
    // UnitOfMeasureRole.
    // -----------------------------------------------------------------------------------

    /**
     * unitOfMeasure alone - no packaging at all - is still fully valid, exactly as it was
     * before packagingUnit/packagingSize existed: a product sold loose (e.g. by the litre)
     * has nothing to say about how it is packaged.
     */
    @Test
    void unitOfMeasureAloneWithNoPackagingRoundTrips() {
        TenantLoginResponse company = signup("Loose Goods Co");

        ResponseEntity<ProductResponse> response = createProduct(
                company,
                new CreateProductRequest(
                        "Palm Oil", "LOOSE-1", null, null, null, "LITER", null, null, null),
                false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().unitOfMeasure()).isEqualTo("LITER");
        assertThat(response.getBody().packagingUnit()).isNull();
        assertThat(response.getBody().packagingSize()).isNull();
    }

    /**
     * The full "Bag of 50 kg" round trip the whole V18 rework exists for: unitOfMeasure says
     * what it is measured in, packagingUnit says how it is packaged, packagingSize says how
     * many of the base unit one package holds - all three together, unlike the old flat
     * unitOfMeasure+unitCount model which could only express one axis at a time.
     */
    @Test
    void bagOf50kgRoundTripsThroughCreateAndGet() {
        TenantLoginResponse company = signup("Fifty Kilo Bag Co");

        ResponseEntity<ProductResponse> created = createProduct(
                company,
                new CreateProductRequest(
                        "Bagged Rice", "BAGGED-1", null, null, null, "KG", "BAG", new BigDecimal("50"), null),
                false);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().unitOfMeasure()).isEqualTo("KG");
        assertThat(created.getBody().packagingUnit()).isEqualTo("BAG");
        assertThat(created.getBody().packagingSize()).isEqualByComparingTo("50");

        ResponseEntity<ProductResponse> fetched = restTemplate.exchange(
                "/api/products/" + created.getBody().id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(company)),
                ProductResponse.class);
        assertThat(fetched.getBody().unitOfMeasure()).isEqualTo("KG");
        assertThat(fetched.getBody().packagingUnit()).isEqualTo("BAG");
        assertThat(fetched.getBody().packagingSize()).isEqualByComparingTo("50");
    }

    /** Both codes are case-insensitive and stored normalized, per UnitOfMeasure.fromCode. */
    @Test
    void unitOfMeasureAndPackagingUnitCodesAreNormalizedRegardlessOfCaseSubmitted() {
        TenantLoginResponse company = signup("Lowercase Unit Co");

        ResponseEntity<ProductResponse> response = createProduct(
                company,
                new CreateProductRequest(
                        "Cement", "CEMENT-1", null, null, null, "kg", "bag", new BigDecimal("50"), null),
                false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().unitOfMeasure()).isEqualTo("KG");
        assertThat(response.getBody().packagingUnit()).isEqualTo("BAG");
    }

    @Test
    void aGarbageUnitOfMeasureCodeIsRejected() {
        TenantLoginResponse company = signup("Garbage Unit Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Widget", "WIDGET-UOM-1", null, null, null, "NOT_A_REAL_UNIT", null, null, null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("NOT_A_REAL_UNIT");
    }

    @Test
    void aGarbagePackagingUnitCodeIsRejected() {
        TenantLoginResponse company = signup("Garbage Packaging Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Widget", "WIDGET-PKG-1", null, null, null, "KG", "NOT_A_REAL_PACKAGE",
                                new BigDecimal("1"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("NOT_A_REAL_PACKAGE");
    }

    /**
     * BAG is a PACKAGING-role code - submitting it as unitOfMeasure is still rejected.
     *
     * <p>COUNT units were opened up in ONE direction only: a COUNT code may serve as a PACK (so a
     * turmeric sold in 34 g PIECEs is describable), but not as a stock unit. Widening the stock
     * unit list to every container is a bigger change than the reported problem needs, and it is
     * the shape {@code UnitOfMeasureRole}'s javadoc argues hardest against.
     */
    @Test
    void aPackagingRoleCodeIsRejectedWhenSubmittedAsUnitOfMeasure() {
        TenantLoginResponse company = signup("Wrong Role Base Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Widget", "WRONGROLE-BASE-1", null, null, null, "BAG", null, null, null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("BAG");
    }

    /**
     * The mirror case: KG is a BASE-role code - submitting it as packagingUnit, the
     * PACKAGING-role field, is rejected exactly like a code that is not on the list at all.
     */
    @Test
    void aBaseRoleCodeIsRejectedWhenSubmittedAsPackagingUnit() {
        TenantLoginResponse company = signup("Wrong Role Packaging Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Widget", "WRONGROLE-PKG-1", null, null, null, "KG", "KG",
                                new BigDecimal("1"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("KG");
    }

    @Test
    void packagingUnitWithoutPackagingSizeIsRejected() {
        TenantLoginResponse company = signup("Lone Packaging Unit Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Sugar", "SUGAR-1", null, null, null, "KG", "BAG", null, null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("together");
    }

    @Test
    void packagingSizeWithoutPackagingUnitIsRejected() {
        TenantLoginResponse company = signup("Lone Packaging Size Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Flour", "FLOUR-1", null, null, null, "KG", null, new BigDecimal("10"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("together");
    }

    /**
     * The new V18 rule: packagingSize is a COUNT of unitOfMeasure, so packaging can never be
     * set on a product with no unitOfMeasure to quantify - a bare "sold in bags of 50" says
     * 50 WHAT, unless a base unit is also given.
     */
    @Test
    void packagingWithoutUnitOfMeasureIsRejected() {
        TenantLoginResponse company = signup("Packaging No Base Unit Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest(
                                "Rice", "RICE-NOBASE-1", null, null, null, null, "BAG",
                                new BigDecimal("50"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("unitOfMeasure");
    }

    /**
     * Update's patch semantics: supplying only packagingSize is fine when the product already
     * carries a packagingUnit (and a unitOfMeasure) from creation, because the pairing check
     * runs against the RESULTING state, not the request in isolation.
     */
    @Test
    void updatingOnlyPackagingSizeIsAllowedWhenPackagingUnitIsAlreadySet() {
        TenantLoginResponse company = signup("Patch Packaging Size Co");
        ProductResponse created = createProduct(
                company,
                new CreateProductRequest(
                        "Palm Oil", "PALMOIL-1", null, null, null, "LITER", "KEG", new BigDecimal("4"), null),
                false).getBody();

        ResponseEntity<ProductResponse> updated = restTemplate.exchange(
                "/api/products/" + created.id(),
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                null, null, null, null, null, null, null, null, null,
                                new BigDecimal("5")),
                        company),
                ProductResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().unitOfMeasure()).isEqualTo("LITER");
        assertThat(updated.getBody().packagingUnit()).isEqualTo("KEG");
        assertThat(updated.getBody().packagingSize()).isEqualByComparingTo("5");
    }

    /**
     * The mirror case: a product created with neither packaging field set has nothing for a
     * lone packagingUnit patch to pair with, so the resulting state is still ambiguous and
     * the update is rejected exactly as it would be on create.
     */
    @Test
    void updatingOnlyPackagingUnitIsRejectedWhenNoPackagingSizeExistsYet() {
        TenantLoginResponse company = signup("Patch Packaging Alone Co");
        ProductResponse created = createProduct(
                company,
                new CreateProductRequest("Detergent", "DETERGENT-1", null, null, null, null, null, null, null),
                false).getBody();
        assertThat(created.unitOfMeasure()).isNull();
        assertThat(created.packagingUnit()).isNull();
        assertThat(created.packagingSize()).isNull();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + created.id(),
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                null, null, null, null, null, null, null, null, "BAG", null),
                        company),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("together");
    }

    /**
     * Clearing unitOfMeasure on an update while packaging is still in place from before is
     * rejected exactly as if packaging had been set with no unitOfMeasure to begin with - the
     * packaging-implies-unitOfMeasure rule is checked against the RESULTING state, not just
     * the raw request.
     */
    @Test
    void clearingUnitOfMeasureOnUpdateWhilePackagingRemainsIsRejected() {
        TenantLoginResponse company = signup("Clear Base Unit Co");
        ProductResponse created = createProduct(
                company,
                new CreateProductRequest(
                        "Rice", "CLEARBASE-1", null, null, null, "KG", "BAG", new BigDecimal("50"), null),
                false).getBody();

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + created.id(),
                HttpMethod.PUT,
                multipart(
                        new UpdateProductRequest(
                                null, null, null, null, null, null, null, "", null, null),
                        company),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).containsIgnoringCase("unitOfMeasure");
    }

    private TenantLoginResponse createVendor(String namePrefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = (namePrefix + "-" + suffix).toLowerCase().replace(' ', '-');
        UUID clientId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                clientId,
                namePrefix + " " + suffix,
                slug,
                "vendor-" + suffix + "@example.com");

        String username = "vendor-" + suffix;
        jdbc.update(
                "INSERT INTO users (id, client_id, username, password_hash, role_id, is_active, is_root) "
                        + "VALUES (?, ?, ?, ?, (SELECT id FROM roles WHERE name = 'VENDOR'), TRUE, TRUE)",
                UUID.randomUUID(),
                clientId,
                username,
                passwordEncoder.encode(PASSWORD));

        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login).as("vendor fixture must be able to log in").isNotNull();
        return login;
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(UpdateProductRequest request, TenantLoginResponse asAdmin) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void createWithSuccessfulImageUploadReturnsNoWarnings() {
        TenantLoginResponse admin = signup("Image Upload Co");
        when(s3ImageService.uploadProductImage(any())).thenReturn(UploadResult.success("https://cdn.example.com/x.jpg"));

        ResponseEntity<ProductResponse> response = createProduct(
                admin,
                new CreateProductRequest("Widget", "WID-1", "A widget", new BigDecimal("9.99"), 5, null, null, null, null),
                true);

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
                admin,
                new CreateProductRequest("Gadget", "GAD-1", "A gadget", new BigDecimal("14.99"), 5, null, null, null, null),
                true);

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
                new CreateProductRequest("First", "DUP-1", null, new BigDecimal("1.00"), null, null, null, null, null);
        assertThat(createProduct(admin, request, false).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<ApiError> secondResponse = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        admin,
                        new CreateProductRequest(
                                "Second", "DUP-1", null, new BigDecimal("2.00"), null, null, null, null, null),
                        false),
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
        createProduct(
                tenantA,
                new CreateProductRequest("A Product", "A-SKU", null, BigDecimal.ONE, null, null, null, null, null),
                false);
        ResponseEntity<ProductResponse> bProductResponse = createProduct(
                tenantB,
                new CreateProductRequest("B Product", "B-SKU", null, BigDecimal.ONE, null, null, null, null, null),
                false);
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

    /**
     * "Piece should be a packaging unit" - a user, correctly, about half of it.
     *
     * <p>PIECE has to stay a stock unit: for a phone there is nothing underneath it, and that is
     * the commonest inventory shape there is (Odoo's default UoM for a new product is literally
     * "Units"; NetSuite's base is "Each"). But it is also a perfectly good container - turmeric
     * measured in grams and sold in 34 g pieces is G + PIECE + 34 - and the BASE/PACKAGING split
     * refused exactly that, because it modelled role as a property of the CODE when it is a
     * property of the USAGE. COUNT units now serve either.
     */
    @Test
    void aCountUnitIsAStockUnitOnOneProductAndAPackOnAnother() {
        TenantLoginResponse company = signup("Dual Role Co");

        ResponseEntity<ProductResponse> phone = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest("Phone", "DUAL-BASE", null, null, null, "PIECE", null, null, null),
                        false),
                ProductResponse.class);
        assertThat(phone.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(phone.getBody().unitOfMeasure()).isEqualTo("PIECE");

        ResponseEntity<ProductResponse> turmeric = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest("Turmeric", "DUAL-PACK", null, null, null, "G", "PIECE",
                                new BigDecimal("34"), null),
                        false),
                ProductResponse.class);
        assertThat(turmeric.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(turmeric.getBody().unitOfMeasure()).isEqualTo("G");
        assertThat(turmeric.getBody().packagingUnit()).isEqualTo("PIECE");
    }

    /**
     * The invariant the role split used to guarantee by accident. A pack of itself converts
     * nothing - any size but 1 is a contradiction and 1 is a pack that does nothing - so it is
     * refused rather than stored.
     */
    @Test
    void aPackCannotNameTheSameUnitAsTheStockUnit() {
        TenantLoginResponse company = signup("Pack Of Itself Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest("Nonsense", "DUAL-SAME", null, null, null, "PIECE", "PIECE",
                                new BigDecimal("34"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("different from the stock unit");
    }

    /**
     * Relaxing COUNT must not relax everything: a weight is never a container, and
     * {@code packagingUnit=KG} would resurrect the "50kg bag" ambiguity the role split removed.
     */
    @Test
    void aWeightUnitIsStillRefusedAsAPack() {
        TenantLoginResponse company = signup("Kg Pack Co");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products",
                HttpMethod.POST,
                multipartEntity(
                        company,
                        new CreateProductRequest("Rice", "DUAL-KG-PACK", null, null, null, "G", "KG",
                                new BigDecimal("1000"), null),
                        false),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
