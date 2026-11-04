package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.DeliveryLineResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * A purchase order's picker must work with thirty products and thirty thousand, so it asks for a
 * bounded search - never for the whole catalog.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class DeliveryLinesSearchIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void searchFindsByNameSkuAndIgnoresCase() {
        TenantLoginResponse tenant = signup("Search Co");
        product(tenant, "Rice (Mama Gold)", "DL-RICE-1");
        product(tenant, "Rice (Local)", "DL-RICE-2");
        product(tenant, "Beans", "DL-BEANS");

        assertThat(names(lines(tenant, "?search=rice"))).containsExactlyInAnyOrder("Rice (Mama Gold)", "Rice (Local)");
        assertThat(names(lines(tenant, "?search=RICE"))).hasSize(2);
        assertThat(names(lines(tenant, "?search=dl-beans"))).containsExactly("Beans");
        assertThat(names(lines(tenant, "?search=zzz"))).isEmpty();
    }

    @Test
    void wildcardCharactersAreJustCharacters() {
        TenantLoginResponse tenant = signup("Wildcard Co");
        product(tenant, "Rice", "DL-WC-1");
        product(tenant, "100% Cotton", "DL-WC-2");

        assertThat(names(searchFor(tenant, "%"))).containsExactly("100% Cotton");
        assertThat(names(searchFor(tenant, "_"))).isEmpty();
    }

    @Test
    void aLimitCapsHowManyProductsComeBack() {
        TenantLoginResponse tenant = signup("Limit Co");
        for (int i = 1; i <= 5; i++) {
            product(tenant, "Maize " + i, "DL-LIM-" + i);
        }

        assertThat(names(lines(tenant, "?search=maize&limit=2"))).hasSize(2);
        assertThat(names(lines(tenant, "?search=maize"))).hasSize(5);
    }

    @Test
    void anotherCompanysProductsNeverAppear() {
        TenantLoginResponse mine = signup("Mine Co");
        TenantLoginResponse theirs = signup("Theirs Co");
        product(mine, "Secret Rice", "DL-SECRET");

        assertThat(names(lines(theirs, "?search=secret"))).isEmpty();
    }

    @Test
    void withoutSearchOrLimitItIsExactlyWhatItAlwaysWas() {
        TenantLoginResponse tenant = signup("Unchanged Co");
        product(tenant, "Rice", "DL-U-1");
        product(tenant, "Beans", "DL-U-2");

        assertThat(names(lines(tenant, ""))).containsExactlyInAnyOrder("Rice", "Beans");
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private List<DeliveryLineResponse> lines(TenantLoginResponse tenant, String query) {
        ResponseEntity<List<DeliveryLineResponse>> response = restTemplate.exchange(
                "/api/imports/delivery-lines" + query, HttpMethod.GET, new HttpEntity<>(json(tenant)),
                new ParameterizedTypeReference<>() { });
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** The search text as a URL variable, so characters like % are encoded once and arrive as typed. */
    private List<DeliveryLineResponse> searchFor(TenantLoginResponse tenant, String text) {
        ResponseEntity<List<DeliveryLineResponse>> response = restTemplate.exchange(
                "/api/imports/delivery-lines?search={text}", HttpMethod.GET, new HttpEntity<>(json(tenant)),
                new ParameterizedTypeReference<>() { }, text);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static List<String> names(List<DeliveryLineResponse> lines) {
        return lines.stream().map(DeliveryLineResponse::productName).distinct().toList();
    }

    private void product(TenantLoginResponse tenant, String name, String sku) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, "KG", null, null, null), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertThat(restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private HttpHeaders json(TenantLoginResponse tenant) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tenant.tokens().accessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup", new ClientSignupRequest(
                name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }
}
