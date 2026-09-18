package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.CommitPreviewResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PatchRowRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ValueMappingRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
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
 * The journey nobody had made in one go: download the generated template, fill it, upload that
 * exact file, repair a row, answer a question, confirm, commit, download the report, undo.
 *
 * <p>Every other test in the suite hands the engine a CSV it wrote itself, which is a legitimate
 * shortcut for testing the engine and a complete blind spot for testing the <em>product</em>. A
 * user never types a CSV. They click Download template, fill in the file we generated, and upload
 * that. Everything that can only break at the seam between the generator and the reader - a
 * header the generator spells differently from the field key the mapper expects, a pre-filled
 * value the parser cannot read back, an example row the sheet promises will be ignored - is
 * invisible until somebody does exactly this.
 *
 * <p>The .xlsx assertions are here too, on the same files: the two templates and the report are
 * reopened with POI and checked as documents, because "the endpoint answered 200 with some bytes"
 * is not the same claim as "the user can open this in Excel and it says the right things".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BulkImportRoundTripIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** The product sheet for a company that does not sell (BULK_IMPORT_CX_PLAN.md task 1.6). */
    private static final List<String> CATALOG_TEMPLATE_HEADERS = List.of(
            "Product name *", "Your code *", "Comes in", "Size of one", "Supplier",
            "How many you have now", "Price you pay for one (₦)", "Warn me when I have",
            "Category", "Notes", "Supplier's code for it", "Barcode");

    /** The stock sheet as it is downloaded today (BULK_IMPORT_CX_PLAN.md task 1.4). */
    private static final List<String> STOCK_IN_TEMPLATE_HEADERS = List.of(
            "Product", "Comes in", "How many arrived", "Price paid for one (₦)", "Last price paid (₦)",
            "Supplier", "Your code", "Date (if different)", "Ref");

    /**
     * Deliberately still spelled {@code quantity_on_hand}: this is a saved copy of the template as
     * it was before UNIT_UX_CONTRACT.md section 5.1 renamed the column, and every round trip below
     * therefore also exercises the permanent read alias that non-negotiable 8 requires.
     */
    private static final String CATALOG_CSV_HEADERS =
            "name,sku,description,cost_price,quantity_on_hand,low_stock_threshold,unit_of_measure,"
                    + "packaging_unit,packaging_size,vendor_name,vendor_sku,is_preferred_vendor\n";

    private final DataFormatter formatter = new DataFormatter();

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------- catalog template, end to end

    /**
     * The product sheet as a document: a help tab first, the columns in plain words, a guidance
     * row, a note, and three worked examples - and nothing else.
     */
    @Test
    void theProductTemplateIsAWellFormedSheetWithTheContractsColumnsAndItsExampleRows() {
        TenantLoginResponse tenant = signup("Template Shape Co");
        createVendor(tenant, "Dangote Nigeria Plc");

        try (XSSFWorkbook workbook = open(productTemplate(tenant))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("How to fill this in");
            Sheet sheet = workbook.getSheet("Products");
            assertThat(headersOf(sheet)).containsExactlyElementsOf(CATALOG_TEMPLATE_HEADERS);
            assertThat(cell(sheet, 1, "Product name *", CATALOG_TEMPLATE_HEADERS)).startsWith("ⓘ");
            assertThat(cell(sheet, 2, "Product name *", CATALOG_TEMPLATE_HEADERS)).startsWith("ⓘ").contains("examples");

            // Labels, never codes, and a pack of twelve 750 ml bottles the way the invoice says it.
            assertThat(cell(sheet, 3, "Product name *", CATALOG_TEMPLATE_HEADERS)).isEqualTo("Rice (Mama Gold)");
            assertThat(cell(sheet, 3, "Comes in", CATALOG_TEMPLATE_HEADERS)).isEqualTo("Bag");
            assertThat(cell(sheet, 3, "Size of one", CATALOG_TEMPLATE_HEADERS)).isEqualTo("50 kg");
            assertThat(cell(sheet, 4, "Comes in", CATALOG_TEMPLATE_HEADERS)).isEqualTo("Pack");
            assertThat(cell(sheet, 4, "Size of one", CATALOG_TEMPLATE_HEADERS)).isEqualTo("12 x 750 ml");
            assertThat(cell(sheet, 5, "Size of one", CATALOG_TEMPLATE_HEADERS)).isEqualTo("piece");
            assertThat(sheet.getLastRowNum()).as("headers, guidance, note and three examples").isEqualTo(5);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The whole catalog journey on the real file.
     *
     * <p>Three rows are added below the examples: one clean, one whose unit is a code we do not
     * know, and one naming a supplier the tenant does not have yet. That is not a
     * contrived file - it is the ordinary one, and it exercises the repair, the bulk-fix count and
     * the resolution card in a single pass.
     */
    @Test
    void aProductCatalogSurvivesDownloadFillUploadRepairResolveConfirmCommitReportAndUndo() {
        TenantLoginResponse tenant = signup("Catalog Round Trip Co");

        byte[] filled = fill(productTemplate(tenant), CATALOG_TEMPLATE_HEADERS, List.of(
                row("Product name *", "Rice 50kg", "Your code *", "RT-1", "Comes in", "Bag", "Size of one", "50 kg",
                        "How many you have now", "20", "Price you pay for one (₦)", "42000", "Category", "Grains"),
                row("Product name *", "Beans 100kg", "Your code *", "RT-2", "Size of one", "KGX", "Category", "grains"),
                row("Product name *", "Salt 25kg", "Your code *", "RT-3", "Size of one", "kg",
                        "Supplier", "Brand New Supplier Ltd")));

        ImportSessionResponse session = upload(tenant, filled, "products.xlsx", "PRODUCT_CATALOG", "CREATE_ONLY");

        assertThat(session.rowCount())
                .as("the template's own example rows say \"we skip it either way\" - the engine must agree")
                .isEqualTo(3);
        assertThat(session.columnMapping())
                .as("our own template's words map by themselves, so the mapping step never appears")
                .containsEntry("your_code", "sku")
                .containsEntry("size_of_one", "contains")
                .containsEntry("how_many_you_have_now", "opening_stock")
                .containsEntry("category", "category");
        assertThat(session.unmappedHeaders()).isEmpty();
        assertThat(session.needsMapping()).isFalse();
        assertThat(session.status().name()).isEqualTo("NEEDS_REVIEW");

        // Repair: the row whose unit was typed as a human types it.
        ImportRowResponse broken = rows(tenant, session.id(), "ERROR").stream()
                .filter(row -> "RT-2".equals(String.valueOf(row.raw().get("sku"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RT-2 should have been an error on contains"));
        assertThat(broken.errors()).anySatisfy(error -> assertThat(error.column()).isEqualTo("contains"));
        assertThat(broken.raw())
                .as("raw stays populated on an error row so the grid shows what they typed, not an em-dash")
                .containsEntry("contains", "KGX");
        ImportRowResponse repaired = patchRow(tenant, session.id(), broken.id(), Map.of("contains", "kg"));
        assertThat(repaired.status().name())
                .as("PATCH returns the REVALIDATED row - the grid does not refetch")
                .isEqualTo("VALID");
        assertThat(repaired.errors()).isEmpty();

        // Resolve: the supplier nobody has heard of.
        assertThat(session.unresolvedValues())
                .singleElement()
                .satisfies(unresolved -> {
                    assertThat(unresolved.column()).isEqualTo("vendor_name");
                    assertThat(unresolved.value()).isEqualTo("Brand New Supplier Ltd");
                    assertThat(unresolved.allowCreateNew()).isTrue();
                });
        ImportSessionResponse resolved = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "vendor_name",
                        "Brand New Supplier Ltd",
                        new ValueResolution(
                                ValueResolution.KIND_CREATE_NEW, null, null,
                                Map.of("name", "Brand New Supplier Ltd"))));
        assertThat(resolved.status().name()).isEqualTo("READY");
        // The question stays on the wire with its answer attached rather than vanishing - contract
        // section 4's `resolution` field - so the card can render as answered instead of the user
        // watching their decision disappear.
        assertThat(resolved.unresolvedValues())
                .singleElement()
                .satisfies(unresolved -> {
                    assertThat(unresolved.resolution()).isNotNull();
                    assertThat(unresolved.resolution().isCreateNew()).isTrue();
                });

        // Confirm.
        CommitPreviewResponse preview = preview(tenant, session.id());
        assertThat(preview.blocked()).isFalse();
        assertThat(preview.confirmLabel()).isNotBlank();
        assertThat(preview.lines()).anySatisfy(line -> assertThat(line.key()).isEqualTo("create"));
        assertThat(preview.lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("categories");
            assertThat(line.text()).as("Grains and grains are one category").isEqualTo("1 new category will be added");
        });

        // Commit.
        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.vendorsCreated()).isEqualTo(1);
        // Task 1.6: starting stock is back on the product sheet, and goes through the ledger.
        assertThat(result.movementsCreated()).as("one row said how many it has now").isEqualTo(1);
        assertThat(result.kind()).as("so the result screen can hand on to the stock sheet").isEqualTo(
                com.procurepal_services.stock_bridge_api.entity.ImportKind.PRODUCT_CATALOG);
        assertThat(productBySku(tenant, "RT-1").packagingSize())
                .as("\"50 kg\" beside Bag is a 50 kg bag")
                .isEqualByComparingTo("50");
        assertThat(productBySku(tenant, "RT-1").quantityOnHand()).as("20 bags of 50 kg").isEqualTo(1000);
        assertThat(productBySku(tenant, "RT-1").costPrice()).as("N42,000 a bag is N840 a kg").isEqualByComparingTo("840");
        assertThat(productBySku(tenant, "RT-1").categoryName()).isEqualTo("Grains");
        assertThat(productBySku(tenant, "RT-2").categoryName()).isEqualTo("Grains");
        assertThat(result.undoable()).isTrue();
        assertThat(productBySku(tenant, "RT-2").unitOfMeasure())
                .as("the repair, not the file, is what got imported")
                .isEqualTo("KG");

        // The report, as a document.
        try (XSSFWorkbook report = open(report(tenant, session.id()))) {
            Sheet sheet = report.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isNotBlank();
            List<String> headers = new ArrayList<>();
            for (Cell header : sheet.getRow(2)) {
                headers.add(header.getStringCellValue());
            }
            assertThat(headers.subList(0, 3)).containsExactly("row", "outcome", "message");
            List<String> outcomes = new ArrayList<>();
            for (int i = 3; i <= sheet.getLastRowNum(); i++) {
                outcomes.add(sheet.getRow(i).getCell(1).getStringCellValue());
            }
            assertThat(outcomes)
                    .as("every row of the file, in file order - completeness is the point of the report")
                    .containsExactly("CREATED", "CREATED", "CREATED");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Undo.
        ImportResultResponse undone = undo(tenant, session.id());
        assertThat(undone.undoable()).isFalse();
        assertThat(productsMatching(tenant, "RT-"))
                .as("undo deactivates what the import created - it never deletes, because the ledger "
                        + "rows stamped with this import point at these products")
                .hasSize(3)
                .allSatisfy(product -> {
                    assertThat(product.active()).isFalse();
                });
    }

    // ------------------------------------------------ stock-in template, end to end

    /**
     * The stock sheet as a document, checked against the catalog it was generated from: a help
     * tab first, the headers and guidance row, one row per way each product is bought, the
     * supplier and last price filled in, and the two columns a person answers left blank.
     */
    @Test
    void theStockInTemplateIsPreFilledFromTheCatalogItWasGeneratedFrom() {
        TenantLoginResponse tenant = signup("Stock Sheet Shape Co");
        createVendor(tenant, "Dangote Nigeria Plc");
        commitCatalog(tenant, CATALOG_CSV_HEADERS
                + "Rice 50kg,SS-1,,42000,,,KG,,,Dangote Nigeria Plc,,TRUE\n"
                + "Salt 25kg,SS-2,,12000,,,KG,,,,,\n");

        try (XSSFWorkbook workbook = open(stockInTemplate(tenant))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("How to fill this in");
            Sheet sheet = workbook.getSheet("Delivery");
            assertThat(headersOf(sheet)).containsExactlyElementsOf(STOCK_IN_TEMPLATE_HEADERS);
            assertThat(cell(sheet, 1, "Product", STOCK_IN_TEMPLATE_HEADERS)).startsWith("ⓘ");

            Map<String, Integer> rowByCode = new LinkedHashMap<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                rowByCode.put(cell(sheet, i, "Your code", STOCK_IN_TEMPLATE_HEADERS), i);
            }
            assertThat(rowByCode).containsOnlyKeys("SS-1", "SS-2");

            int rice = rowByCode.get("SS-1");
            assertThat(cell(sheet, rice, "Product", STOCK_IN_TEMPLATE_HEADERS)).isEqualTo("Rice 50kg");
            assertThat(cell(sheet, rice, "Supplier", STOCK_IN_TEMPLATE_HEADERS))
                    .as("pre-filled with the product's preferred supplier")
                    .isEqualTo("Dangote Nigeria Plc");
            assertThat(cell(sheet, rice, "Comes in", STOCK_IN_TEMPLATE_HEADERS))
                    .as("a product with no pack is bought one way, and the row says which")
                    .isEqualTo("Loose · kg");
            assertThat(numericCell(sheet, rice, "Last price paid (₦)", STOCK_IN_TEMPLATE_HEADERS))
                    .as("what they last paid, as a real number")
                    .isEqualTo(42000d);
            assertThat(cell(sheet, rice, "Price paid for one (₦)", STOCK_IN_TEMPLATE_HEADERS))
                    .as("the price starts blank - a blank price uses the last one, out loud")
                    .isEmpty();
            assertThat(cell(sheet, rice, "How many arrived", STOCK_IN_TEMPLATE_HEADERS))
                    .as("the one column the user has to fill is left empty")
                    .isEmpty();
            assertThat(cell(sheet, rice, "Ref", STOCK_IN_TEMPLATE_HEADERS)).isNotBlank();

            int salt = rowByCode.get("SS-2");
            assertThat(cell(sheet, salt, "Supplier", STOCK_IN_TEMPLATE_HEADERS))
                    .as("a product with no supplier yet leaves that cell blank rather than inventing one")
                    .isEmpty();
            assertThat(cell(sheet, salt, "Comes in", STOCK_IN_TEMPLATE_HEADERS)).isEqualTo("Loose · kg");
            assertThat(rowByCode.keySet()).as("one supplier's products first, the unsupplied last")
                    .containsExactly("SS-1", "SS-2");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The stock-in journey on the real generated sheet: fill one quantity, leave the other blank,
     * upload, commit, read the report, undo. The blank row is a silent skip - the sheet lists the
     * whole catalog and the user received two of four hundred things.
     */
    @Test
    void aStockSheetSurvivesDownloadFillUploadCommitReportAndUndo() {
        TenantLoginResponse tenant = signup("Stock Sheet Round Trip Co");
        createVendor(tenant, "Dangote Nigeria Plc");
        commitCatalog(tenant, CATALOG_CSV_HEADERS
                + "Rice 50kg,SRT-1,,42000,,,KG,,,Dangote Nigeria Plc,,TRUE\n"
                + "Salt 25kg,SRT-2,,12000,,,KG,,,Dangote Nigeria Plc,,TRUE\n");

        byte[] sheet = stockInTemplate(tenant);
        byte[] filled = setCell(sheet, STOCK_IN_TEMPLATE_HEADERS, "SRT-1", "How many arrived", "30");

        ImportSessionResponse session = upload(tenant, filled, "stock-sheet.xlsx", "STOCK_IN", null);
        assertThat(session.rowCount())
                .as("two product rows; the help tab and the guidance row are not rows")
                .isEqualTo(2);
        assertThat(session.unmappedHeaders()).isEmpty();
        assertThat(session.errorCount()).as("a blank quantity is a silent skip, never an error").isZero();
        assertThat(session.status().name()).isEqualTo("READY");

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.movementsCreated()).isEqualTo(1);
        assertThat(result.skippedCount()).as("the row we did not receive").isEqualTo(1);
        assertThat(productBySku(tenant, "SRT-1").quantityOnHand()).isEqualTo(30);
        assertThat(productBySku(tenant, "SRT-2").quantityOnHand()).isZero();

        try (XSSFWorkbook report = open(report(tenant, session.id()))) {
            Sheet target = report.getSheetAt(0);
            List<String> outcomes = new ArrayList<>();
            for (int i = 3; i <= target.getLastRowNum(); i++) {
                outcomes.add(target.getRow(i).getCell(1).getStringCellValue());
            }
            assertThat(outcomes)
                    .as("the skipped row is in the report too - it is the answer to \"why did only one go in\"")
                    .containsExactlyInAnyOrder("CREATED", "SKIPPED");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        undo(tenant, session.id());
        assertThat(productBySku(tenant, "SRT-1").quantityOnHand()).isZero();
    }

    // ------------------------------------------------------------- spreadsheet work

    private XSSFWorkbook open(byte[] xlsx) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> headersOf(Sheet sheet) {
        List<String> headers = new ArrayList<>();
        for (Cell cell : sheet.getRow(0)) {
            headers.add(cell.getStringCellValue());
        }
        return headers;
    }

    /** A cell by header name, formatted the way Excel would show it. */
    private String cell(Sheet sheet, int rowIndex, String header, List<String> headers) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            return "";
        }
        Cell cell = row.getCell(headers.indexOf(header));
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    /** The same cell as a number, for the money and quantity columns the template formats. */
    private double numericCell(Sheet sheet, int rowIndex, String header, List<String> headers) {
        Cell cell = sheet.getRow(rowIndex).getCell(headers.indexOf(header));
        assertThat(cell).as("%s on row %d", header, rowIndex).isNotNull();
        assertThat(cell.getCellType()).as("%s must be a number, not text", header).isEqualTo(CellType.NUMERIC);
        return cell.getNumericCellValue();
    }

    /** Appends data rows to a generated template, below whatever it already contains. */
    private byte[] fill(byte[] template, List<String> headers, List<Map<String, String>> newRows) {
        try (XSSFWorkbook workbook = open(template)) {
            Sheet sheet = workbook.getSheet("Products");
            int rowIndex = sheet.getLastRowNum() + 1;
            for (Map<String, String> values : newRows) {
                Row row = sheet.createRow(rowIndex++);
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    row.createCell(headers.indexOf(entry.getKey())).setCellValue(entry.getValue());
                }
            }
            return bytes(workbook);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fills one cell of the stock sheet, found by the product's code - what the user does to it. */
    private byte[] setCell(byte[] template, List<String> headers, String sku, String header, String value) {
        try (XSSFWorkbook workbook = open(template)) {
            Sheet sheet = workbook.getSheet("Delivery");
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                if (sku.equals(cell(sheet, i, "Your code", headers))) {
                    Row row = sheet.getRow(i);
                    Cell cell = row.getCell(headers.indexOf(header));
                    if (cell == null) {
                        cell = row.createCell(headers.indexOf(header));
                    }
                    cell.setCellType(CellType.STRING);
                    cell.setCellValue(value);
                    return bytes(workbook);
                }
            }
            throw new AssertionError("No pre-filled row for " + sku);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] bytes(XSSFWorkbook workbook) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, String> row(String... keyValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return values;
    }

    // ----------------------------------------------------------------- transport

    private byte[] productTemplate(TenantLoginResponse tenant) {
        return download(tenant, "/api/imports/templates/products");
    }

    private byte[] stockInTemplate(TenantLoginResponse tenant) {
        return download(tenant, "/api/imports/templates/stock-in?filter=ALL");
    }

    private byte[] report(TenantLoginResponse tenant, UUID sessionId) {
        return download(tenant, "/api/imports/" + sessionId + "/report");
    }

    private byte[] download(TenantLoginResponse tenant, String path) {
        ResponseEntity<byte[]> response =
                restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(authHeaders(tenant)), byte[].class);
        assertThat(response.getStatusCode()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .as("the saved file needs a sensible name - the frontend saves the blob, not a link")
                .endsWith(".xlsx");
        return response.getBody();
    }

    private ImportSessionResponse upload(
            TenantLoginResponse tenant, byte[] file, String filename, String kind, String mode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ByteArrayResource resource = new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(resource, filePartHeaders));
        body.add("kind", kind);
        if (mode != null) {
            body.add("mode", mode);
        }
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers), ImportSessionResponse.class);
        assertThat(response.getStatusCode()).as("upload must answer 201").isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void commitCatalog(TenantLoginResponse tenant, String csv) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "seed.csv";
            }
        };
        body.add("file", new HttpEntity<>(resource, new HttpHeaders()));
        body.add("kind", "PRODUCT_CATALOG");
        body.add("mode", "CREATE_ONLY");
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ImportSessionResponse session = restTemplate
                .exchange("/api/imports", HttpMethod.POST, new HttpEntity<>(body, headers),
                        ImportSessionResponse.class)
                .getBody();
        commit(tenant, session.id());
    }

    private ImportResultResponse commit(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<ImportResultResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);
        assertThat(response.getStatusCode()).as("commit must answer 200").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ImportResultResponse undo(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<ImportResultResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/undo",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);
        assertThat(response.getStatusCode()).as("undo must answer 200").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private CommitPreviewResponse preview(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/preview",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        CommitPreviewResponse.class)
                .getBody();
    }

    private List<ImportRowResponse> rows(TenantLoginResponse tenant, UUID sessionId, String status) {
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/rows?status=" + status,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<ImportRowResponse>>() {})
                .getBody()
                .content();
    }

    private ImportRowResponse patchRow(
            TenantLoginResponse tenant, UUID sessionId, UUID rowId, Map<String, Object> normalized) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/rows/" + rowId,
                        HttpMethod.PATCH,
                        new HttpEntity<>(new PatchRowRequest(normalized), headers),
                        ImportRowResponse.class)
                .getBody();
    }

    private ImportSessionResponse resolveValue(
            TenantLoginResponse tenant, UUID sessionId, ValueMappingRequest request) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ImportSessionResponse> response = restTemplate.exchange(
                "/api/imports/" + sessionId + "/value-mappings",
                HttpMethod.PATCH,
                new HttpEntity<>(request, headers),
                ImportSessionResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private CompanyVendorResponse createVendor(TenantLoginResponse tenant, String name) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate
                .exchange(
                        "/api/company-vendors",
                        HttpMethod.POST,
                        new HttpEntity<>(
                                new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null, null, null, null, null),
                                headers),
                        CompanyVendorResponse.class)
                .getBody();
    }

    private List<ProductResponse> productsMatching(TenantLoginResponse tenant, String search) {
        return restTemplate
                .exchange(
                        "/api/products?search=" + search + "&size=50",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<ProductResponse>>() {})
                .getBody()
                .content();
    }

    private ProductResponse productBySku(TenantLoginResponse tenant, String sku) {
        return productsMatching(tenant, sku).stream()
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
    private record TestPage<T>(List<T> content, int totalElements) {
    }
}
