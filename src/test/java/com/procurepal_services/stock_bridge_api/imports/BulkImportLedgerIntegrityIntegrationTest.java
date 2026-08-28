package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * Ledger integrity - BULK_IMPORT_DESIGN.md section 3 and contract section 8.1, the single
 * invariant this whole feature is not allowed to break:
 *
 * <blockquote>No quantity reaches {@code products.quantity_on_hand} without a corresponding
 * {@code StockMovement}.</blockquote>
 *
 * <p>Every other test in the suite checks a <em>count</em> the server reported about itself.
 * These check the two numbers against each other: the product's cached {@code quantity_on_hand}
 * as {@code GET /api/products} publishes it, and the signed sum of every row
 * {@code GET /api/products/{id}/stock/history} returns. If those two ever disagree, the cached
 * figure has been written by a path that skipped the ledger, and the ledger has stopped being
 * able to reconstruct stock - which is what {@code StockMovement}'s class javadoc promises it can
 * always do. That is a release blocker, not a test failure, which is why the assertion is spelled
 * out per path rather than folded into one loop.
 *
 * <p>Every path that can move stock is covered: a catalog import's opening balances, a stock-in
 * import, the undo of each, and the compatibility shim at {@code POST /api/products/bulk-upload}
 * that contract section 3 keeps alive. The shim is included deliberately - it is the one caller
 * that predates the ledger rule, and it is the one nobody would think to re-check.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BulkImportLedgerIntegrityIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String CATALOG_HEADERS =
            "name,sku,description,cost_price,quantity_on_hand,low_stock_threshold,unit_of_measure,"
                    + "packaging_unit,packaging_size,vendor_name,vendor_sku,is_preferred_vendor\n";

    private static final String STOCK_IN_HEADERS =
            "sku,product_name,vendor_name,quantity,unit,unit_cost,packaging_size,received_date,reference\n";

    /** The legacy shim's column set for a non-seller company - ProductExcelService.ALL_HEADER_NAMES minus unit_price. */
    private static final List<String> LEGACY_COMPANY_HEADERS = List.of(
            "name", "sku", "description", "cost_price", "quantity_on_hand", "low_stock_threshold",
            "unit_of_measure", "packaging_unit", "packaging_size");

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------------- catalog opening balances

    /**
     * A catalog import that sets an opening balance must have written an IN movement for it.
     *
     * <p>Design section 3 is explicit that this is the bug the ledger fix existed to close: the
     * pre-V20 bulk upload set {@code quantity_on_hand} straight onto the row, so a product could
     * arrive in the catalog with 100 kg of stock and an empty history. The three rows below cover
     * the three shapes that matter - a quantity, no quantity, and an explicit zero - because a
     * reconciliation that only ever sees non-zero quantities cannot tell "wrote a movement" from
     * "wrote a movement for everything, including nothing".
     */
    @Test
    void aCatalogImportsOpeningBalancesAreEachBackedByALedgerMovement() {
        TenantLoginResponse tenant = signup("Ledger Catalog Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,LEDG-C1,,42000,100,,KG,,,,,\n"
                        + "Beans 100kg,LEDG-C2,,52000,,,KG,,,,,\n"
                        + "Salt 25kg,LEDG-C3,,12000,0,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");
        ImportResultResponse result = commit(tenant, session.id());

        // One movement, for the one row that carried a quantity. Not three, not zero.
        assertThat(result.movementsCreated()).isEqualTo(1);

        assertLedgerReconciles(tenant, "LEDG-C1");
        assertLedgerReconciles(tenant, "LEDG-C2");
        assertLedgerReconciles(tenant, "LEDG-C3");

        ProductResponse stocked = productBySku(tenant, "LEDG-C1");
        assertThat(stocked.quantityOnHand()).isEqualTo(100);
        assertThat(movements(tenant, stocked.id()))
                .as("the opening balance must be an IN movement, not a bare column write")
                .singleElement()
                .satisfies(movement -> {
                    assertThat(movement.movementType()).isEqualTo(MovementType.IN);
                    assertThat(movement.quantity()).isEqualTo(100);
                });

        // The row with no quantity, and the row with an explicit zero, both have an empty
        // history AND a zero on hand. A movement written for either would be a phantom delivery.
        assertThat(movements(tenant, productBySku(tenant, "LEDG-C2").id())).isEmpty();
        assertThat(movements(tenant, productBySku(tenant, "LEDG-C3").id())).isEmpty();
    }

    /**
     * The same invariant after the undo. Design 6.6 forbids deleting ledger rows - the reversal is
     * a compensating {@code ADJUSTMENT} - so this asserts both halves at once: the ledger got
     * longer rather than shorter, and it still sums to what the product says it holds.
     */
    @Test
    void undoingACatalogImportReversesTheOpeningStockWithoutDeletingLedgerRows() {
        TenantLoginResponse tenant = signup("Ledger Catalog Undo Co");
        ImportSessionResponse session = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,LEDG-U1,,42000,80,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(tenant, session.id());

        UUID productId = productBySku(tenant, "LEDG-U1").id();
        List<StockMovementResponse> before = movements(tenant, productId);
        assertThat(before).hasSize(1);
        assertLedgerReconciles(tenant, "LEDG-U1");

        ImportResultResponse undone = undo(tenant, session.id());
        // The status stays COMMITTED - what changes is that it can no longer be undone twice.
        assertThat(undone.undoable()).isFalse();
        assertThat(undone.movementsCreated()).as("the reversal reports the movements it wrote").isEqualTo(1);

        List<StockMovementResponse> after = movements(tenant, productId);
        assertThat(after)
                .as("undo compensates, it never deletes - contract section 8.10")
                .hasSizeGreaterThan(before.size());
        assertThat(after).extracting(StockMovementResponse::id).containsAll(before.stream()
                .map(StockMovementResponse::id)
                .toList());
        assertThat(after).anySatisfy(movement -> {
            assertThat(movement.movementType()).isEqualTo(MovementType.ADJUSTMENT);
            assertThat(movement.quantity()).isEqualTo(-80);
        });

        // And the two numbers still agree, which is the whole point: an undo that zeroed the
        // column without a compensating row would leave a product reading 0 over a ledger of 80.
        assertLedgerReconcilesFor(tenant, productId, "LEDG-U1");
    }

    // ------------------------------------------------------------------ stock in

    @Test
    void aStockInImportsQuantitiesAreEachBackedByALedgerMovement() {
        TenantLoginResponse tenant = signup("Ledger Stock In Co");
        commitCatalog(tenant, CATALOG_HEADERS
                + "Rice 50kg,LEDG-S1,,42000,10,,KG,,,,,\n"
                + "Beans 100kg,LEDG-S2,,52000,,,KG,,,,,\n");
        assertLedgerReconciles(tenant, "LEDG-S1");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "LEDG-S1,Rice 50kg,,25,KG,42000,,,WB-1\n"
                        + "LEDG-S2,Beans 100kg,,40,KG,52000,,,WB-2\n",
                "STOCK_IN",
                null);
        assertThat(session.status().name()).isEqualTo("READY");
        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.movementsCreated()).isEqualTo(2);

        assertThat(productBySku(tenant, "LEDG-S1").quantityOnHand()).isEqualTo(35);
        assertThat(productBySku(tenant, "LEDG-S2").quantityOnHand()).isEqualTo(40);
        assertLedgerReconciles(tenant, "LEDG-S1");
        assertLedgerReconciles(tenant, "LEDG-S2");
    }

    @Test
    void undoingAStockInImportReversesTheDeliveriesWithoutDeletingLedgerRows() {
        TenantLoginResponse tenant = signup("Ledger Stock In Undo Co");
        commitCatalog(tenant, CATALOG_HEADERS + "Rice 50kg,LEDG-SU1,,42000,10,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant, STOCK_IN_HEADERS + "LEDG-SU1,Rice 50kg,,25,KG,42000,,,WB-9\n", "STOCK_IN", null);
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "LEDG-SU1").quantityOnHand()).isEqualTo(35);

        UUID productId = productBySku(tenant, "LEDG-SU1").id();
        List<UUID> before = movements(tenant, productId).stream()
                .map(StockMovementResponse::id)
                .toList();

        undo(tenant, session.id());

        List<StockMovementResponse> after = movements(tenant, productId);
        assertThat(after).extracting(StockMovementResponse::id).containsAll(before);
        assertThat(after).anySatisfy(movement -> {
            assertThat(movement.movementType()).isEqualTo(MovementType.ADJUSTMENT);
            assertThat(movement.quantity()).isEqualTo(-25);
        });
        // Back to the opening balance, and the ledger says so too.
        assertThat(productBySku(tenant, "LEDG-SU1").quantityOnHand()).isEqualTo(10);
        assertLedgerReconciles(tenant, "LEDG-SU1");
    }

    // ------------------------------------------------------ the compatibility shim

    /**
     * {@code POST /api/products/bulk-upload} - the endpoint contract section 3 keeps alive,
     * reimplemented on top of the new engine. It is the path most likely to have kept the old
     * behaviour of writing {@code quantity_on_hand} straight onto the row, because its own tests
     * assert on {@code BulkUploadResponse} and never look at the ledger.
     */
    @Test
    void theLegacyBulkUploadShimWritesALedgerMovementForEveryQuantityItSets() {
        TenantLoginResponse tenant = signup("Ledger Shim Co");
        byte[] file = legacyWorkbook(List.of(
                new Object[] {"Rice 50kg", "LEDG-B1", "A bag of rice", 42000, 60, 5, "KG", null, null},
                new Object[] {"Beans 100kg", "LEDG-B2", "A bag of beans", 52000, null, null, "KG", null, null}));

        ResponseEntity<BulkUploadResponse> response = restTemplate.exchange(
                "/api/products/bulk-upload", HttpMethod.POST, legacyMultipart(tenant, file), BulkUploadResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().createdCount()).isEqualTo(2);

        assertThat(productBySku(tenant, "LEDG-B1").quantityOnHand()).isEqualTo(60);
        assertThat(movements(tenant, productBySku(tenant, "LEDG-B1").id()))
                .as("the shim must not set quantity_on_hand without a movement - contract section 8.1 "
                        + "says EVERY path, and names this one")
                .singleElement()
                .satisfies(movement -> {
                    assertThat(movement.movementType()).isEqualTo(MovementType.IN);
                    assertThat(movement.quantity()).isEqualTo(60);
                });
        assertLedgerReconciles(tenant, "LEDG-B1");
        assertLedgerReconciles(tenant, "LEDG-B2");
    }

    // ------------------------------------------------------------------- FIFO

    /**
     * Contract section 8.9: FIFO orders by {@code (occurred_at, created_at)}, not by
     * {@code created_at} alone.
     *
     * <p>The two deliveries below are recorded in the opposite order to the one they happened in -
     * today's arrival is entered first, last week's is entered second, which is exactly what
     * bulk stock-in makes ordinary. Under the pre-V20 {@code ORDER BY created_at} the sale would
     * draw from today's lot, meaning stock that genuinely arrived later would be sold before
     * stock that had been sitting there for a week. The allocation table is where that shows: it
     * records which IN each OUT was funded from, so asserting on it asserts on the sort itself
     * rather than on a total that comes out the same either way.
     */
    @Test
    void fifoDrawsFromTheBackdatedDeliveryEvenThoughItWasRecordedSecond() {
        TenantLoginResponse tenant = signup("Ledger Fifo Co");
        commitCatalog(tenant, CATALOG_HEADERS + "Rice 50kg,LEDG-F1,,42000,,,KG,,,,,\n");
        UUID productId = productBySku(tenant, "LEDG-F1").id();

        // Recorded first, arrived today.
        UUID todaysLot = stockIn(tenant, productId, 10, OffsetDateTime.now()).movement().id();
        // Recorded second, arrived a week ago. This is the one FIFO must consume first.
        UUID lastWeeksLot =
                stockIn(tenant, productId, 10, OffsetDateTime.now().minusDays(7)).movement().id();

        stockOut(tenant, productId, 10);

        assertThat(allocations(tenant, lastWeeksLot))
                .as("the older delivery must be drawn from first, even though it was entered second")
                .singleElement()
                .satisfies(allocation -> assertThat(allocation.quantity()).isEqualTo(10));
        assertThat(allocations(tenant, todaysLot))
                .as("today's delivery must be untouched while older stock remains")
                .isEmpty();

        assertLedgerReconcilesFor(tenant, productId, "LEDG-F1");
    }

    // -------------------------------------------------------------- reconciliation

    /**
     * The invariant itself. {@code quantity_on_hand} is a cache; the ledger is the truth. Any
     * disagreement means some path wrote the cache without writing the ledger, and the ledger has
     * stopped being able to reconstruct stock.
     */
    private void assertLedgerReconciles(TenantLoginResponse tenant, String sku) {
        ProductResponse product = productBySku(tenant, sku);
        assertLedgerReconcilesFor(tenant, product.id(), sku);
    }

    private void assertLedgerReconcilesFor(TenantLoginResponse tenant, UUID productId, String label) {
        ProductResponse product = productById(tenant, productId);
        int fromLedger = movements(tenant, productId).stream()
                .mapToInt(BulkImportLedgerIntegrityIntegrationTest::signedQuantity)
                .sum();
        assertThat(product.quantityOnHand())
                .as("%s: quantity_on_hand must equal the signed sum of its stock movements", label)
                .isEqualTo(fromLedger);
    }

    /** IN adds, OUT subtracts, ADJUSTMENT is already signed - see StockMovement's javadoc. */
    private static int signedQuantity(StockMovementResponse movement) {
        return movement.movementType() == MovementType.OUT ? -movement.quantity() : movement.quantity();
    }

    // ----------------------------------------------------------------- helpers

    private List<StockMovementResponse> movements(TenantLoginResponse tenant, UUID productId) {
        TestPage<StockMovementResponse> page = restTemplate
                .exchange(
                        "/api/products/" + productId + "/stock/history?size=200",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<StockMovementResponse>>() {})
                .getBody();
        assertThat(page.totalElements())
                .as("the reconciliation must see every movement, not one page of them")
                .isLessThanOrEqualTo(200);
        return page.content();
    }

    private List<AllocationResponse> allocations(TenantLoginResponse tenant, UUID inMovementId) {
        return restTemplate
                .exchange(
                        "/api/stock-movements/" + inMovementId + "/allocations",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<List<AllocationResponse>>() {})
                .getBody();
    }

    private StockMutationResponse stockIn(
            TenantLoginResponse tenant, UUID productId, int quantity, OffsetDateTime occurredAt) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(
                                quantity, new BigDecimal("42000"), "received", null, null, null, null, occurredAt),
                        headers),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).as("stock-in fixture must succeed").isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void stockOut(TenantLoginResponse tenant, UUID productId, int quantity) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", quantity, "note", "sold"), headers),
                String.class);
        assertThat(response.getStatusCode()).as("stock-out fixture must succeed").isEqualTo(HttpStatus.OK);
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

    private void commitCatalog(TenantLoginResponse tenant, String csv) {
        commit(tenant, upload(tenant, csv, "PRODUCT_CATALOG", "CREATE_ONLY").id());
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

    private ProductResponse productById(TenantLoginResponse tenant, UUID productId) {
        return restTemplate
                .exchange(
                        "/api/products/" + productId,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        ProductResponse.class)
                .getBody();
    }

    /** The legacy shim takes .xlsx only, so this one file is built with POI rather than as CSV. */
    private byte[] legacyWorkbook(List<Object[]> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < LEGACY_COMPANY_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(LEGACY_COMPANY_HEADERS.get(i));
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
            throw new UncheckedIOException(e);
        }
    }

    private HttpEntity<MultiValueMap<String, Object>> legacyMultipart(TenantLoginResponse tenant, byte[] file) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders filePartHeaders = new HttpHeaders();
        filePartHeaders.setContentType(
                MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        ByteArrayResource fileResource = new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "products.xlsx";
            }
        };
        body.add("file", new HttpEntity<>(fileResource, filePartHeaders));
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
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
