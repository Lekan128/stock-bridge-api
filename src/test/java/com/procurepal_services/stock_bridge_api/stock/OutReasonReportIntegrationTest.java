package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.OutReason;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementSummaryResponse;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The stock-out reason on the movements report (SELLING_PRICE_PLAN.md section 6.9): it round-trips,
 * it filters the rows, and the totals footer filters exactly the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class OutReasonReportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aReasonRoundTripsAndFiltersTheRowsAndTheFooterAlike() {
        TenantLoginResponse owner = signup();
        UUID rice = product(owner);
        post(owner, rice, "stock-in", Map.of("quantity", 20));
        stockOut(owner, rice, 2, OutReason.EXPIRED);
        stockOut(owner, rice, 3, OutReason.EXPIRED);
        stockOut(owner, rice, 1, OutReason.DAMAGED);
        stockOut(owner, rice, 4, null);

        List<StockMovementResponse> expired = rows(owner, "outReason=EXPIRED");
        assertThat(expired).hasSize(2).allSatisfy(row -> assertThat(row.outReason()).isEqualTo(OutReason.EXPIRED));
        assertThat(summary(owner, "outReason=EXPIRED").outQuantity()).isEqualTo(5);

        List<StockMovementResponse> none = rows(owner, "noReason=true");
        assertThat(none).singleElement().satisfies(row -> {
            assertThat(row.outReason()).isNull();
            assertThat(row.quantity()).isEqualTo(4);
        });
        StockMovementSummaryResponse noneSummary = summary(owner, "noReason=true");
        assertThat(noneSummary.outMovementCount()).isEqualTo(1);
        assertThat(noneSummary.inMovementCount()).isZero();
    }

    @Test
    void anUnknownReasonIsRefusedNotIgnored() {
        TenantLoginResponse owner = signup();
        assertThat(restTemplate.exchange("/api/stock/movements?outReason=MELTED", HttpMethod.GET, new HttpEntity<>(auth(owner)), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void stockOut(TenantLoginResponse owner, UUID productId, int quantity, OutReason reason) {
        Map<String, Object> body = new HashMap<>(Map.of("quantity", quantity));
        if (reason != null) body.put("reason", reason.name());
        post(owner, productId, "stock-out", body);
    }

    private void post(TenantLoginResponse owner, UUID productId, String action, Map<String, Object> body) {
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(restTemplate.exchange("/api/products/" + productId + "/stock/" + action, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<StockMovementResponse> rows(TenantLoginResponse owner, String filter) {
        return restTemplate.exchange("/api/stock/movements?size=50&" + filter, HttpMethod.GET, new HttpEntity<>(auth(owner)),
                        new ParameterizedTypeReference<TestPage<StockMovementResponse>>() {})
                .getBody()
                .content();
    }

    private StockMovementSummaryResponse summary(TenantLoginResponse owner, String filter) {
        return restTemplate.exchange("/api/stock/movements/summary?" + filter, HttpMethod.GET, new HttpEntity<>(auth(owner)),
                StockMovementSummaryResponse.class).getBody();
    }

    private UUID product(TenantLoginResponse owner) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(new CreateProductRequest(
                "Rice", "RICE-" + UUID.randomUUID().toString().substring(0, 8), null, null, null, "KG", null, null, null), part));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private TenantLoginResponse signup() {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest("Reason Co " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders auth(TenantLoginResponse login) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(login.tokens().accessToken());
        return headers;
    }

    private record TestPage<T>(List<T> content) {
    }
}
