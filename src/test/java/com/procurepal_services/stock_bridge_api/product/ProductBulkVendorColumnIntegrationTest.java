package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
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

        ImportResultResponse result = importCatalog(tenant, "products.csv", csv(
                "name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name,vendor_sku\n"
                        + "Rice 50kg,VND-RICE-50,42000,40,KG," + VENDOR_NAME + ",DN-RICE-50\n"));

        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.movementsCreated()).isEqualTo(1);
        ProductResponse product = productBySku(tenant, "VND-RICE-50");
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
            // stockIn seeded the line from the row's cost_price, and the supplier named on a
            // product's own row is its main supplier - there is no separate flag to honour.
            assertThat(line.lastCostPrice()).isEqualByComparingTo("42000");
            assertThat(line.isPreferred()).isTrue();
            assertThat(line.totalQuantityReceived()).isEqualTo(40);
        });
    }

    /**
     * A supplier name we do not have stays unattributed rather than being guessed at or invented -
     * "the spreadsheet named someone we do not know" is a question for the review screen's value
     * mapper, and an import committed without answering it must not answer it silently.
     */
    @Test
    void anUnknownSupplierNameLeavesTheStockUnattributedRatherThanInventingOne() {
        TenantLoginResponse tenant = signup("Unknown Vendor Co");
        createVendor(tenant, VENDOR_NAME);

        ImportResultResponse result = importCatalog(tenant, "products.csv", csv(
                "name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name\n"
                        + "Garri 25kg,VND-GARRI-25,18500,12,KG,Some Supplier We Have Never Heard Of\n"));

        assertThat(result.createdCount()).isEqualTo(1);
        // Left unanswered, the supplier question has a stated fallback - imported without a
        // supplier - rather than a supplier invented on the user's behalf.
        assertThat(result.vendorsCreated()).isZero();
        ProductResponse product = productBySku(tenant, "VND-GARRI-25");
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
        importCatalog(tenant, "products.csv", csv("name,sku,cost_price,quantity_on_hand,unit_of_measure,vendor_name\n"
                + "Rice 50kg,VNDEXP-RICE-50,42000,5,KG," + VENDOR_NAME + "\n"));

        ResponseEntity<byte[]> export = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(tenant)), byte[].class);

        assertThat(export.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export.getBody()))) {
            Sheet sheet = workbook.getSheet("Products");
            List<String> headers = headerNames(sheet);
            assertThat(headers).contains("Supplier", "Supplier's code for it");
            // The supplier on a product's row is its main supplier - there is no separate flag.
            assertThat(headers).noneMatch(header -> header.toLowerCase().contains("preferred"));

            Row exported = rowWithSku(sheet, "VNDEXP-RICE-50");
            assertThat(exported.getCell(headers.indexOf("Supplier")).getStringCellValue())
                    .isEqualTo(VENDOR_NAME);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A .csv import goes through the same engine and produces the same product as the .xlsx of
     * the same content - the format is the user's choice, not a different feature. Many ERPs export
     * nothing else, and POI cannot read it (BULK_IMPORT_DESIGN.md section 11).
     */
    @Test
    void aCsvImportProducesTheSameProductAsAnXlsxOne() {
        TenantLoginResponse csvTenant = signup("Csv Upload Co");
        TenantLoginResponse xlsxTenant = signup("Xlsx Upload Co");

        ImportResultResponse fromCsv = importCatalog(csvTenant, "products.csv", csv(
                "name,sku,cost_price,quantity_on_hand,unit_of_measure\nRice 50kg,FMT-RICE-50,42000,40,KG\n"));
        ImportResultResponse fromXlsx = importCatalog(xlsxTenant, "products.xlsx", xlsx(
                List.of("name", "sku", "cost_price", "quantity_on_hand", "unit_of_measure"),
                List.<Object[]>of(new Object[] {"Rice 50kg", "FMT-RICE-50", 42000, 40, "KG"})));

        assertThat(fromCsv.createdCount()).isEqualTo(1);
        assertThat(fromXlsx.createdCount()).isEqualTo(1);
        assertThat(fromCsv.movementsCreated()).isEqualTo(fromXlsx.movementsCreated());
        ProductResponse csvProduct = productBySku(csvTenant, "FMT-RICE-50");
        ProductResponse xlsxProduct = productBySku(xlsxTenant, "FMT-RICE-50");
        assertThat(csvProduct.name()).isEqualTo(xlsxProduct.name());
        assertThat(csvProduct.quantityOnHand()).isEqualTo(40).isEqualTo(xlsxProduct.quantityOnHand());
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
                new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null, null, null, null, null);
        ResponseEntity<CompanyVendorResponse> response = restTemplate.exchange(
                "/api/company-vendors", HttpMethod.POST, new HttpEntity<>(request, headers), CompanyVendorResponse.class);
        assertThat(response.getStatusCode()).as("vendor fixture must be creatable").isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /**
     * Upload to the import engine as a product catalog and commit it - the only way a file reaches
     * the catalog since the legacy {@code /api/products/bulk-upload} was removed.
     */
    private ImportResultResponse importCatalog(TenantLoginResponse tenant, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(resource, new HttpHeaders()));
        body.add("kind", "PRODUCT_CATALOG");
        body.add("mode", "CREATE_ONLY");

        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ImportSessionResponse> uploaded = restTemplate.exchange(
                "/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers), ImportSessionResponse.class);
        assertThat(uploaded.getStatusCode()).as("upload must answer 201").isEqualTo(HttpStatus.CREATED);
        assertThat(uploaded.getBody().errorCount()).as("the fixture file must be clean").isZero();

        ResponseEntity<ImportResultResponse> committed = restTemplate.exchange(
                "/api/imports/" + uploaded.getBody().id() + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);
        assertThat(committed.getStatusCode()).as("commit must answer 200").isEqualTo(HttpStatus.OK);
        return committed.getBody();
    }

    private ProductResponse productBySku(TenantLoginResponse tenant, String sku) {
        TestPage<ProductResponse> page = restTemplate
                .exchange(
                        "/api/products?search=" + sku + "&size=50",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<ProductResponse>>() {})
                .getBody();
        return page.content().stream()
                .filter(product -> sku.equals(product.sku()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No product with sku " + sku));
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TestPage<T>(List<T> content) {
    }
}
