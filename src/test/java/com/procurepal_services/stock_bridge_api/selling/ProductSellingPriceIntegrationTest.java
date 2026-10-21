package com.procurepal_services.stock_bridge_api.selling;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.ProductPriceChange;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.PriceChangeResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.SellingSettingsResponse;
import com.procurepal_services.stock_bridge_api.selling.dto.UpdateSellingSettingsRequest;
import java.util.HashMap;
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

/** A company's selling price, typed per the unit it sells in, and its history (plan section 4.5). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductSellingPriceIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aPriceTypedPerCartonIsKeptExactlyBesideItsPerPiecePrice() {
        TenantLoginResponse owner = sellingShop("Carton Price Co");

        ProductResponse milk = create(owner, Map.of("unitPrice", 5000, "unitPriceUnit", "CARTON"));

        assertThat(milk.unitPrice()).isEqualByComparingTo("208.33");
        assertThat(milk.unitPriceEntered()).isEqualByComparingTo("5000.00");
        assertThat(milk.unitPriceEnteredUnit()).isEqualTo("CARTON");
    }

    @Test
    void aPricePerPieceHasNothingEnteredBesideIt() {
        TenantLoginResponse owner = sellingShop("Piece Price Co");

        ProductResponse milk = create(owner, Map.of("unitPrice", 250));

        assertThat(milk.unitPrice()).isEqualByComparingTo("250");
        assertThat(milk.unitPriceEntered()).isNull();
        assertThat(milk.unitPriceEnteredUnit()).isNull();
    }

    @Test
    void whenThePackChangesSizeThePerPiecePriceFollowsWhatWasTyped() {
        TenantLoginResponse owner = sellingShop("Pack Resize Co");
        ProductResponse milk = create(owner, Map.of("unitPrice", 5000, "unitPriceUnit", "CARTON"));

        ProductResponse resized = update(owner, milk.id(), Map.of("packagingSize", 20)).getBody();

        assertThat(resized.unitPriceEntered()).isEqualByComparingTo("5000.00");
        assertThat(resized.unitPrice()).isEqualByComparingTo("250.00");
    }

    @Test
    void everyPriceChangeIsLoggedWithWhoAndThroughWhat() {
        TenantLoginResponse owner = sellingShop("Price Log Co");
        ProductResponse milk = create(owner, Map.of("unitPrice", 5000, "unitPriceUnit", "CARTON"));

        update(owner, milk.id(), Map.of("unitPrice", 5200, "unitPriceUnit", "CARTON"));
        // Saving the same thing again is not a change.
        update(owner, milk.id(), Map.of("unitPrice", 5200, "unitPriceUnit", "CARTON"));
        update(owner, milk.id(), Map.of("clearUnitPrice", true));

        List<PriceChangeResponse> history = history(owner, milk.id());
        assertThat(history).hasSize(2);
        PriceChangeResponse cleared = history.get(0);
        assertThat(cleared.newUnitPrice()).isNull();
        assertThat(cleared.oldEnteredPrice()).isEqualByComparingTo("5200.00");
        PriceChangeResponse raised = history.get(1);
        assertThat(raised.oldEnteredPrice()).isEqualByComparingTo("5000.00");
        assertThat(raised.newEnteredPrice()).isEqualByComparingTo("5200.00");
        assertThat(raised.newEnteredUnit()).isEqualTo("CARTON");
        assertThat(raised.source()).isEqualTo(ProductPriceChange.Source.FORM);
        assertThat(raised.changedByName()).isNotBlank();
    }

    @Test
    void aPricePerAUnitTheProductDoesNotHaveIsRefused() {
        TenantLoginResponse owner = sellingShop("Bad Unit Co");

        ResponseEntity<String> response = createRaw(owner, Map.of("unitPrice", 5000, "unitPriceUnit", "DRUM"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------------ helpers ----

    private TenantLoginResponse sellingShop(String name) {
        String unique = UUID.randomUUID().toString();
        TenantLoginResponse owner = restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
        restTemplate.exchange("/api/company/selling", HttpMethod.PUT,
                new HttpEntity<>(new UpdateSellingSettingsRequest(true, null), auth(owner)), SellingSettingsResponse.class);
        return owner;
    }

    /** A milk product counted in pieces that comes in cartons of 24, plus {@code extra}. */
    private ProductResponse create(TenantLoginResponse owner, Map<String, Object> extra) {
        ResponseEntity<String> raw = createRaw(owner, extra);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return restTemplate.exchange("/api/products/" + idOf(raw.getBody()), HttpMethod.GET, new HttpEntity<>(auth(owner)),
                ProductResponse.class).getBody();
    }

    private ResponseEntity<String> createRaw(TenantLoginResponse owner, Map<String, Object> extra) {
        Map<String, Object> product = new HashMap<>(Map.of(
                "name", "Milk " + UUID.randomUUID().toString().substring(0, 6),
                "sku", "MILK-" + UUID.randomUUID().toString().substring(0, 8),
                "unitOfMeasure", "PIECE",
                "packagingUnit", "CARTON",
                "packagingSize", 24));
        product.putAll(extra);
        return restTemplate.exchange("/api/products", HttpMethod.POST, multipart(owner, product), String.class);
    }

    private ResponseEntity<ProductResponse> update(TenantLoginResponse owner, UUID id, Map<String, Object> patch) {
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products/" + id, HttpMethod.PUT, multipart(owner, patch), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private List<PriceChangeResponse> history(TenantLoginResponse owner, UUID id) {
        return restTemplate.exchange("/api/products/" + id + "/price-changes", HttpMethod.GET, new HttpEntity<>(auth(owner)),
                        new ParameterizedTypeReference<List<PriceChangeResponse>>() {})
                .getBody();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(TenantLoginResponse owner, Map<String, Object> product) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(product, part));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private static UUID idOf(String json) {
        int at = json.indexOf("\"id\":\"") + 6;
        return UUID.fromString(json.substring(at, at + 36));
    }

    private static HttpHeaders auth(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
