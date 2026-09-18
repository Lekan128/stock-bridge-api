package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ColumnMappingRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PasteRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
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
 * BULK_IMPORT_CX_PLAN.md task 3.2 - rows pasted rather than uploaded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class PasteImportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    /** A block copied out of Excel or Google Sheets arrives tab-separated. */
    @Test
    void aTabSeparatedPasteWithHeadersIsReadLikeAnUploadedSheet() {
        TenantLoginResponse tenant = signup("Paste Tabs Co");

        ImportSessionResponse session = paste(tenant, new PasteRequest(
                "Product name\tYour code\tComes in\tSize of one\tPrice you pay for one\n"
                        + "Rice (Mama Gold)\tPT-RICE\tBag\t50 kg\t42000\n"
                        + "Onion\tPT-ONION\tBasket\t30 kg\t29000\n",
                "PRODUCT_CATALOG", null, null, null, null));

        assertThat(session.rowCount()).isEqualTo(2);
        assertThat(session.errorCount()).isZero();
        assertThat(session.unmappedHeaders()).isEmpty();
        assertThat(session.originalFilename()).startsWith("Pasted products, ");
        assertThat(rows(tenant, session.id())).extracting(row -> row.normalized().get("name"))
                .containsExactly("Rice (Mama Gold)", "Onion");
    }

    /**
     * The one that matters. A WhatsApp list has no header row, and every reader we have treats
     * line 1 as one - so the first product would vanish, leaving a total short by one item with
     * nothing on screen to explain it. Every row must survive, and the user lands on the mapping
     * step with their own values in front of them.
     */
    @Test
    void aPasteWithNoHeaderRowKeepsItsFirstRowAndAsksWhichColumnIsWhich() {
        TenantLoginResponse tenant = signup("Paste No Header Co");

        ImportSessionResponse session = paste(tenant, new PasteRequest(
                "Rice (Mama Gold)\t10\tbags\nOnion\t5\tbaskets\nSugar\t2\tbags\n",
                "PRODUCT_CATALOG", null, null, null, null));

        assertThat(session.rowCount()).as("not one of the three is eaten as a heading").isEqualTo(3);
        assertThat(session.needsMapping()).isTrue();
        assertThat(session.unmappedHeaders()).contains("column_1");
        assertThat(rows(tenant, session.id())).hasSize(3);

        // Point the columns at fields, which is what the mapping step is for - and only then can
        // the values be read back. All three are there, first row included.
        ResponseEntity<String> mapped = restTemplate.exchange("/api/imports/" + session.id() + "/mapping",
                HttpMethod.PATCH,
                new HttpEntity<>(new ColumnMappingRequest(Map.of(
                        "column_1", "name", "column_2", "opening_stock", "column_3", "pack")), json(tenant)),
                String.class);
        assertThat(mapped.getStatusCode()).as(mapped.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(rows(tenant, session.id())).extracting(row -> row.raw().get("name"))
                .containsExactly("Rice (Mama Gold)", "Onion", "Sugar");
    }

    /** A comma-separated paste is the same thing - the reader already sniffs the separator. */
    @Test
    void aCommaSeparatedStockInPasteCarriesTheDeliveryDetailsAskedOnce() {
        TenantLoginResponse tenant = signup("Paste Stock In Co");
        ProductResponse rice = product(tenant, "Rice", "PS-RICE", "KG", "BAG", new BigDecimal("50"));

        ImportSessionResponse session = paste(tenant, new PasteRequest(
                "Your code,How many arrived,Comes in\n" + rice.sku() + ",3,Bag\n",
                "STOCK_IN", null, "2026-09-15", "WB-31", null));

        assertThat(session.kind().name()).isEqualTo("STOCK_IN");
        assertThat(session.rowCount()).isEqualTo(1);
        assertThat(session.delivery().invoiceNo()).isEqualTo("WB-31");
        assertThat(session.originalFilename()).startsWith("Pasted delivery, ");
        assertThat(rows(tenant, session.id()).get(0).normalized().get("received_date")).isEqualTo("2026-09-15");
    }

    @Test
    void anEmptyPasteIsRefusedRatherThanCreatingAnImportOfNothing() {
        TenantLoginResponse tenant = signup("Paste Empty Co");

        ResponseEntity<String> response = restTemplate.exchange("/api/imports/paste", HttpMethod.POST,
                new HttpEntity<>(new PasteRequest("   ", "PRODUCT_CATALOG", null, null, null, null), json(tenant)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * One line and nothing under it: whether that line is a heading or a product, there is no
     * second row to import, and saying so beats a session with zero rows in it.
     */
    @Test
    void aSingleHeaderLineWithNoRowsUnderItIsRefused() {
        TenantLoginResponse tenant = signup("Paste Header Only Co");

        ResponseEntity<String> response = restTemplate.exchange("/api/imports/paste", HttpMethod.POST,
                new HttpEntity<>(new PasteRequest(
                        "Product name\tYour code\tSelling price\n", "PRODUCT_CATALOG", null, null, null, null),
                        json(tenant)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("could not find any rows");
    }

    // ------------------------------------------------------------------------------ helpers ----

    private ImportSessionResponse paste(TenantLoginResponse tenant, PasteRequest request) {
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports/paste", HttpMethod.POST, new HttpEntity<>(request, json(tenant)),
                ImportSessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private List<ImportRowResponse> rows(TenantLoginResponse tenant, UUID sessionId) {
        Map<String, Object> page = restTemplate.exchange("/api/imports/" + sessionId + "/rows?status=ALL&size=200",
                HttpMethod.GET, new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<Map<String, Object>>() {})
                .getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        tools.jackson.databind.json.JsonMapper mapper = new tools.jackson.databind.json.JsonMapper();
        return content.stream().map(row -> mapper.convertValue(row, ImportRowResponse.class)).toList();
    }

    private ProductResponse product(
            TenantLoginResponse tenant, String name, String sku, String unit, String pack, BigDecimal size) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name, sku, null, null, null, unit, pack, size, null), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers),
                ProductResponse.class).getBody();
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
