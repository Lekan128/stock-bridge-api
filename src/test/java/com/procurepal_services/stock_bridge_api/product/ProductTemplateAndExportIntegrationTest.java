package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The two ends of the catalog spreadsheet over the real HTTP + Spring Security filter chain: the
 * template a tenant downloads and the export they get back out. See AuthIntegrationTest for why
 * local Postgres over Testcontainers.
 *
 * <p>Filling a sheet in and sending it back is no longer this class's business. The single upload
 * path is {@code POST /api/imports} with its review, confirm, commit and undo - see
 * BulkImportRoundTripIntegrationTest for the whole journey and BulkImportSessionIntegrationTest for
 * the row-level rules. The old {@code POST /api/products/bulk-upload} shim, which validated a file
 * in one shot and either created everything or nothing, was removed in BULK_IMPORT_CX_PLAN.md task
 * 2.3; what it used to assert - an unrecognized unit, a code already taken, a code repeated in the
 * file, ghost rows padded with zero-width spaces - now lives with the pipeline that does the work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductTemplateAndExportIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    // The company (non-seller) template's column set - the product sheet in plain words
    // (BULK_IMPORT_CX_PLAN.md task 1.6): identity, then how you count it, then how much, then
    // supplier. Selling price is dropped entirely.
    private static final List<String> COMPANY_HEADERS = List.of(
            "Product name *", "Your code *", "Comes in", "Size of one", "Supplier",
            "How many you have now", "Price you pay for one (₦)", "Warn me when I have",
            "Category", "Notes", "Supplier's code for it", "Barcode");
    // The seller template's full column set - the same, plus the selling price they list at.
    private static final List<String> FULL_HEADERS = List.of(
            "Product name *", "Your code *", "Comes in", "Size of one", "Supplier",
            "How many you have now", "Price you pay for one (₦)", "Selling price (₦) *", "Warn me when I have",
            "Category", "Notes", "Supplier's code for it", "Barcode");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void templateDownloadsAndHasExpectedHeaders() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET, new HttpEntity<>(authHeaders(signup("Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            Row header = workbook.getSheet("Products").getRow(0);
            for (int i = 0; i < COMPANY_HEADERS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue()).isEqualTo(COMPANY_HEADERS.get(i));
            }
        }
    }

    /**
     * A company (non-seller) has no selling price at all - see ProductManagementService.create
     * and UnitPriceRequiredException - so its downloaded template must not even show the column,
     * rather than showing it as optional and inviting the question "should I fill this in?".
     */
    @Test
    void companyTemplateHasNoSellingPriceColumnButStillAsksHowItIsCounted() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(signup("Company Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> headerNames = readHeaderNames(response.getBody());
        assertThat(headerNames).doesNotContain("Selling price (₦) *");
        assertThat(headerNames).containsSequence("Comes in", "Size of one");
    }

    /**
     * A marketplace seller's product IS a listing, so their template keeps the selling price -
     * required, present - alongside every column a company gets.
     */
    @Test
    void vendorTemplateStillHasTheSellingPriceColumn() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(createVendor("Vendor Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> headerNames = readHeaderNames(response.getBody());
        assertThat(headerNames).containsExactly(FULL_HEADERS.toArray(new String[0]));
    }

    /**
     * The example rows teach the model by showing it: a 50 kg bag, a pack of twelve 750 ml
     * bottles written the way the invoice writes it, and something bought loose with no pack at
     * all. A user should be able to infer what to type from the examples alone.
     *
     * <p>The unit cells carry LABELS, not codes - UNIT_UX_CONTRACT.md section 7, non-negotiable 4.
     * An example row is the one place a user learns what to type, so an example reading "KG" would
     * teach them our vocabulary rather than their own.
     */
    @Test
    void templateExampleRowsDemonstrateThreeFieldModel() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(signup("Example Rows Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            Sheet sheet = workbook.getSheet("Products");
            List<String> headerNames = readHeaderNames(response.getBody());
            int packCol = headerNames.indexOf("Comes in");
            int containsCol = headerNames.indexOf("Size of one");
            // Task 1.6: starting stock and price are back, as optional columns.
            assertThat(headerNames).contains("How many you have now", "Price you pay for one (₦)");

            // Rows 0-2 are the headers, the guidance row and the "these are examples" note.
            Row rice = sheet.getRow(3);
            assertThat(rice.getCell(packCol).getStringCellValue()).isEqualTo("Bag");
            assertThat(rice.getCell(containsCol).getStringCellValue()).isEqualTo("50 kg");

            // A pack of twelve 750 ml bottles, written the way it is on the invoice.
            Row water = sheet.getRow(4);
            assertThat(water.getCell(packCol).getStringCellValue()).isEqualTo("Pack");
            assertThat(water.getCell(containsCol).getStringCellValue()).isEqualTo("12 x 750 ml");

            // Bought loose - no pack, and the size is just the unit.
            Row biro = sheet.getRow(5);
            assertThat(biro.getCell(packCol)).isNull();
            assertThat(biro.getCell(containsCol).getStringCellValue()).isEqualTo("piece");
        }
    }

    /**
     * The stock unit, the pack and the units per pack round-trip from the manual create endpoint
     * out through the export - all three are open to a buying company exactly as much as to a
     * seller, unlike the selling price.
     *
     * <p>The export writes them as LABELS in one phrase - "Bag", "25.5 kg" - because the export and
     * the template are the same file to a user who exports, edits and re-uploads, and a cell
     * reading "KG" on one and "Kilogram (kg)" on the other would be two vocabularies in one
     * workflow. It still round-trips: the import resolves the display label back to the same unit.
     */
    @Test
    void theStockUnitPackAndPackSizeRoundTripThroughCreateAndExport() {
        TenantLoginResponse company = signup("UOM Export Co");
        createProduct(company, new CreateProductRequest(
                "Rice Bag", "UOM-EXPORT-1", null, null, null, "kg", "bag", new BigDecimal("25.5"), null));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(company)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object[] uomPackagingUnitAndSize = readUnitOfMeasurePackagingUnitAndSize(response.getBody(), "UOM-EXPORT-1");
        // The stored value is normalized to the enum's uppercase code; the SHEET shows the label.
        assertThat(uomPackagingUnitAndSize[0]).isEqualTo("Kilogram (kg)");
        assertThat(uomPackagingUnitAndSize[1]).isEqualTo("Bag");
        assertThat(uomPackagingUnitAndSize[2]).isEqualTo(25.5);
    }

    @Test
    void exportContainsOnlyCallersActiveProducts() {
        TenantLoginResponse tenantA = signup("Export Tenant A " + UUID.randomUUID());
        TenantLoginResponse tenantB = signup("Export Tenant B " + UUID.randomUUID());
        createProduct(tenantA, new CreateProductRequest(
                "A Item", "EXPORT-A", null, new BigDecimal("1.00"), null, null, null, null, null));
        createProduct(tenantB, new CreateProductRequest(
                "B Item", "EXPORT-B", null, new BigDecimal("1.00"), null, null, null, null, null));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(tenantA)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> skusInExport = readSkuColumn(response.getBody());
        assertThat(skusInExport).contains("EXPORT-A").doesNotContain("EXPORT-B");
    }

    private List<String> readHeaderNames(byte[] file) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Row header = workbook.getSheet("Products").getRow(0);
            List<String> names = new java.util.ArrayList<>();
            for (Cell cell : header) {
                names.add(cell.getStringCellValue());
            }
            return names;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The stock unit, the pack and the units per pack for the exported row matching sku, read out
     * while the workbook is still open.
     */
    private Object[] readUnitOfMeasurePackagingUnitAndSize(byte[] file, String sku) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheet("Products");
            // Resolved by header NAME, not by a hardcoded index: the column set grows (V20 added
            // the vendor trio, the unit remediation added opening_stock_counted_in), and an index
            // here turns every such addition into a puzzling type error three columns to the right.
            Row headerRow = sheet.getRow(0);
            List<String> exportHeaders = new java.util.ArrayList<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                exportHeaders.add(cell == null ? "" : cell.getStringCellValue());
            }
            int skuCol = exportHeaders.indexOf("Your code *");
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(skuCol) != null && sku.equals(row.getCell(skuCol).getStringCellValue())) {
                    // Section 15: the export writes one phrase - "25.5 kg" - so the unit and the
                    // size are read back out of it by the same parser the import uses.
                    Cell packagingUnitCell = row.getCell(exportHeaders.indexOf("Comes in"));
                    Cell containsCell = row.getCell(exportHeaders.indexOf("Size of one"));
                    String packagingUnit = packagingUnitCell == null ? null : packagingUnitCell.getStringCellValue();
                    var contents = containsCell == null
                            ? java.util.Optional.<com.procurepal_services.stock_bridge_api.product.unit.PackContents>empty()
                            : com.procurepal_services.stock_bridge_api.product.unit.PackContents.parse(
                                    containsCell.getStringCellValue());
                    String uom = contents.map(c -> c.unit().label()).orElse(null);
                    Double size = contents.map(c -> c.packSize() == null ? null : c.packSize().doubleValue()).orElse(null);
                    return new Object[] {uom, packagingUnit, size};
                }
            }
            throw new AssertionError("No exported row found for sku " + sku);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private TenantLoginResponse createVendor(String namePrefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = (namePrefix + "-" + suffix).toLowerCase().replace(' ', '-');
        UUID clientId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO clients (id, name, slug, admin_contact_email, client_type, is_active) "
                        + "VALUES (?, ?, ?, ?, 'VENDOR', TRUE)",
                clientId,
                namePrefix + " " + suffix,
                slug,
                "vendor-" + suffix + "@example.com");

        String username = "vendor-" + suffix;
        jdbc.update(
                "INSERT INTO users (id, client_id, username, password_hash, role_id, is_active, is_root) "
                        + "VALUES (?, ?, ?, ?, (SELECT id FROM roles WHERE name = 'VENDOR'), TRUE, TRUE)",
                UUID.randomUUID(),
                clientId,
                username,
                passwordEncoder.encode(PASSWORD));

        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login).as("vendor fixture must be able to log in").isNotNull();
        return login;
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
