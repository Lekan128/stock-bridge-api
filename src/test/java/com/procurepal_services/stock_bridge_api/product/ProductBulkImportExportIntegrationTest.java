package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.ProductRowError;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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
 * Exercises the real HTTP + Spring Security filter chain against the local
 * docker-compose Postgres - see AuthIntegrationTest for why local Postgres
 * over Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductBulkImportExportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final List<String> HEADERS =
            List.of("name", "sku", "description", "unit_price", "cost_price", "quantity_on_hand", "low_stock_threshold");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void templateDownloadsAndHasExpectedHeaders() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET, new HttpEntity<>(authHeaders(signup("Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue()).isEqualTo(HEADERS.get(i));
            }
        }
    }

    @Test
    void successfulBulkUploadCreatesAllRows() {
        TenantLoginResponse admin = signup("Bulk Upload Co");
        byte[] file = workbook(List.of(
                new Object[] {"Widget", "BULK-1", "A widget", 9.99, 4.5, 10, 2},
                new Object[] {"Gadget", "BULK-2", "A gadget", 19.99, null, null, null}));

        ResponseEntity<BulkUploadResponse> response = upload(admin, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BulkUploadResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.createdCount()).isEqualTo(2);
        List<String> skus = body.products().stream().map(ProductResponse::sku).toList();
        assertThat(skus).containsExactlyInAnyOrder("BULK-1", "BULK-2");
        ProductResponse gadget =
                body.products().stream().filter(p -> p.sku().equals("BULK-2")).findFirst().orElseThrow();
        assertThat(gadget.quantityOnHand()).isZero();
        assertThat(gadget.lowStockThreshold()).isNull();
    }

    @Test
    void missingRequiredColumnHeaderIsRejectedWithClearMessage() {
        TenantLoginResponse admin = signup("Missing Header Co");
        byte[] file = workbookWithHeaders(
                List.of("name", "sku", "description", "cost_price", "quantity_on_hand", "low_stock_threshold"),
                List.<Object[]>of(new Object[] {"Widget", "MH-1", "A widget", 4.5, 10, 2}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(admin, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<ProductRowError> errors = response.getBody();
        assertThat(errors).isNotNull();
        assertThat(errors).anyMatch(e -> e.column().equals("unit_price") && e.message().toLowerCase().contains("missing"));
    }

    @Test
    void duplicateSkuWithinFileIsRejected() {
        TenantLoginResponse admin = signup("Dup In File Co");
        byte[] file = workbook(List.of(
                new Object[] {"Widget", "DUPFILE-1", "A widget", 9.99, null, null, null},
                new Object[] {"Widget Two", "DUPFILE-1", "Another widget", 12.99, null, null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(admin, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 3 && e.column().equals("sku") && e.message().toLowerCase().contains("duplicate"));
    }

    @Test
    void duplicateSkuAgainstExistingProductIsRejected() {
        TenantLoginResponse admin = signup("Dup Existing Co");
        createProduct(admin, new CreateProductRequest("Existing", "DUPEX-1", null, new BigDecimal("5.00"), null, null));

        byte[] file = workbook(List.<Object[]>of(new Object[] {"New Product", "DUPEX-1", null, 9.99, null, null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(admin, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.column().equals("sku") && e.message().toLowerCase().contains("already exists"));
    }

    @Test
    void nonNumericPriceIsRejected() {
        TenantLoginResponse admin = signup("Bad Price Co");
        byte[] file = workbook(List.<Object[]>of(new Object[] {"Widget", "BADPRICE-1", null, "not-a-number", null, null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(admin, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).anyMatch(e -> e.row() == 2 && e.column().equals("unit_price"));
    }

    @Test
    void exportContainsOnlyCallersActiveProducts() {
        TenantLoginResponse tenantA = signup("Export Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Export Tenant B " + UUID.randomUUID());
        createProduct(tenantA, new CreateProductRequest("A Item", "EXPORT-A", null, new BigDecimal("1.00"), null, null));
        createProduct(tenantB, new CreateProductRequest("B Item", "EXPORT-B", null, new BigDecimal("1.00"), null, null));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(tenantA)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> skusInExport = readSkuColumn(response.getBody());
        assertThat(skusInExport).contains("EXPORT-A").doesNotContain("EXPORT-B");
    }

    private List<String> readSkuColumn(byte[] file) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> skus = new java.util.ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(1) != null) {
                    skus.add(row.getCell(1).getStringCellValue());
                }
            }
            return skus;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private byte[] workbook(List<Object[]> rows) {
        return workbookWithHeaders(HEADERS, rows);
    }

    private byte[] workbookWithHeaders(List<String> headers, List<Object[]> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == null) {
                        continue;
                    }
                    if (values[i] instanceof Number number) {
                        row.createCell(i).setCellValue(number.doubleValue());
                    } else {
                        row.createCell(i).setCellValue(values[i].toString());
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ResponseEntity<BulkUploadResponse> upload(TenantLoginResponse asAdmin, byte[] file) {
        return restTemplate.exchange(
                "/api/products/bulk-upload", HttpMethod.POST, multipartFile(asAdmin, file), BulkUploadResponse.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartFile(TenantLoginResponse asAdmin, byte[] file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ByteArrayResource fileResource = new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "products.xlsx";
            }
        };
        body.add("file", new HttpEntity<>(fileResource, filePartHeaders));

        HttpHeaders headers = authHeaders(asAdmin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private void createProduct(TenantLoginResponse asAdmin, CreateProductRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(request, productPartHeaders));

        HttpHeaders headers = authHeaders(asAdmin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        restTemplate.exchange(
                "/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
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
