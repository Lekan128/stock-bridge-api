package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.DeliveryLineResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PasteRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.product.dto.UpdateProductRequest;
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
 * BULK_IMPORT_CX_PLAN.md task 3.3 - the barcode on the box, and the scan that finds it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductBarcodeIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void aScannedBarcodeAnswersWithThatProductsDeliveryLines() {
        TenantLoginResponse tenant = signup("Scan To Receive Co");
        ProductResponse rice = create(tenant, "Rice (Mama Gold)", "BC-RICE", "6154000012345");
        create(tenant, "Soap", "BC-SOAP", "6154000099999");

        List<DeliveryLineResponse> lines = restTemplate.exchange(
                "/api/imports/delivery-lines/by-barcode/6154000012345", HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<List<DeliveryLineResponse>>() {})
                .getBody();

        assertThat(lines).isNotEmpty();
        assertThat(lines).allSatisfy(line -> assertThat(line.productId()).isEqualTo(rice.id()));
        assertThat(lines).extracting(DeliveryLineResponse::comesIn).contains("Bag · 50 kg");
    }

    /**
     * 404 rather than an empty list, so the screen can offer "add it as a new product" without a
     * second round trip to find out whether anything matched.
     */
    @Test
    void aBarcodeOnNothingIsA404WithSomethingUsefulToSay() {
        TenantLoginResponse tenant = signup("Unknown Barcode Co");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/imports/delivery-lines/by-barcode/0000000000000", HttpMethod.GET,
                new HttpEntity<>(json(tenant)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("barcode");
    }

    @Test
    void twoProductsInOneCompanyCannotShareABarcode() {
        TenantLoginResponse tenant = signup("Barcode Clash Co");
        create(tenant, "Rice", "BX-RICE", "6154000054321");

        ResponseEntity<String> response = createRaw(tenant, "Beans", "BX-BEANS", "6154000054321");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("already on Rice");
    }

    /** Two companies stocking the same tin of milk each keep their own row. */
    @Test
    void twoCompaniesMayEachHaveTheSameBarcode() {
        TenantLoginResponse mine = signup("Barcode Mine Co");
        TenantLoginResponse theirs = signup("Barcode Theirs Co");
        create(mine, "Peak Milk", "BM-MILK", "6154000077777");

        assertThat(createRaw(theirs, "Peak Milk", "BT-MILK", "6154000077777").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aBarcodeCanBeMovedOffOneProductByClearingItAndOntoAnother() {
        TenantLoginResponse tenant = signup("Barcode Move Co");
        ProductResponse rice = create(tenant, "Rice", "BV-RICE", "6154000011111");
        ProductResponse beans = create(tenant, "Beans", "BV-BEANS", null);

        // Blank clears it; absent would have left it alone.
        update(tenant, rice.id(), "");
        assertThat(productById(tenant, rice.id()).barcode()).isNull();

        update(tenant, beans.id(), "6154000011111");
        assertThat(productById(tenant, beans.id()).barcode()).isEqualTo("6154000011111");
    }

    /**
     * Typing barcodes one at a time would defeat the point for a catalog of any size, so the sheet
     * carries the column like any other.
     */
    @Test
    void barcodesArriveThroughTheSheetLikeAnyOtherColumn() {
        TenantLoginResponse tenant = signup("Barcode Sheet Co");

        ImportSessionResponse session = paste(tenant,
                "Product name\tYour code\tComes in\tSize of one\tBarcode\n"
                        + "Rice (Mama Gold)\tBS-RICE\tBag\t50 kg\t6154000022222\n");

        assertThat(session.errorCount()).isZero();
        assertThat(session.unmappedHeaders()).isEmpty();
        assertThat(rows(tenant, session.id()).get(0).normalized().get("barcode")).isEqualTo("6154000022222");

        commit(tenant, session.id());
        assertThat(restTemplate.exchange(
                        "/api/imports/delivery-lines/by-barcode/6154000022222", HttpMethod.GET,
                        new HttpEntity<>(json(tenant)), String.class)
                .getStatusCode())
                .as("scannable as soon as the sheet is imported")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Left to the database this is a constraint violation half way through the commit, which rolls
     * back the whole import and names an index rather than a row - so it is caught on the review
     * screen, on the row that caused it, like every other row problem.
     */
    @Test
    void twoRowsOfOneSheetClaimingTheSameBarcodeAreCaughtOnTheReviewScreen() {
        TenantLoginResponse tenant = signup("Barcode Sheet Clash Co");

        ImportSessionResponse session = paste(tenant,
                "Product name\tYour code\tBarcode\n"
                        + "Rice\tBSC-RICE\t6154000033333\n"
                        + "Beans\tBSC-BEANS\t6154000033333\n");

        assertThat(session.errorCount()).isEqualTo(1);
        List<ImportRowResponse> rows = rows(tenant, session.id());
        assertThat(rows.get(1).errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("barcode");
            assertThat(error.message()).contains("Row 2 already has this barcode");
        });
    }

    @Test
    void aSheetRowClaimingABarcodeAnotherProductAlreadyHasIsRefusedByName() {
        TenantLoginResponse tenant = signup("Barcode Sheet Taken Co");
        create(tenant, "Rice (Mama Gold)", "BST-RICE", "6154000044444");

        ImportSessionResponse session = paste(tenant,
                "Product name\tYour code\tBarcode\n" + "Beans\tBST-BEANS\t6154000044444\n");

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(rows(tenant, session.id()).get(0).errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("barcode");
            assertThat(error.message()).contains("Rice (Mama Gold) already has this barcode");
        });
    }

    // ------------------------------------------------------------------------------ helpers ----

    private ImportSessionResponse paste(TenantLoginResponse tenant, String text) {
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports/paste", HttpMethod.POST,
                new HttpEntity<>(new PasteRequest(text, "PRODUCT_CATALOG", null, null, null, null), json(tenant)),
                ImportSessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void commit(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<String> response = restTemplate.exchange("/api/imports/" + sessionId + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
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

    private ProductResponse productById(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/products/" + id, HttpMethod.GET, new HttpEntity<>(json(tenant)),
                ProductResponse.class).getBody();
    }

    private void update(TenantLoginResponse tenant, UUID id, String barcode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(new UpdateProductRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, barcode), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + id, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ProductResponse create(TenantLoginResponse tenant, String name, String sku, String barcode) {
        ResponseEntity<String> response = createRaw(tenant, name, sku, barcode);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CREATED);
        return productBySku(tenant, sku);
    }

    private ResponseEntity<String> createRaw(
            TenantLoginResponse tenant, String name, String sku, String barcode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(new CreateProductRequest(
                name, sku, null, null, null, "KG", "BAG", new BigDecimal("50"), null, null, barcode), partHeaders));
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ProductResponse productBySku(TenantLoginResponse tenant, String sku) {
        Map<String, Object> page = restTemplate.exchange("/api/products?search=" + sku, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        tools.jackson.databind.json.JsonMapper mapper = new tools.jackson.databind.json.JsonMapper();
        return content.stream()
                .map(row -> mapper.convertValue(row, ProductResponse.class))
                .filter(product -> sku.equals(product.sku()))
                .findFirst()
                .orElseThrow();
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
