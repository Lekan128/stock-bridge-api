package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.entity.ImportSession;
import com.procurepal_services.stock_bridge_api.entity.ImportStatus;
import com.procurepal_services.stock_bridge_api.entity.Product;
import com.procurepal_services.stock_bridge_api.entity.ProductCategory;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRepository;
import com.procurepal_services.stock_bridge_api.repository.ImportSessionRowRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductCategoryRepository;
import com.procurepal_services.stock_bridge_api.repository.ProductRepository;
import com.procurepal_services.stock_bridge_api.repository.StockMovementRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * The three paths the engine's author flagged as built but never exercised.
 *
 * <ol>
 *   <li><b>A commit that blows up mid-transaction.</b> Every other commit test in the suite runs
 *       the happy path; this one forces a real constraint violation inside the write and asserts
 *       what {@code ImportExceptions.CommitFailed} promises the user in words - "nothing was
 *       changed". The all-or-nothing transaction is the only thing making that sentence true, and
 *       nothing was checking it.</li>
 *   <li><b>The forty-eight-hour sweep.</b> {@code collectExpired} is public and directly
 *       drivable, and the rule that matters is the negative one: a COMMITTED file is never
 *       collected. Both {@code import_batch_id} foreign keys are ON DELETE RESTRICT so a wrong
 *       sweep would fail loudly rather than corrupt - but "fails loudly" is a scheduled job
 *       logging an error every hour forever, so the rule is asserted rather than left to the
 *       schema.</li>
 *   <li><b>{@code filter=BY_VENDOR} and {@code filter=BY_CATEGORY}.</b> Contract section 7 records
 *       these as built-but-unreachable: {@code importsApi.stockInTemplateUrl} does not accept a
 *       {@code vendorId} or a {@code categoryId}, so no caller has ever sent one. Reachable or
 *       not, they are shipping.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BulkImportEngineEdgeCaseIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String CATALOG_HEADERS =
            "name,sku,description,cost_price,quantity_on_hand,low_stock_threshold,unit_of_measure,"
                    + "packaging_unit,packaging_size,vendor_name,vendor_sku,is_preferred_vendor\n";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ImportSessionService importSessionService;

    @Autowired
    private ImportSessionRepository importSessionRepository;

    @Autowired
    private ImportSessionRowRepository importSessionRowRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductCategoryRepository productCategoryRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    // ------------------------------------------------- 1. commit failure and rollback

    /**
     * A genuine mid-commit failure, and the promise that survives it.
     *
     * <p>The file is fifty clean rows that validated as READY. Between validation and commit, one
     * of its product codes is taken by a second import - which is not contrived: it is two people
     * uploading overlapping files in the same minute, and it is exactly the race the review screen
     * cannot see. On commit, {@code productRepository.flush()} hits
     * {@code uq_products_client_id_sku} and the whole transaction rolls back.
     *
     * <p>Four things must then hold, and none of them was covered anywhere:
     * <ul>
     *   <li>the caller is told, in the words the exception carries, that nothing changed;</li>
     *   <li>nothing changed - not one of the other forty-nine rows was written;</li>
     *   <li>no row anywhere carries the failed run's {@code import_batch_id}, which would be a
     *       stamp pointing at an import that never happened;</li>
     *   <li>the file reports itself as FAILED and its result is still readable, because the
     *       review screen sends COMMITTING to the result screen and a file stuck in COMMITTING
     *       strands the user on a spinner with nothing to read.</li>
     * </ul>
     */
    @Test
    void aCommitThatFailsMidTransactionRollsBackEverythingAndSaysSoHonestly() {
        TenantLoginResponse tenant = signup("Rollback Co");

        StringBuilder csv = new StringBuilder(CATALOG_HEADERS);
        for (int i = 1; i <= 50; i++) {
            csv.append("Product %d,ROLL-%d,,4200,7,,KG,,,,,\n".formatted(i, i));
        }
        ImportSessionResponse session = upload(tenant, csv.toString(), "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(session.status()).isEqualTo(ImportStatus.READY);
        assertThat(session.rowCount()).isEqualTo(50);

        // Row 40's code is taken behind this file's back, after it was validated.
        ImportSessionResponse thief =
                upload(tenant, CATALOG_HEADERS + "Thief,ROLL-40,,4200,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(tenant, thief.id());

        ResponseEntity<ApiErrorBody> failed = restTemplate.exchange(
                "/api/imports/" + session.id() + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ApiErrorBody.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(failed.getBody().message())
                .as("the sentence the user reads is only true because of the all-or-nothing transaction")
                .contains("nothing was changed");

        UUID clientId = clientIdOf(session.id());

        // Not one of the other forty-nine rows was written. ROLL-40 exists, but it is the thief's.
        for (int i = 1; i <= 50; i++) {
            Optional<Product> product = productRepository.findByClientIdAndSku(clientId, "ROLL-" + i);
            if (i == 40) {
                assertThat(product).isPresent();
                assertThat(product.get().getImportBatchId())
                        .as("ROLL-40 belongs to the import that won the race, not to the one that failed")
                        .isEqualTo(thief.id());
            } else {
                assertThat(product).as("ROLL-%d must not have been written", i).isEmpty();
            }
        }

        // No orphaned stamps: nothing anywhere points at the run that rolled back.
        assertThat(productRepository.findAllByClientIdAndImportBatchId(clientId, session.id()))
                .as("a product stamped with an import that never committed would be a lie in the audit trail")
                .isEmpty();
        assertThat(stockMovementRepository.findAllByClientIdAndImportBatchIdOrderByOccurredAtAsc(
                        clientId, session.id()))
                .as("fifty opening balances rolled back with the products they belonged to")
                .isEmpty();

        // The file reports itself honestly, and the result screen has something to show.
        ImportSessionResponse afterFailure = get(tenant, session.id());
        assertThat(afterFailure.status())
                .as("a file left in COMMITTING strands the user on a spinner - M5 polls until it leaves")
                .isEqualTo(ImportStatus.FAILED);

        ResponseEntity<ImportResultResponse> result = restTemplate.exchange(
                "/api/imports/" + session.id() + "/result",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);
        assertThat(result.getStatusCode()).as("GET /result must stay readable after a failure").isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().status()).isEqualTo(ImportStatus.FAILED);
        assertThat(result.getBody().createdCount()).isZero();
        assertThat(result.getBody().movementsCreated()).isZero();
        assertThat(result.getBody().failedCount()).isEqualTo(50);
        assertThat(result.getBody().undoable())
                .as("there is nothing to undo - and offering it would imply something happened")
                .isFalse();
        assertThat(result.getBody().lines()).anySatisfy(line ->
                assertThat(line.text()).contains("Nothing was imported"));
    }

    /**
     * A failed run is not a committed one: it can still be thrown away, and it cannot be undone.
     * Both follow from FAILED, and both are what the two buttons on that screen do.
     */
    @Test
    void aFailedImportCanBeDiscardedAndCannotBeUndone() {
        TenantLoginResponse tenant = signup("Rollback Discard Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS + "One,RBD-1,,4200,,,KG,,,,,\n" + "Two,RBD-2,,4200,,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");
        commit(tenant, upload(tenant, CATALOG_HEADERS + "Thief,RBD-2,,4200,,,KG,,,,,\n", "PRODUCT_CATALOG",
                        "CREATE_ONLY")
                .id());

        restTemplate.exchange(
                "/api/imports/" + session.id() + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                String.class);
        assertThat(get(tenant, session.id()).status()).isEqualTo(ImportStatus.FAILED);

        assertThat(restTemplate
                        .exchange(
                                "/api/imports/" + session.id() + "/undo",
                                HttpMethod.POST,
                                new HttpEntity<>(authHeaders(tenant)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(restTemplate
                        .exchange(
                                "/api/imports/" + session.id(),
                                HttpMethod.DELETE,
                                new HttpEntity<>(authHeaders(tenant)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------ 2. the expiry sweep

    /**
     * The sweep, end to end, with both answers in one run.
     *
     * <p>Two files, both with an {@code expires_at} pushed into the past: one abandoned before
     * commit, one imported. The sweep must take the first and leave the second - and it must
     * leave it without throwing, because {@code ImportExpirySweep} swallows and logs, so a sweep
     * that tried and failed would look identical to one that correctly did nothing, right up
     * until the hourly error log.
     */
    @Test
    void theExpirySweepCollectsAnAbandonedFileAndNeverACommittedOne() {
        TenantLoginResponse tenant = signup("Expiry Sweep Co");

        ImportSessionResponse abandoned = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,EXP-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        ImportSessionResponse imported = upload(
                tenant, CATALOG_HEADERS + "Beans 100kg,EXP-2,,52000,15,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(tenant, imported.id());

        UUID clientId = clientIdOf(abandoned.id());
        expire(abandoned.id());
        expire(imported.id());

        int collected = importSessionService.collectExpired(100);

        assertThat(collected).as("exactly the abandoned one").isEqualTo(1);
        assertThat(importSessionRepository.findById(abandoned.id())).isEmpty();
        assertThat(importSessionRowRepository.findAllBySessionIdOrderByExcelRowAsc(abandoned.id()))
                .as("its rows go with it - that is the table the sweep exists to stop growing")
                .isEmpty();

        assertThat(importSessionRepository.findById(imported.id()))
                .as("a committed import is provenance for real stock; both import_batch_id FKs are "
                        + "ON DELETE RESTRICT precisely so this can never be deleted")
                .isPresent()
                .get()
                .satisfies(session -> assertThat(session.getStatus()).isEqualTo(ImportStatus.COMMITTED));
        assertThat(productRepository.findAllByClientIdAndImportBatchId(clientId, imported.id()))
                .as("and what it created still points back at it")
                .hasSize(1);
        assertThat(stockMovementRepository.findAllByClientIdAndImportBatchIdOrderByOccurredAtAsc(
                        clientId, imported.id()))
                .hasSize(1);

        // The user's view agrees with the sweep: the abandoned link is gone, the committed one works.
        assertThat(getRaw(tenant, abandoned.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getRaw(tenant, imported.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * A file that has not passed its two days is left alone. Without this, the test above would
     * pass just as well against a sweep that collected everything uncommitted regardless of date,
     * which is the far more damaging bug of the two.
     */
    @Test
    void theExpirySweepLeavesAFileThatIsStillWithinItsTwoDays() {
        TenantLoginResponse tenant = signup("Expiry Fresh Co");
        ImportSessionResponse fresh = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,EXPF-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(fresh.expiresAt()).isAfter(OffsetDateTime.now());

        importSessionService.collectExpired(100);

        assertThat(importSessionRepository.findById(fresh.id())).isPresent();
        assertThat(getRaw(tenant, fresh.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // -------------------------------------------- 3. the two unreachable template filters

    /**
     * {@code filter=BY_VENDOR}. Two products, two suppliers, one sheet - and the sheet has to
     * contain the one product that supplier actually sells.
     *
     * <p>Also covers the malformed case the service documents: BY_VENDOR with no {@code vendorId}
     * yields nothing rather than everything. Handing back the whole catalog for a request that
     * named no supplier would quietly give the user a very different file from the one they asked
     * for, and they would not find out until they had filled it in.
     */
    @Test
    void theStockInTemplateCanBeFilteredToOneSuppliersProducts() {
        TenantLoginResponse tenant = signup("Template Vendor Co");
        CompanyVendorResponse dangote = createVendor(tenant, "Dangote Nigeria Plc");
        createVendor(tenant, "Olam Agri Ltd");

        commitCatalog(tenant, CATALOG_HEADERS
                + "Rice 50kg,TVEN-1,,42000,,,KG,,,Dangote Nigeria Plc,,TRUE\n"
                + "Salt 25kg,TVEN-2,,12000,,,KG,,,Olam Agri Ltd,,TRUE\n");

        assertThat(skusInSheet(stockInTemplate(tenant, "?filter=ALL")))
                .as("the unfiltered sheet is the control - both products are in the catalog")
                .contains("TVEN-1", "TVEN-2");

        assertThat(skusInSheet(stockInTemplate(tenant, "?filter=BY_VENDOR&vendorId=" + dangote.id())))
                .containsExactly("TVEN-1");

        assertThat(skusInSheet(stockInTemplate(tenant, "?filter=BY_VENDOR")))
                .as("BY_VENDOR with no supplier is a malformed request, and the whole catalog is the "
                        + "wrong answer to it")
                .isEmpty();
    }

    /**
     * {@code filter=BY_CATEGORY}. The category is set straight on the row because assigning one
     * through the API is a platform-owner journey that has nothing to do with this filter - what
     * is under test is the predicate, and the predicate reads {@code product.category}.
     */
    @Test
    void theStockInTemplateCanBeFilteredToOneCategory() {
        TenantLoginResponse tenant = signup("Template Category Co");
        UUID catalogSession = commitCatalog(tenant, CATALOG_HEADERS
                + "Rice 50kg,TCAT-1,,42000,,,KG,,,,,\n"
                + "Engine oil,TCAT-2,,12000,,,KG,,,,,\n");

        UUID clientId = clientIdOf(catalogSession);
        ProductCategory grains = productCategoryRepository.save(ProductCategory.builder()
                .name("Grains")
                .slug("grains-" + UUID.randomUUID())
                .sortOrder(0)
                .active(true)
                .build());
        Product rice = productRepository.findByClientIdAndSku(clientId, "TCAT-1").orElseThrow();
        rice.setCategory(grains);
        productRepository.saveAndFlush(rice);

        assertThat(skusInSheet(stockInTemplate(tenant, "?filter=BY_CATEGORY&categoryId=" + grains.getId())))
                .containsExactly("TCAT-1");

        // A category the tenant has nothing in yields an empty sheet, not a fall-through to ALL.
        assertThat(skusInSheet(stockInTemplate(tenant, "?filter=BY_CATEGORY&categoryId=" + UUID.randomUUID())))
                .isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    /** Pushes a file past its two-day limit without waiting two days. */
    private void expire(UUID sessionId) {
        ImportSession session = importSessionRepository.findById(sessionId).orElseThrow();
        session.setExpiresAt(OffsetDateTime.now().minusHours(1));
        importSessionRepository.saveAndFlush(session);
    }

    private UUID clientIdOf(UUID sessionId) {
        return importSessionRepository.findById(sessionId).orElseThrow().getClientId();
    }

    private byte[] stockInTemplate(TenantLoginResponse tenant, String query) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/imports/templates/stock-in" + query,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant)),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** The catalog SKUs in a generated stock sheet - the example row's reserved marker excluded. */
    private List<String> skusInSheet(byte[] xlsx) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("sku");
            List<String> skus = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Cell cell = row.getCell(0);
                String sku = cell == null ? null : cell.getStringCellValue();
                if (sku != null && !sku.isBlank() && !sku.startsWith("EXAMPLE-SKU-DELETE-ME")) {
                    skus.add(sku);
                }
            }
            return skus;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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

    private ImportSessionResponse upload(TenantLoginResponse tenant, String csv, String kind, String mode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "upload.csv";
            }
        };
        body.add("file", new HttpEntity<>(resource, new HttpHeaders()));
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

    private UUID commitCatalog(TenantLoginResponse tenant, String csv) {
        UUID sessionId = upload(tenant, csv, "PRODUCT_CATALOG", "CREATE_ONLY").id();
        commit(tenant, sessionId);
        return sessionId;
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

    private ImportSessionResponse get(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        ImportSessionResponse.class)
                .getBody();
    }

    private ResponseEntity<String> getRaw(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate.exchange(
                "/api/imports/" + sessionId, HttpMethod.GET, new HttpEntity<>(authHeaders(tenant)), String.class);
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
    private record ApiErrorBody(String message) {
    }

}
