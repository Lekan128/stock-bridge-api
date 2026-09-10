package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureCategory;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureResponse;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Covers the V17 slice: unit_price is now nullable and round-trips as null, the
 * new unit_count column round-trips a decimal, and the units-of-measure catalog
 * endpoint serves the full static list. Same real-HTTP-against-local-Postgres
 * shape as ProductManagementIntegrationTest, for the same reason given there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductUnitOfMeasureIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void unitPriceCanBeNullAndPackagingSizeRoundTripsAsADecimal() {
        TenantLoginResponse admin = signup("Packaging Size Co");
        Client client = clientRepository.findBySlug(admin.user().clientIdentifier()).orElseThrow();

        UUID savedId;
        TenantContext.set(client.getId());
        try {
            Product saved = productRepository.save(Product.builder()
                    .name("Half Bag Rice")
                    .sku("HALF-BAG-1")
                    .unitPrice(null)
                    .unitOfMeasure("KG")
                    .packagingUnit("BAG")
                    .packagingSize(new BigDecimal("0.50"))
                    .quantityOnHand(10)
                    .active(true)
                    .build());
            savedId = saved.getId();
        } finally {
            TenantContext.clear();
        }

        TenantContext.set(client.getId());
        try {
            Product reloaded = productRepository.findById(savedId).orElseThrow();
            assertThat(reloaded.getUnitPrice()).isNull();
            assertThat(reloaded.getUnitOfMeasure()).isEqualTo("KG");
            assertThat(reloaded.getPackagingUnit()).isEqualTo("BAG");
            assertThat(reloaded.getPackagingSize()).isEqualByComparingTo(new BigDecimal("0.50"));
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void unitsOfMeasureEndpointReturnsTheFullStaticList() {
        TenantLoginResponse admin = signup("Units Endpoint Co");

        ResponseEntity<List<UnitOfMeasureResponse>> response = restTemplate.exchange(
                "/api/products/units-of-measure",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UnitOfMeasureResponse> units = response.getBody();
        assertThat(units).hasSize(UnitOfMeasure.values().length);
        assertThat(units)
                .extracting(UnitOfMeasureResponse::code)
                .contains("KG", "BAG", "PIECE", "LITER");
        assertThat(units)
                .filteredOn(u -> u.code().equals("KG"))
                .singleElement()
                .satisfies(kg -> {
                    assertThat(kg.label()).isEqualTo("Kilogram (kg)");
                    assertThat(kg.category()).isEqualTo(UnitOfMeasureCategory.WEIGHT);
                    assertThat(kg.role()).isEqualTo(UnitOfMeasureRole.BASE);
                });
        // role flows through so the frontend can split this one flat list into the two
        // pickers (unitOfMeasure vs packagingUnit) - see UnitOfMeasureRole.
        assertThat(units)
                .filteredOn(u -> u.code().equals("BAG"))
                .singleElement()
                .satisfies(bag -> assertThat(bag.role()).isEqualTo(UnitOfMeasureRole.PACKAGING));
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
