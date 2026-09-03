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

    /**
     * Contract section 5's PRODUCT_CATALOG field keys for a company that does not sell -
     * {@code unit_price} is omitted entirely, as it always has been. Field key and column header
     * are the same string, which is what makes {@code column_mapping} an identity map for our own
     * template and is the property this whole round trip depends on.
     */
    private static final List<String> CATALOG_TEMPLATE_HEADERS = List.of(
            "name", "sku", "description",
            "stock_unit", "pack", "units_per_pack",
            "opening_stock", "low_stock_alert_at", "cost_price",
            "vendor_name", "vendor_sku", "is_preferred_vendor");

    /** UNIT_UX_CONTRACT.md section 5.2's STOCK_IN column set, in order. */
    private static final List<String> STOCK_IN_TEMPLATE_HEADERS = List.of(
            "sku", "product_name", "how_you_count_it", "vendor_name", "quantity", "counted_in",
            "cost_per_unit", "received_date", "reference");

    private static final String EXAMPLE_MARKER = "EXAMPLE-SKU-DELETE-ME";

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
     * The product template as a document: the columns contract section 5 promises, in the order it
     * promises them, and the two greyed example rows the file's own text says are safe to leave in.
     */
    @Test
    void theProductTemplateIsAWellFormedSheetWithTheContractsColumnsAndItsExampleRows() {
        TenantLoginResponse tenant = signup("Template Shape Co");
        createVendor(tenant, "Dangote Nigeria Plc");

        try (XSSFWorkbook workbook = open(productTemplate(tenant))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(headersOf(sheet))
                    .as("field key and column header are the same string - contract section 5")
                    .containsExactlyElementsOf(CATALOG_TEMPLATE_HEADERS);

            assertThat(cell(sheet, 1, "sku", CATALOG_TEMPLATE_HEADERS)).startsWith(EXAMPLE_MARKER);
            assertThat(cell(sheet, 2, "sku", CATALOG_TEMPLATE_HEADERS)).startsWith(EXAMPLE_MARKER);
            // Labels, never codes - UNIT_UX_CONTRACT.md section 7, non-negotiable 4. The example
            // row is the one place a user learns what to type into these columns, so a cell
            // reading "KG" would teach them our vocabulary instead of their own.
            assertThat(cell(sheet, 1, "stock_unit", CATALOG_TEMPLATE_HEADERS)).isEqualTo("Milliliter (ml)");
            assertThat(cell(sheet, 1, "pack", CATALOG_TEMPLATE_HEADERS)).isEqualTo("Keg");
            // The example teaches section 9.1: 30 beside a Keg of 50 ml is thirty kegs. A row
            // reading 1,000 beside a pack of 50 - which is what it used to say - teaches the
            // opposite, and the opposite is what a user actually got wrong.
            assertThat(cell(sheet, 1, "units_per_pack", CATALOG_TEMPLATE_HEADERS)).isEqualTo("50");
            assertThat(cell(sheet, 1, "opening_stock", CATALOG_TEMPLATE_HEADERS)).isEqualTo("30");
            assertThat(cell(sheet, 1, "vendor_name", CATALOG_TEMPLATE_HEADERS))
                    .as("the example demonstrates the vendor columns with a supplier this tenant actually has")
                    .isEqualTo("Dangote Nigeria Plc");
            assertThat(sheet.getLastRowNum()).as("headers plus two examples, and nothing else").isEqualTo(2);
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
                row("name", "Rice 50kg", "sku", "RT-1", "cost_price", "840",
                        "opening_stock", "20", "stock_unit", "KG"),
                row("name", "Beans 100kg", "sku", "RT-2", "cost_price", "52000", "stock_unit", "KGX"),
                row("name", "Salt 25kg", "sku", "RT-3", "cost_price", "12000", "stock_unit", "KG",
                        "vendor_name", "Brand New Supplier Ltd")));

        ImportSessionResponse session = upload(tenant, filled, "products.xlsx", "PRODUCT_CATALOG", "CREATE_ONLY");

        assertThat(session.rowCount())
                .as("the template's own example rows say \"we skip it either way\" - the engine must agree")
                .isEqualTo(3);
        assertThat(session.columnMapping())
                .as("our own template maps to itself, so the mapping step never appears")
                .containsEntry("sku", "sku")
                .containsEntry("stock_unit", "stock_unit");
        assertThat(session.needsMapping()).isFalse();
        assertThat(session.status().name()).isEqualTo("NEEDS_REVIEW");

        // Repair: the row whose unit was typed as a human types it.
        ImportRowResponse broken = rows(tenant, session.id(), "ERROR").stream()
                .filter(row -> "RT-2".equals(String.valueOf(row.raw().get("sku"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RT-2 should have been an error on stock_unit"));
        assertThat(broken.raw())
                .as("raw stays populated on an error row so the grid shows what they typed, not an em-dash")
                .containsEntry("stock_unit", "KGX");
        ImportRowResponse repaired = patchRow(tenant, session.id(), broken.id(), Map.of("stock_unit", "KG"));
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

        // Commit.
        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.vendorsCreated()).isEqualTo(1);
        assertThat(result.movementsCreated()).as("only RT-1 carried an opening balance").isEqualTo(1);
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
                    assertThat(product.quantityOnHand()).isZero();
                });
    }

    // ------------------------------------------------ stock-in template, end to end

    /**
     * The pre-filled stock sheet as a document, checked against the catalog it was generated from.
     *
     * <p>Design 5.3's whole argument is that the user fills exactly one column. That only holds if
     * the other eight arrive correct, so this asserts the pre-fill cell by cell against the
     * products it came from - and asserts {@code quantity} is the one that is blank.
     */
    @Test
    void theStockInTemplateIsPreFilledFromTheCatalogItWasGeneratedFrom() {
        TenantLoginResponse tenant = signup("Stock Sheet Shape Co");
        createVendor(tenant, "Dangote Nigeria Plc");
        commitCatalog(tenant, CATALOG_CSV_HEADERS
                + "Rice 50kg,SS-1,,42000,,,KG,,,Dangote Nigeria Plc,,TRUE\n"
                + "Salt 25kg,SS-2,,12000,,,KG,,,,,\n");

        try (XSSFWorkbook workbook = open(stockInTemplate(tenant))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(headersOf(sheet)).containsExactlyElementsOf(STOCK_IN_TEMPLATE_HEADERS);
            assertThat(cell(sheet, 1, "sku", STOCK_IN_TEMPLATE_HEADERS)).startsWith(EXAMPLE_MARKER);

            Map<String, Integer> rowBySku = new LinkedHashMap<>();
            for (int i = 2; i <= sheet.getLastRowNum(); i++) {
                rowBySku.put(cell(sheet, i, "sku", STOCK_IN_TEMPLATE_HEADERS), i);
            }
            assertThat(rowBySku).containsOnlyKeys("SS-1", "SS-2");

            int rice = rowBySku.get("SS-1");
            assertThat(cell(sheet, rice, "product_name", STOCK_IN_TEMPLATE_HEADERS)).isEqualTo("Rice 50kg");
            assertThat(cell(sheet, rice, "vendor_name", STOCK_IN_TEMPLATE_HEADERS))
                    .as("pre-filled with the product's preferred supplier - design 8.1")
                    .isEqualTo("Dangote Nigeria Plc");
            assertThat(cell(sheet, rice, "counted_in", STOCK_IN_TEMPLATE_HEADERS))
                    .as("the label a person reads, never the internal code - contract 7.4")
                    .isEqualTo("kg");
            assertThat(cell(sheet, rice, "how_you_count_it", STOCK_IN_TEMPLATE_HEADERS))
                    .as("a product with no pack has exactly one way to count it, and the row says so")
                    .isEqualTo("kg");
            assertThat(numericCell(sheet, rice, "cost_per_unit", STOCK_IN_TEMPLATE_HEADERS))
                    .as("what they last paid, from the vendor line the catalog import wrote - written as a "
                            + "real number in a money-formatted column, not as text")
                    .isEqualTo(42000d);
            assertThat(cell(sheet, rice, "received_date", STOCK_IN_TEMPLATE_HEADERS)).isNotBlank();
            assertThat(cell(sheet, rice, "quantity", STOCK_IN_TEMPLATE_HEADERS))
                    .as("the one column the user has to fill is the one column left empty")
                    .isEmpty();

            int salt = rowBySku.get("SS-2");
            assertThat(cell(sheet, salt, "vendor_name", STOCK_IN_TEMPLATE_HEADERS))
                    .as("a product with no supplier yet leaves that cell blank rather than inventing one")
                    .isEmpty();
            assertThat(cell(sheet, salt, "counted_in", STOCK_IN_TEMPLATE_HEADERS)).isEqualTo("kg");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The stock-in journey on the real generated sheet: fill one quantity, leave the other blank,
     * upload, commit, read the report, undo.
     *
     * <p>The blank row is contract section 8.11 in its natural habitat - the sheet lists the whole
     * catalog and the user only received two of four hundred things, so a blank quantity has to be
     * a silent skip rather than four hundred errors.
     */
    @Test
    void aStockSheetSurvivesDownloadFillUploadCommitReportAndUndo() {
        TenantLoginResponse tenant = signup("Stock Sheet Round Trip Co");
        createVendor(tenant, "Dangote Nigeria Plc");
        commitCatalog(tenant, CATALOG_CSV_HEADERS
                + "Rice 50kg,SRT-1,,42000,,,KG,,,Dangote Nigeria Plc,,TRUE\n"
                + "Salt 25kg,SRT-2,,12000,,,KG,,,Dangote Nigeria Plc,,TRUE\n");

        byte[] sheet = stockInTemplate(tenant);
        byte[] filled = setCell(sheet, STOCK_IN_TEMPLATE_HEADERS, "SRT-1", "quantity", "30");

        ImportSessionResponse session = upload(tenant, filled, "stock-sheet.xlsx", "STOCK_IN", null);
        assertThat(session.rowCount())
                .as("two catalog rows; the example row the sheet promises to skip is not one of them")
                .isEqualTo(2);
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
            Sheet sheet = workbook.getSheetAt(0);
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

    /** Fills one cell of a pre-filled sheet, found by its sku - what the user does to a stock sheet. */
    private byte[] setCell(byte[] template, List<String> headers, String sku, String header, String value) {
        try (XSSFWorkbook workbook = open(template)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                if (sku.equals(cell(sheet, i, "sku", headers))) {
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
                                new CompanyVendorRequest(name, "08030000000", null, null, null, null, null, null),
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
