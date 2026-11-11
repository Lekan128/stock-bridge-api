package com.procurepal_services.stock_bridge_api.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Company A, signed in, going after company B's rows by id and through every list: each must come
 * back as "not found" or simply absent, never B's data, and nothing of B's may change. One company
 * per area that holds stock - products, stock and its ledger, the on-device sync feed, suppliers,
 * categories and users. Holds whichever isolation layer is doing the work; see
 * {@link TenantFilterLiveRequestIntegrationTest} for layer 1 on its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class CrossTenantAccessIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private TenantLoginResponse a;
    private TenantLoginResponse b;
    private UUID bProduct;
    private UUID bMovement;
    private UUID bVendor;
    private UUID bCategory;

    @BeforeEach
    void twoCompanies() {
        a = signup("Cross A");
        b = signup("Cross B");
        product(a, "A's own");
        bProduct = product(b, "B's secret");
        Map<?, ?> stockIn = post(b, "/api/products/" + bProduct + "/stock/stock-in", Map.of("quantity", 7, "unitPrice", 100));
        bMovement = UUID.fromString((String) ((Map<?, ?>) stockIn.get("movement")).get("id"));
        bVendor = UUID.fromString((String) post(b, "/api/company-vendors", Map.of("name", "B's supplier", "contactPhone", "08031234567")).get("id"));
        bCategory = UUID.fromString((String) post(b, "/api/company-categories", Map.of("name", "B's shelf")).get("id"));
    }

    @Test
    void anotherCompanysRowsAreNotFoundById() {
        assertNotFound(HttpMethod.GET, "/api/products/" + bProduct);
        assertNotFound(HttpMethod.GET, "/api/products/" + bProduct + "/stock/history");
        assertNotFound(HttpMethod.GET, "/api/products/" + bProduct + "/lots");
        assertNotFound(HttpMethod.GET, "/api/products/" + bProduct + "/vendors");
        assertNotFound(HttpMethod.GET, "/api/stock-movements/" + bMovement + "/allocations");
        assertNotFound(HttpMethod.GET, "/api/company-vendors/" + bVendor);
        assertNotFound(HttpMethod.GET, "/api/users/" + b.user().id());
    }

    @Test
    void anotherCompanysRowsCannotBeChanged() {
        assertNotFound(HttpMethod.POST, "/api/products/" + bProduct + "/stock/stock-in", Map.of("quantity", 5));
        assertNotFound(HttpMethod.POST, "/api/products/" + bProduct + "/stock/stock-out", Map.of("quantity", 1));
        assertNotFound(HttpMethod.POST, "/api/products/" + bProduct + "/stock/movements/" + bMovement + "/void", Map.of());
        assertNotFound(HttpMethod.DELETE, "/api/products/" + bProduct);
        assertNotFound(HttpMethod.DELETE, "/api/company-categories/" + bCategory);
        assertNotFound(HttpMethod.DELETE, "/api/company-vendors/" + bVendor);

        assertThat(jdbc.queryForObject("SELECT quantity_on_hand FROM products WHERE id = ?", Integer.class, bProduct)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT is_active FROM products WHERE id = ?", Boolean.class, bProduct)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_movements WHERE product_id = ?", Long.class, bProduct)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM company_categories WHERE id = ?", Long.class, bCategory)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM company_vendors WHERE id = ?", Boolean.class, bVendor)).isTrue();
    }

    @Test
    void anotherCompanysRowsAreAbsentFromEveryList() {
        assertThat(contentNames(get(a, "/api/products?size=100"))).containsExactly("A's own");
        assertThat(ids(get(a, "/api/stock/movements?size=100"))).doesNotContain(bMovement.toString());
        assertThat(ids(get(a, "/api/stock/movements?productId=" + bProduct))).isEmpty();
        assertThat(ids(get(a, "/api/company-vendors?size=100"))).doesNotContain(bVendor.toString());
        assertThat(ids(get(a, "/api/users?size=100"))).doesNotContain(b.user().id().toString());

        Map<?, ?> snapshot = get(a, "/api/products/sync/snapshot");
        assertThat(contentNames(Map.of("content", snapshot.get("products")))).containsExactly("A's own");
    }

    private void assertNotFound(HttpMethod method, String path) {
        assertNotFound(method, path, null);
    }

    private void assertNotFound(HttpMethod method, String path, Object body) {
        HttpHeaders headers = bearer(a);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        ResponseEntity<String> response = restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode().value()).as(method + " " + path + " -> " + response.getBody()).isEqualTo(404);
    }

    private Map<?, ?> get(TenantLoginResponse who, String path) {
        ResponseEntity<Object> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(who)), Object.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("GET " + path).isTrue();
        Object body = response.getBody();
        // Some lists are plain arrays, the rest Spring pages.
        return body instanceof List<?> list ? Map.of("content", list) : (Map<?, ?>) body;
    }

    private Map<?, ?> post(TenantLoginResponse who, String path, Object body) {
        HttpHeaders headers = bearer(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("POST " + path + " -> " + response.getBody()).isTrue();
        return response.getBody();
    }

    private UUID product(TenantLoginResponse who, String name) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                Map.of("name", name, "sku", "X-" + UUID.randomUUID().toString().substring(0, 8), "unitOfMeasure", "KG"), part));
        HttpHeaders headers = bearer(who);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> response = restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("create " + name + " -> " + response.getBody()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private static List<Object> ids(Map<?, ?> page) {
        return ((List<?>) page.get("content")).stream().map(row -> ((Map<?, ?>) row).get("id")).map(Object.class::cast).toList();
    }

    private static List<Object> contentNames(Map<?, ?> page) {
        return ((List<?>) page.get("content")).stream().map(row -> ((Map<?, ?>) row).get("name")).map(Object.class::cast).toList();
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "cross-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders bearer(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }
}
