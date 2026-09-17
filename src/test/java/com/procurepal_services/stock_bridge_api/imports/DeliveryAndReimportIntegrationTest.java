package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.DeliveryLineResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.DeliveryRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Phase 2 of BULK_IMPORT_CX_PLAN.md: a delivery typed into the app (2.1/2.2), and "Download my
 * products → edit → upload back" matched by Ref (2.3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class DeliveryAndReimportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private final DataFormatter formatter = new DataFormatter();

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------------------------ 2.1 / 2.2

    @Test
    void theDeliveryLinesAreTheStockSheetsRowsForOneSupplier() {
        TenantLoginResponse tenant = signup("Delivery Lines Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice (Mama Gold)", "DL-RICE", "KG", "BAG", new BigDecimal("50"));
        product(tenant, "Soap", "DL-SOAP", "PIECE", null, null);
        stockIn(tenant, rice.id(), new StockInRequest(2, new BigDecimal("42000"), null, "BAG", tony.id(), null, null));

        List<DeliveryLineResponse> lines = deliveryLines(tenant, "?filter=BY_VENDOR&vendorId=" + tony.id());

        assertThat(lines).extracting(DeliveryLineResponse::comesIn).containsExactly("Bag · 50 kg", "Loose · kg");
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.productId()).isEqualTo(rice.id());
            assertThat(line.supplierName()).isEqualTo("Tony Stores");
        });
        assertThat(lines.get(0).unit()).isEqualTo("BAG:50");
        assertThat(lines.get(0).pack()).isTrue();
        assertThat(lines.get(0).lastPrice()).isEqualByComparingTo("42000");
        assertThat(lines.get(1).unit()).isEqualTo("KG");
        assertThat(lines.get(1).lastPrice()).isEqualByComparingTo("840");

        assertThat(deliveryLines(tenant, "?filter=ALL")).extracting(DeliveryLineResponse::productName)
                .contains("Soap");
    }

    @Test
    void aTypedDeliveryIsRecordedThroughTheSameReviewAndCommit() {
        TenantLoginResponse tenant = signup("Typed Delivery Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice (Mama Gold)", "TD-RICE", "KG", "BAG", new BigDecimal("50"));
        ProductResponse onion = product(tenant, "Onion", "TD-ONION", "KG", "BASKET", new BigDecimal("30"));
        stockIn(tenant, rice.id(), new StockInRequest(1, new BigDecimal("42000"), null, "BAG", tony.id(), null, null));
        String date = LocalDate.now().minusDays(2).toString();

        ResponseEntity<ImportSessionResponse> created = restTemplate.exchange(
                "/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(date, "WB-77", tony.id(), List.of(
                        new DeliveryRequest.Line(rice.id(), "BAG:50", new BigDecimal("2.5"), null),
                        new DeliveryRequest.Line(onion.id(), "BASKET:30", new BigDecimal("3"), new BigDecimal("29000")))),
                        json(tenant)),
                ImportSessionResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ImportSessionResponse session = created.getBody();
        assertThat(session.kind().name()).isEqualTo("STOCK_IN");
        assertThat(session.originalFilename()).startsWith("Delivery from Tony Stores, ");
        assertThat(session.rowCount()).isEqualTo(2);
        assertThat(session.errorCount()).isZero();
        assertThat(session.delivery().invoiceNo()).isEqualTo("WB-77");

        List<ImportRowResponse> rows = rows(tenant, session.id());
        assertThat(rows).extracting(row -> row.normalized().get("received_date")).containsOnly(date);
        assertThat(rows).extracting(row -> row.normalized().get("vendor_name")).containsOnly("Tony Stores");

        ImportResultResponse result = restTemplate.exchange(
                "/api/imports/" + session.id() + "/commit", HttpMethod.POST, new HttpEntity<>(json(tenant)),
                ImportResultResponse.class).getBody();
        assertThat(result.movementsCreated()).isEqualTo(2);
        assertThat(get(tenant, rice.id()).quantityOnHand()).as("1 + 2.5 bags of 50").isEqualTo(175);
        assertThat(get(tenant, rice.id()).costPrice()).as("a blank price is the last one: N840 a kg").isEqualByComparingTo("840");
        assertThat(get(tenant, onion.id()).quantityOnHand()).isEqualTo(90);
    }

    @Test
    void aTypedDeliveryWithNothingInItOrAFutureDateIsRefused() {
        TenantLoginResponse tenant = signup("Empty Delivery Co");
        ProductResponse rice = product(tenant, "Rice", "ED-RICE", "KG", null, null);

        ResponseEntity<String> empty = restTemplate.exchange("/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(null, null, null, List.of()), json(tenant)), String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> future = restTemplate.exchange("/api/imports/delivery", HttpMethod.POST,
                new HttpEntity<>(new DeliveryRequest(LocalDate.now().plusDays(3).toString(), null, null,
                        List.of(new DeliveryRequest.Line(rice.id(), "KG", BigDecimal.ONE, null))), json(tenant)),
                String.class);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(future.getBody()).contains("can't be in the future");
    }

    // ------------------------------------------------------------------ 2.3

    /**
     * Download my products, upload it back untouched: nothing to create, every row an update,
     * no warnings - and nothing about the products changes.
     */
    @Test
    void anUnchangedDownloadUploadsBackAsQuietUpdates() {
        TenantLoginResponse tenant = signup("Unchanged Export Co");
        CompanyVendorResponse tony = vendor(tenant, "Tony Stores");
        ProductResponse rice = product(tenant, "Rice (Mama Gold)", "UE-RICE", "KG", "BAG", new BigDecimal("50"));
        stockIn(tenant, rice.id(), new StockInRequest(4, new BigDecimal("42000"), null, "BAG", tony.id(), null, null));
        product(tenant, "Soap", "UE-SOAP", "PIECE", null, null);

        byte[] export = export(tenant);
        ImportSessionResponse session = upload(tenant, export);

        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).as("today's stock and price on every row is not a change").isZero();
        assertThat(session.unmappedHeaders()).isEmpty();
        Map<String, Object> preview = preview(tenant, session.id());
        assertThat(preview.toString()).contains("2 existing products").doesNotContain("new product");

        commit(tenant, session.id());
        ProductResponse after = get(tenant, rice.id());
        assertThat(after.quantityOnHand()).isEqualTo(200);
        assertThat(after.costPrice()).isEqualByComparingTo("840");
        assertThat(after.packagingSize()).isEqualByComparingTo("50");
        assertThat(after.preferredVendorName()).isEqualTo("Tony Stores");
    }

    /**
     * Edited and uploaded back: the Ref finds each product even after its code changed, changes
     * land, a stock change is ignored out loud, a new row with no Ref is a new product - and undo
     * puts the edited products back.
     */
    @Test
    void anEditedDownloadUpdatesByRefAndUndoPutsItBack() {
        TenantLoginResponse tenant = signup("Edited Export Co");
        ProductResponse rice = product(tenant, "Rice", "EE-RICE", "KG", "BAG", new BigDecimal("50"));
        product(tenant, "Soap", "EE-SOAP", "PIECE", null, null);

        byte[] edited = editExport(export(tenant), rows -> {
            Map<String, String> riceRow = rows.stream().filter(r -> "EE-RICE".equals(r.get("Your code *"))).findFirst().orElseThrow();
            riceRow.put("Product name *", "Rice (Mama Gold)");
            riceRow.put("Your code *", "RICE-50");
            riceRow.put("Category", "Grains");
            riceRow.put("How many you have now", "9");
            riceRow.put("Warn me when I have", "2");
            Map<String, String> fresh = new java.util.LinkedHashMap<>();
            fresh.put("Product name *", "Beans");
            fresh.put("Your code *", "EE-BEANS");
            fresh.put("Size of one", "kg");
            rows.add(fresh);
        });
        ImportSessionResponse session = upload(tenant, edited);

        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).as("only the stock change is questioned").isEqualTo(1);
        commit(tenant, session.id());

        ProductResponse after = get(tenant, rice.id());
        assertThat(after.name()).isEqualTo("Rice (Mama Gold)");
        assertThat(after.sku()).isEqualTo("RICE-50");
        assertThat(after.categoryName()).isEqualTo("Grains");
        assertThat(after.lowStockThreshold()).as("2 bags").isEqualTo(100);
        assertThat(after.quantityOnHand()).as("stock is not changed from this sheet").isZero();

        restTemplate.exchange("/api/imports/" + session.id() + "/undo", HttpMethod.POST,
                new HttpEntity<>(json(tenant)), String.class);
        ProductResponse undone = get(tenant, rice.id());
        assertThat(undone.name()).isEqualTo("Rice");
        assertThat(undone.sku()).isEqualTo("EE-RICE");
        assertThat(undone.categoryId()).isNull();
    }

    /** A row whose code already exists but carries no Ref is still refused - it isn't a known edit. */
    @Test
    void aCodeThatExistsWithoutARefIsRefusedWithTheWayToEditIt() {
        TenantLoginResponse tenant = signup("No Ref Co");
        product(tenant, "Rice", "NR-RICE", "KG", null, null);

        ImportSessionResponse session = upload(tenant,
                "name,sku,unit_of_measure\nRice,NR-RICE,KG\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "products.csv");

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(rows(tenant, session.id()).get(0).errors())
                .anySatisfy(error -> assertThat(error.message()).contains("Download my products"));
    }

    // ------------------------------------------------------------------ helpers

    private interface RowEdit {
        void apply(List<Map<String, String>> rows);
    }

    /** The export's data rows as header → text, edited, and written back into the same workbook. */
    private byte[] editExport(byte[] export, RowEdit edit) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export))) {
            Sheet sheet = workbook.getSheet("Products");
            List<String> headers = new ArrayList<>();
            for (Cell cell : sheet.getRow(0)) {
                headers.add(cell.getStringCellValue());
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                Map<String, String> values = new java.util.LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row == null ? null : row.getCell(c);
                    values.put(headers.get(c), cell == null ? "" : formatter.formatCellValue(cell).replace(",", ""));
                }
                rows.add(values);
            }
            edit.apply(rows);
            for (int i = sheet.getLastRowNum(); i >= 2; i--) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    sheet.removeRow(row);
                }
            }
            int index = 2;
            for (Map<String, String> values : rows) {
                Row row = sheet.createRow(index++);
                for (int c = 0; c < headers.size(); c++) {
                    String value = values.get(headers.get(c));
                    if (value != null && !value.isEmpty()) {
                        row.createCell(c).setCellValue(value);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<DeliveryLineResponse> deliveryLines(TenantLoginResponse tenant, String query) {
        return restTemplate.exchange("/api/imports/delivery-lines" + query, HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<List<DeliveryLineResponse>>() {}).getBody();
    }

    private byte[] export(TenantLoginResponse tenant) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(json(tenant)), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ImportSessionResponse upload(TenantLoginResponse tenant, byte[] content) {
        return upload(tenant, content, "my-products.xlsx");
    }

    private ImportSessionResponse upload(TenantLoginResponse tenant, byte[] content, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, new HttpHeaders()));
        body.add("kind", "PRODUCT_CATALOG");
        body.add("mode", "CREATE_ONLY");
        HttpHeaders headers = json(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers), ImportSessionResponse.class);
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

    private Map<String, Object> preview(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate.exchange("/api/imports/" + sessionId + "/preview", HttpMethod.GET,
                new HttpEntity<>(json(tenant)), new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
    }

    private void commit(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<String> response = restTemplate.exchange("/api/imports/" + sessionId + "/commit",
                HttpMethod.POST, new HttpEntity<>(json(tenant)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private ProductResponse get(TenantLoginResponse tenant, UUID id) {
        return restTemplate.exchange("/api/products/" + id, HttpMethod.GET, new HttpEntity<>(json(tenant)),
                ProductResponse.class).getBody();
    }

    private void stockIn(TenantLoginResponse tenant, UUID productId, StockInRequest request) {
        ResponseEntity<String> response = restTemplate.exchange("/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST, new HttpEntity<>(request, json(tenant)), String.class);
        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
    }

    private CompanyVendorResponse vendor(TenantLoginResponse tenant, String name) {
        return restTemplate.exchange("/api/company-vendors", HttpMethod.POST,
                new HttpEntity<>(new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null, null,
                        null, null, null), json(tenant)),
                CompanyVendorResponse.class).getBody();
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
        ResponseEntity<ProductResponse> response = restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
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
