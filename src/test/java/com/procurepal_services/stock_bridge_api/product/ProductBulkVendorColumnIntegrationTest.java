package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
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
 * The vendor columns, end to end through the real HTTP stack - see AuthIntegrationTest for why
 * local Postgres over Testcontainers.
 *
 * <p>Three things are only true if the whole chain works, and none of them can be proved by a unit
 * test: that the template a specific tenant downloads carries that tenant's own suppliers; that an
 * imported row naming one of them produces a real supplier line and a lot attributed to it rather
 * than an anonymous quantity; and that a name we do not recognise is left alone instead of being
 * guessed at.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductBulkVendorColumnIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String VENDOR_NAME = "Dangote Nigeria Plc";

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * BULK_IMPORT_DESIGN.md section 5.2's "the reason the template must become per-tenant": the
     * downloaded file has to contain THIS tenant's suppliers, in the hidden lookup sheet the
     * vendor_name dropdown reads from.
     */
    @Test
    void aTenantsOwnSuppliersAreInTheTemplateItDownloads() {
        TenantLoginResponse tenant = signup("Vendor Dropdown Co");
        createVendor(tenant, VENDOR_NAME);
        createVendor(tenant, "Ade Foods Ltd");

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET, new HttpEntity<>(authHeaders(tenant)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            int lookups = workbook.getSheetIndex("_lookups");
            assertThat(lookups).as("the hidden lookup sheet must be there").isNotNegative();
            assertThat(workbook.isSheetHidden(lookups)).isTrue();
            assertThat(workbook.getName("vendor_names")).as("the vendor_name dropdown's range").isNotNull();
            assertThat(allTextOf(workbook.getSheetAt(lookups))).contains(VENDOR_NAME, "Ade Foods Ltd");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The seam M1 left in {@code ProductManagementService.openingBalanceVendorId}, filled: an
     * imported row that names a supplier we know produces a real {@code ProductVendor} line with
     * the row's cost on it, and the opening-balance movement is attributed to that supplier rather
     * than to nobody. This is what turns section 3's "every imported quantity is a real lot" into
     * a lot that also has a supplier and a cost basis behind it.
     */
    @Test
    void anImportedRowNamingAKnownSupplierCreatesTheSupplierLineAndAttributesTheStock() {
        TenantLoginResponse tenant = signup("Vendor Import Co");
        CompanyVendorResponse vendor = createVendor(tenant, VENDOR_NAME);

        ResponseEntity<BulkUploadResponse> upload = upload(tenant, csv(
                "name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name,vendor_sku,is_preferred_vendor\n"
                        + "Rice 50kg,VND-RICE-50,42000,40,KG," + VENDOR_NAME + ",DN-RICE-50,TRUE\n"));

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = upload.getBody().products().get(0);
        assertThat(product.quantityOnHand()).isEqualTo(40);

        List<ProductVendorResponse> lines = restTemplate.exchange(
                        "/api/products/" + product.id() + "/vendors",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<List<ProductVendorResponse>>() {})
                .getBody();

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.companyVendorId()).isEqualTo(vendor.id());
            assertThat(line.companyVendorName()).isEqualTo(VENDOR_NAME);
            // stockIn seeded the line from the row's cost_price, and made it preferred because it
            // is the product's first - section 7.1's "cost_price gains a second meaning".
            assertThat(line.lastCostPrice()).isEqualByComparingTo("42000");
            assertThat(line.isPreferred()).isTrue();
            assertThat(line.totalQuantityReceived()).isEqualTo(40);
        });
    }

    /**
     * A supplier name we do not have stays unattributed rather than being guessed at or invented -
     * "the spreadsheet named someone we do not know" is a question for the review screen's value
     * mapper, not something to answer silently on a compatibility endpoint.
     */
    @Test
    void anUnknownSupplierNameLeavesTheStockUnattributedRatherThanInventingOne() {
        TenantLoginResponse tenant = signup("Unknown Vendor Co");
        createVendor(tenant, VENDOR_NAME);

        ResponseEntity<BulkUploadResponse> upload = upload(tenant, csv(
                "name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name\n"
                        + "Garri 25kg,VND-GARRI-25,18500,12,KG,Some Supplier We Have Never Heard Of\n"));

        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = upload.getBody().products().get(0);
        // The quantity still landed through the ledger - the vendor being unknown must not cost the
        // user their stock.
        assertThat(product.quantityOnHand()).isEqualTo(12);

        List<ProductVendorResponse> lines = restTemplate.exchange(
                        "/api/products/" + product.id() + "/vendors",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<List<ProductVendorResponse>>() {})
                .getBody();

        assertThat(lines).isEmpty();
    }

    /**
     * Export and template are the same column set, so "export, edit, upload back" is lossless -
     * including the supplier, which would otherwise silently empty itself on every round trip.
     */
    @Test
    void theExportCarriesTheSupplierBackOutAgain() {
        TenantLoginResponse tenant = signup("Vendor Export Co");
        createVendor(tenant, VENDOR_NAME);
        upload(tenant, csv("name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name\n"
                + "Rice 50kg,VNDEXP-RICE-50,42000,5,KG," + VENDOR_NAME + "\n"));

        ResponseEntity<byte[]> export = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(tenant)), byte[].class);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.getBody()))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headers = headerNames(sheet);
            assertThat(headers).containsSequence("vendor_name", "vendor_sku", "is_preferred_vendor");

            Row exported = rowWithSku(sheet, "VNDEXP-RICE-50");
            assertThat(exported.getCell(headers.indexOf("vendor_name")).getStringCellValue())
                    .isEqualTo(VENDOR_NAME);
            assertThat(exported.getCell(headers.indexOf("is_preferred_vendor")).getStringCellValue())
                    .isEqualTo("TRUE");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A .csv upload goes through the same endpoint and produces the same products as the .xlsx of
     * the same content - the format is the user's choice, not a different feature. Many ERPs export
     * nothing else, and POI cannot read it (BULK_IMPORT_DESIGN.md section 11).
     */
    @Test
    void aCsvUploadWorksThroughTheSameEndpointAsAnXlsxOne() {
        TenantLoginResponse csvTenant = signup("Csv Upload Co");
        TenantLoginResponse xlsxTenant = signup("Xlsx Upload Co");

        BulkUploadResponse fromCsv = upload(csvTenant, csv(
                        "name,sku,cost_price,quantity_on_hand,unit_of_measure\nRice 50kg,FMT-RICE-50,42000,40,KG\n"))
                .getBody();
        BulkUploadResponse fromXlsx = uploadFile(xlsxTenant, "products.xlsx", xlsx(
                        List.of("name", "sku", "cost_price", "quantity_on_hand", "unit_of_measure"),
                        List.<Object[]>of(new Object[] {"Rice 50kg", "FMT-RICE-50", 42000, 40, "KG"})))
                .getBody();

        assertThat(fromCsv.createdCount()).isEqualTo(1);
        assertThat(fromXlsx.createdCount()).isEqualTo(1);
        ProductResponse csvProduct = fromCsv.products().get(0);
        ProductResponse xlsxProduct = fromXlsx.products().get(0);
        assertThat(csvProduct.sku()).isEqualTo(xlsxProduct.sku());
        assertThat(csvProduct.quantityOnHand()).isEqualTo(xlsxProduct.quantityOnHand());
        assertThat(csvProduct.unitOfMeasure()).isEqualTo(xlsxProduct.unitOfMeasure());
        assertThat(csvProduct.costPrice()).isEqualByComparingTo(xlsxProduct.costPrice());
    }

    // ------------------------------------------------------------------------------ fixtures ----

    private byte[] csv(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] xlsx(List<String> headers, List<Object[]> rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (Object[] values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < values.length; i++) {
                    if (values[i] instanceof Number number) {
                        row.createCell(i).setCellValue(number.doubleValue());
                    } else if (values[i] != null) {
                        row.createCell(i).setCellValue(values[i].toString());
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

    private List<String> headerNames(Sheet sheet) {
        List<String> names = new ArrayList<>();
        for (Cell cell : sheet.getRow(0)) {
            names.add(cell.getStringCellValue());
        }
        return names;
    }

    private Row rowWithSku(Sheet sheet, String sku) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getCell(1) != null && sku.equals(row.getCell(1).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("No exported row for sku " + sku);
    }

    private List<String> allTextOf(Sheet sheet) {
        List<String> values = new ArrayList<>();
        for (Row row : sheet) {
            for (Cell cell : row) {
                values.add(cell.getStringCellValue());
            }
        }
        return values;
    }

    private CompanyVendorResponse createVendor(TenantLoginResponse tenant, String name) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        CompanyVendorRequest request =
                new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors", HttpMethod.POST, new HttpEntity<>(request, headers), CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).as("vendor fixture must be creatable").isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<BulkUploadResponse> upload(TenantLoginResponse tenant, byte[] content) {
        return uploadFile(tenant, "products.csv", content);
    }

    private ResponseEntity<BulkUploadResponse> uploadFile(
            TenantLoginResponse tenant, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(resource, new HttpHeaders()));

        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(
                "/api/products/bulk-upload", HttpMethod.POST, new HttpEntity<>(body, headers), BulkUploadResponse.class);
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
