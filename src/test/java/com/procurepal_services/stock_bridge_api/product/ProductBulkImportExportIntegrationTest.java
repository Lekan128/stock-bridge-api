package com.procurepal_services.stock_bridge_api.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
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
    // The company (non-seller) template's column set: same as HEADERS but with unit_price
    // dropped entirely, plus the unit_of_measure/packaging_unit/packaging_size trio appended at
    // the end - see ProductExcelService.ALL_HEADER_NAMES.
    private static final List<String> COMPANY_HEADERS = List.of(
            "name", "sku", "description", "cost_price", "quantity_on_hand", "low_stock_threshold",
            "unit_of_measure", "packaging_unit", "packaging_size");
    // The seller template's full column set. V20 appends the vendor trio (vendor_name,
    // vendor_sku, is_preferred_vendor) per BULK_IMPORT_CONTRACT.md section 5 and
    // BULK_IMPORT_DESIGN.md section 7.1 - the first ten are unchanged and in the same order, which
    // is the column-stability promise this test's containsExactly() is really guarding.
    private static final List<String> FULL_HEADERS = List.of(
            "name", "sku", "description", "unit_price", "cost_price", "quantity_on_hand", "low_stock_threshold",
            "unit_of_measure", "packaging_unit", "packaging_size",
            "vendor_name", "vendor_sku", "is_preferred_vendor");

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
            Row header = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < COMPANY_HEADERS.size(); i++) {
                assertThat(header.getCell(i).getStringCellValue()).isEqualTo(COMPANY_HEADERS.get(i));
            }
        }
    }

    /**
     * A company (non-seller) has no selling price at all - see ProductManagementService.create
     * and UnitPriceRequiredException - so its downloaded template must not even show a
     * unit_price column, rather than showing it as optional and inviting the question "should I
     * fill this in?". unit_of_measure/packaging_unit/packaging_size are appended for every
     * tenant kind, with example values demonstrating the three-field model.
     */
    @Test
    void companyTemplateHasNoUnitPriceColumnButHasUnitOfMeasureColumns() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(signup("Company Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> headerNames = readHeaderNames(response.getBody());
        assertThat(headerNames).doesNotContain("unit_price");
        assertThat(headerNames).containsSequence("unit_of_measure", "packaging_unit", "packaging_size");
    }

    /**
     * A marketplace seller's template keeps unit_price exactly as before - required, present -
     * and also gains the unit_of_measure/packaging_unit/packaging_size columns every tenant
     * kind gets.
     */
    @Test
    void vendorTemplateStillHasUnitPriceColumn() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(createVendor("Vendor Template Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> headerNames = readHeaderNames(response.getBody());
        assertThat(headerNames).containsExactly(FULL_HEADERS.toArray(new String[0]));
    }

    /**
     * The template's example rows demonstrate the full three-field model - a "50kg bag" (base
     * unit + packaging unit + packaging size all set) and a "carton of 24 pieces" (same shape,
     * different units) - so a user can infer the model from the examples alone. See
     * ProductExcelService.exampleRowOne/exampleRowTwo.
     */
    @Test
    void templateExampleRowsDemonstrateThreeFieldModel() throws IOException {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/template", HttpMethod.GET,
                new HttpEntity<>(authHeaders(signup("Example Rows Co"))), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getBody()))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<String> headerNames = readHeaderNames(response.getBody());
            int uomCol = headerNames.indexOf("unit_of_measure");
            int packagingUnitCol = headerNames.indexOf("packaging_unit");
            int packagingSizeCol = headerNames.indexOf("packaging_size");

            Row exampleOne = sheet.getRow(1);
            assertThat(exampleOne.getCell(uomCol).getStringCellValue()).isEqualTo("KG");
            assertThat(exampleOne.getCell(packagingUnitCol).getStringCellValue()).isEqualTo("BAG");
            assertThat(exampleOne.getCell(packagingSizeCol).getStringCellValue()).isEqualTo("50");

            Row exampleTwo = sheet.getRow(2);
            assertThat(exampleTwo.getCell(uomCol).getStringCellValue()).isEqualTo("PIECE");
            assertThat(exampleTwo.getCell(packagingUnitCol).getStringCellValue()).isEqualTo("CARTON");
            assertThat(exampleTwo.getCell(packagingSizeCol).getStringCellValue()).isEqualTo("24");
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
        // unit_price is a required header only for a seller - see
        // ProductManagementService.bulkUpload/ProductExcelService.requiredHeadersFor - so this
        // check for "the header is missing" needs a vendor tenant, not a company.
        TenantLoginResponse admin = createVendor("Missing Header Co");
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
        createProduct(admin, new CreateProductRequest(
                "Existing", "DUPEX-1", null, new BigDecimal("5.00"), null, null, null, null, null));

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
    void trailingRowsPaddedWithZeroWidthSpacesAreTreatedAsBlankAndSkipped() {
        TenantLoginResponse admin = signup("Ghost Rows Co");
        byte[] file = workbookWithGhostRows(
                List.<Object[]>of(new Object[] {"Widget", "GHOST-1", "A widget", 9.99, 4.5, 10, 2}), 5);

        ResponseEntity<BulkUploadResponse> response = upload(admin, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BulkUploadResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.createdCount()).isEqualTo(1);
        assertThat(body.products()).extracting(ProductResponse::sku).containsExactly("GHOST-1");
    }

    /**
     * Mirrors the real-world file that motivated this test: a spreadsheet app
     * dragged formatting/fill past the last real row, leaving trailing rows
     * whose cells all contain a zero-width space (U+200B) instead of being
     * truly empty.
     */
    private byte[] workbookWithGhostRows(List<Object[]> rows, int ghostRowCount) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                header.createCell(i).setCellValue(HEADERS.get(i));
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
            for (int g = 0; g < ghostRowCount; g++) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < HEADERS.size(); i++) {
                    row.createCell(i).setCellValue("​");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * A company can bulk-upload with no unit_price column at all - see
     * ProductExcelService.requiredHeadersFor - and quantity_on_hand still defaults to 0 when
     * omitted (regression: must not have broken while wiring the isSeller flag through parse(),
     * nor while adding the packaging_unit column and its cross-field validation).
     */
    @Test
    void companyBulkUploadSucceedsWithoutUnitPriceColumnAndQuantityStillDefaultsToZero() {
        TenantLoginResponse company = signup("No Price Bulk Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.of(
                        new Object[] {"Widget", "NOPRICE-1", "A widget", 4.5, 10, 2, "KG", "BAG", 50},
                        new Object[] {"Gadget", "NOPRICE-2", "A gadget", null, null, null, null, null, null}));

        ResponseEntity<BulkUploadResponse> response = upload(company, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        BulkUploadResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.createdCount()).isEqualTo(2);

        ProductResponse widget =
                body.products().stream().filter(p -> p.sku().equals("NOPRICE-1")).findFirst().orElseThrow();
        assertThat(widget.unitPrice()).isNull();
        assertThat(widget.unitOfMeasure()).isEqualTo("KG");
        assertThat(widget.packagingUnit()).isEqualTo("BAG");
        assertThat(widget.packagingSize()).isEqualByComparingTo("50");

        ProductResponse gadget =
                body.products().stream().filter(p -> p.sku().equals("NOPRICE-2")).findFirst().orElseThrow();
        assertThat(gadget.unitPrice()).isNull();
        assertThat(gadget.quantityOnHand()).isZero();
        assertThat(gadget.unitOfMeasure()).isNull();
        assertThat(gadget.packagingUnit()).isNull();
        assertThat(gadget.packagingSize()).isNull();
    }

    /**
     * A seller's product IS a listing, so unit_price stays required per-row even though the
     * header column is present - see UnitPriceRequiredException for the same rule on the
     * manual create/update path.
     */
    @Test
    void vendorBulkUploadMissingUnitPriceValueIsRejectedWithClearRowError() {
        TenantLoginResponse vendor = createVendor("Vendor No Price Co");
        byte[] file = workbookWithHeaders(
                FULL_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "VNOPRICE-1", "A widget", null, 4.5, 10, 2, null, null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(vendor, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("unit_price") && e.message().equalsIgnoreCase("is required"));
    }

    /**
     * unitOfMeasure/packagingUnit/packagingSize round-trip through the manual create endpoint
     * and back out through export - both are open to a buying company exactly as much as a
     * seller, unlike unitPrice. The export spreadsheet's unit_of_measure/packaging_unit/
     * packaging_size columns (see ProductExcelService.ALL_HEADER_NAMES) read the BASE unit, the
     * PACKAGING unit and the packaging SIZE respectively.
     */
    @Test
    void unitOfMeasurePackagingUnitAndPackagingSizeRoundTripThroughCreateAndExport() {
        TenantLoginResponse company = signup("UOM Export Co");
        createProduct(company, new CreateProductRequest(
                "Rice Bag", "UOM-EXPORT-1", null, null, null, "kg", "bag", new BigDecimal("25.5"), null));

        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/products/export", HttpMethod.GET, new HttpEntity<>(authHeaders(company)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Object[] uomPackagingUnitAndSize = readUnitOfMeasurePackagingUnitAndSize(response.getBody(), "UOM-EXPORT-1");
        // Normalized to the enum's uppercase code, not the "kg"/"bag" casing that was submitted.
        assertThat(uomPackagingUnitAndSize[0]).isEqualTo("KG");
        assertThat(uomPackagingUnitAndSize[1]).isEqualTo("BAG");
        assertThat(uomPackagingUnitAndSize[2]).isEqualTo(25.5);
    }

    /**
     * The full three-field "50kg bag" model round-trips through a fresh bulk upload, including
     * a decimal packaging_size (e.g. a half-bag) - the business requirement is explicit that
     * packaging_size is not integer-only.
     */
    @Test
    void unitOfMeasurePackagingUnitAndPackagingSizeAllSetUploadSucceeds() {
        TenantLoginResponse company = signup("UOM Upload Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Palm Oil", "UOM-UPLOAD-1", "desc", null, null, null, "kg", "drum", 2.5}));

        ResponseEntity<BulkUploadResponse> response = upload(company, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = response.getBody().products().stream()
                .filter(p -> p.sku().equals("UOM-UPLOAD-1"))
                .findFirst()
                .orElseThrow();
        assertThat(product.unitOfMeasure()).isEqualTo("KG");
        assertThat(product.packagingUnit()).isEqualTo("DRUM");
        assertThat(product.packagingSize()).isEqualByComparingTo("2.5");
    }

    /**
     * unit_of_measure may always stand alone with no packaging at all (a product sold loose) -
     * one-directional, mirroring PackagingRequiresUnitOfMeasureException on the manual path.
     */
    @Test
    void onlyUnitOfMeasureProvidedUploadsSuccessfullyWithNullPackaging() {
        TenantLoginResponse company = signup("UOM Alone Upload Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Cooking Oil", "UOM-LOOSE-1", "desc", null, null, null, "liter", null, null}));

        ResponseEntity<BulkUploadResponse> response = upload(company, file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = response.getBody().products().stream()
                .filter(p -> p.sku().equals("UOM-LOOSE-1"))
                .findFirst()
                .orElseThrow();
        assertThat(product.unitOfMeasure()).isEqualTo("LITER");
        assertThat(product.packagingUnit()).isNull();
        assertThat(product.packagingSize()).isNull();
    }

    @Test
    void invalidUnitOfMeasureCodeIsRejectedWithRowErrorNamingTheValue() {
        TenantLoginResponse company = signup("Bad UOM Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "BADUOM-1", null, null, null, null, "NOT-A-UNIT", null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("unit_of_measure")
                        && e.message().contains("NOT-A-UNIT") && e.message().toLowerCase().contains("not a recognized"));
    }

    /**
     * "BAG" resolves against the fixed catalog, but as a PACKAGING-role code - submitting it as
     * unit_of_measure must be rejected with the same "not a recognized unit of measure" row
     * error as a code that is not on the list at all, mirroring
     * ProductManagementService.resolveUnitOfMeasure/InvalidUnitOfMeasureException.
     */
    @Test
    void packagingRoleCodeSubmittedAsUnitOfMeasureIsRejected() {
        TenantLoginResponse company = signup("Wrong Role UOM Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "WRONGROLE-UOM-1", null, null, null, null, "BAG", null, null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("unit_of_measure")
                        && e.message().contains("BAG") && e.message().toLowerCase().contains("not a recognized"));
    }

    /**
     * "KG" resolves against the fixed catalog, but as a BASE-role code - submitting it as
     * packaging_unit must be rejected, mirroring
     * ProductManagementService.resolvePackagingUnit/InvalidUnitOfMeasureException("...",
     * "packaging unit"). unit_of_measure is also supplied here so this row isolates the role
     * check from the separate "packaging requires unit_of_measure" rule.
     */
    @Test
    void baseRoleCodeSubmittedAsPackagingUnitIsRejected() {
        TenantLoginResponse company = signup("Wrong Role Packaging Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "WRONGROLE-PKG-1", null, null, null, null, "PIECE", "KG", 5}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("packaging_unit")
                        && e.message().contains("KG") && e.message().toLowerCase().contains("not a recognized packaging unit"));
    }

    /**
     * packaging_unit and packaging_size present but unit_of_measure blank must be rejected -
     * mirrors PackagingRequiresUnitOfMeasureException on the manual path. Presence is checked
     * from the raw unit_of_measure cell, not resolved validity, so both packaging fields get
     * their own "requires unit_of_measure" error here (the cell truly is blank).
     */
    @Test
    void packagingProvidedWithoutUnitOfMeasureIsRejected() {
        TenantLoginResponse company = signup("Packaging No UOM Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "PKGNOUOM-1", null, null, null, null, null, "BAG", 50}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        List<ProductRowError> errors = response.getBody();
        assertThat(errors).isNotNull();
        assertThat(errors).anyMatch(e -> e.row() == 2 && e.column().equals("packaging_unit")
                && e.message().toLowerCase().contains("unit_of_measure"));
        assertThat(errors).anyMatch(e -> e.row() == 2 && e.column().equals("packaging_size")
                && e.message().toLowerCase().contains("unit_of_measure"));
    }

    /**
     * packaging_unit/packaging_size travel together - either both present or neither - same
     * symmetric pairing rule as ProductManagementService.requirePackagingUnitAndSizePaired.
     * unit_of_measure is set here so this row isolates the pairing check from the separate
     * "packaging requires unit_of_measure" rule.
     */
    @Test
    void packagingUnitWithoutPackagingSizeIsRejectedPerRow() {
        TenantLoginResponse company = signup("Packaging Unit Alone Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "PUALONE-1", null, null, null, null, "KG", "BAG", null}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("packaging_unit")
                        && e.message().toLowerCase().contains("packaging_size"));
    }

    @Test
    void packagingSizeWithoutPackagingUnitIsRejectedPerRow() {
        TenantLoginResponse company = signup("Packaging Size Alone Co");
        byte[] file = workbookWithHeaders(
                COMPANY_HEADERS,
                List.<Object[]>of(new Object[] {"Widget", "PSALONE-1", null, null, null, null, "KG", null, 5}));

        ResponseEntity<List<ProductRowError>> response = restTemplate.exchange(
                "/api/products/bulk-upload",
                HttpMethod.POST,
                multipartFile(company, file),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .anyMatch(e -> e.row() == 2 && e.column().equals("packaging_size")
                        && e.message().toLowerCase().contains("packaging_unit"));
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
            Row header = workbook.getSheetAt(0).getRow(0);
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
     * unit_of_measure (column 7), packaging_unit (column 8) and packaging_size (column 9) for
     * the exported row matching sku (column 1), read out while the workbook is still open -
     * export always writes the full column set (see ProductExcelService.exportProducts), so
     * these indices are fixed regardless of tenant kind.
     */
    private Object[] readUnitOfMeasurePackagingUnitAndSize(byte[] file, String sku) {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null && row.getCell(1) != null && sku.equals(row.getCell(1).getStringCellValue())) {
                    Cell uomCell = row.getCell(7);
                    Cell packagingUnitCell = row.getCell(8);
                    Cell sizeCell = row.getCell(9);
                    String uom = uomCell == null ? null : uomCell.getStringCellValue();
                    String packagingUnit = packagingUnitCell == null ? null : packagingUnitCell.getStringCellValue();
                    Double size = sizeCell == null ? null : sizeCell.getNumericCellValue();
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
