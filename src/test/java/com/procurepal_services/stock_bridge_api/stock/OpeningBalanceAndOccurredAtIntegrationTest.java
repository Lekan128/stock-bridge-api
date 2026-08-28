package com.procurepal_services.stock_bridge_api.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.ApiError;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.MovementType;
import com.procurepal_services.stock_bridge_api.product.bulk.BulkUploadResponse;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.AllocationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMovementResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockOutRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * The two V20 ledger corrections BULK_IMPORT_DESIGN.md sections 3 and 8.4 call non-optional,
 * covered from the outside through the real HTTP + Spring Security filter chain against the
 * local docker-compose Postgres - see StockManagementIntegrationTest for why local Postgres over
 * Testcontainers, and for the self-contained-helpers-per-class convention this mirrors.
 *
 * <h2>Why these three tests and not more</h2>
 * Each one pins a failure that was silent, which is the only reason any of them is worth a test
 * rather than a code comment. None of the three produced an error before the fix; all three
 * produced a wrong answer confidently.
 *
 * <ul>
 *   <li><b>The opening-balance hole (section 3).</b> A bulk-imported quantity used to be typed
 *       straight onto {@code products.quantity_on_hand} with no {@code StockMovement} behind it.
 *       Nothing failed. The product showed the right number. But that stock had no lot, so it
 *       had no vendor, no cost basis and no delivery date - and when it was later sold, {@code
 *       resolveFifoAllocations} wrote no allocation row for it, so the recall/dispute trace the
 *       whole multi-vendor design exists to provide returned an empty list. An empty list, not
 *       an error. The first test walks that exact path end to end: import a row with quantity,
 *       sell some of it, and demand the allocation that used not to exist.</li>
 *   <li><b>FIFO order under backdating (section 8.4).</b> Lots used to be consumed in {@code
 *       created_at} order, which is data-entry order. Bulk stock-in's primary use case is
 *       recording deliveries that already happened, so its rows are routinely entered AFTER
 *       stock that arrived later - and FIFO then drew from the wrong lot and recorded that
 *       wrong answer permanently in {@code stock_movement_allocations}. The second test builds
 *       exactly that inversion (a lot created first but occurring later, a lot created second
 *       but occurring a month earlier) and asserts the sale draws from the older DELIVERY.</li>
 *   <li><b>A future delivery date.</b> Cheap to assert, and worth asserting because the failure
 *       mode is not a wrong number but a lot that sorts last in FIFO forever - never drawn from,
 *       while its stock stays on the books. The third test also pins the boundary: the rule
 *       allows a day of clock/timezone skew deliberately, so "tomorrow-ish" must still be
 *       accepted or the error message would be telling users that today is in the future.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class OpeningBalanceAndOccurredAtIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * The company (non-seller) template's column set - unit_price dropped entirely, the
     * unit_of_measure/packaging trio appended. Mirrors ProductExcelService.ALL_HEADER_NAMES the
     * same way ProductBulkImportExportIntegrationTest's own copy does; duplicated rather than
     * shared for the self-contained-helpers-per-class reason stated in the class javadoc.
     */
    private static final List<String> COMPANY_HEADERS = List.of(
            "name", "sku", "description", "cost_price", "quantity_on_hand", "low_stock_threshold",
            "unit_of_measure", "packaging_unit", "packaging_size");

    @Autowired
    private TestRestTemplate restTemplate;

    // -----------------------------------------------------------------------------------
    // Section 3 - an imported quantity is a real lot, and a later sale allocates against it.
    // -----------------------------------------------------------------------------------

    /**
     * The whole of the section 3 hole, walked from the outside. Before V20 every assertion below
     * the first three passed EXCEPT the last two - the product carried 40 units, the numbers all
     * looked right, and the movement history was simply empty. That emptiness is what made this
     * a hole rather than a rough edge: nothing anywhere reported a problem, and the recall trace
     * for the sale answered "no deliveries" instead of "this one".
     */
    @Test
    void importedQuantityWritesRealInMovementAndALaterSaleAllocatesAgainstThatLot() {
        TenantLoginResponse admin = signup("Opening Balance Co");
        byte[] file = workbook(List.<Object[]>of(
                new Object[] {"Rice 50kg", "OB-RICE-1", "imported with stock", 4500, 40, null, "KG", null, null}));

        ResponseEntity<BulkUploadResponse> upload = upload(admin, file);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = upload.getBody().products().get(0);

        // The counter still says what the spreadsheet said - the fix changes how it got there,
        // not what it is. costPrice comes out of stockIn's weighted-average recalculation now
        // rather than being copied from the cell, and against a product with zero on hand that
        // recalculation returns the delivery's own price: the same number, arrived at honestly.
        assertThat(product.quantityOnHand()).isEqualTo(40);
        assertThat(product.costPrice()).isEqualByComparingTo("4500");

        // The lot that did not used to exist.
        List<StockMovementResponse> history = history(admin, product.id());
        assertThat(history).hasSize(1);
        StockMovementResponse opening = history.get(0);
        assertThat(opening.movementType()).isEqualTo(MovementType.IN);
        assertThat(opening.quantity()).isEqualTo(40);
        assertThat(opening.unitPriceAtTime()).isEqualByComparingTo("4500");
        assertThat(opening.note()).isEqualTo("Opening balance (imported)");
        // Attributed to the uploader rather than to nobody - the acting user is threaded from
        // the controller for exactly this, the same way V19 did it for product creation.
        assertThat(opening.createdByUserId()).isNotNull();
        // An opening balance happened when it was counted, which is now - a backdated receipt
        // is a delivery, and deliveries are what bulk stock-in records.
        assertThat(opening.occurredAt()).isNotNull();
        assertThat(opening.occurredAt()).isCloseTo(opening.createdAt(), within(1, java.time.temporal.ChronoUnit.MINUTES));
        // No vendor: the product template has no vendor column yet, and inventing an
        // attribution would show up on the Vendors tab as fact.
        assertThat(opening.companyVendorId()).isNull();

        // The sale, and the allocation that used to be silently missing.
        StockMutationResponse sale = stockOut(admin, product.id(), 15).getBody();
        assertThat(sale.product().quantityOnHand()).isEqualTo(25);
        assertThat(sale.breakdown()).hasSize(1);
        assertThat(sale.breakdown().get(0).inMovementId()).isEqualTo(opening.id());
        assertThat(sale.breakdown().get(0).quantity()).isEqualTo(15);

        // And the same fact read from the other end - the forward trace from the delivery to
        // every sale it funded, which is the query MULTI_VENDOR_INVENTORY_DESIGN.md section 10
        // exists to answer and which returned nothing at all for imported stock before V20.
        List<AllocationResponse> allocations = allocations(admin, opening.id());
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).outMovementId()).isEqualTo(sale.movement().id());
        assertThat(allocations.get(0).quantity()).isEqualTo(15);
    }

    /**
     * The other half of the same rule, and the reason the fix is "any row CARRYING quantity"
     * rather than "every row": a catalog row with no stock is the ordinary "add product lines I
     * don't have stock of yet" case (design doc 6.7's matrix), and it must not manufacture a
     * zero-quantity movement - {@code stock_movements} has no valid representation for one
     * anyway (see V4's quantity CHECK), and a ledger full of nothing-happened rows is worse than
     * no rows.
     */
    @Test
    void importedRowWithNoQuantityWritesNoMovementAtAll() {
        TenantLoginResponse admin = signup("No Opening Balance Co");
        byte[] file = workbook(List.<Object[]>of(
                new Object[] {"Garri 25kg", "OB-GARRI-1", "catalog only", 3000, null, null, "KG", null, null}));

        ResponseEntity<BulkUploadResponse> upload = upload(admin, file);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ProductResponse product = upload.getBody().products().get(0);

        assertThat(product.quantityOnHand()).isZero();
        // costPrice is still seeded from the cell - a row may carry a cost with no quantity
        // ("this is what we pay, we just have none right now"), and there is no movement for
        // that case to hang a cost on.
        assertThat(product.costPrice()).isEqualByComparingTo("3000");
        assertThat(history(admin, product.id())).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Section 8.4 - FIFO orders by occurred_at, with created_at as the tiebreak.
    // -----------------------------------------------------------------------------------

    /**
     * The inversion that {@code created_at}-only ordering got wrong, built deliberately: the lot
     * recorded FIRST is the one that arrived LAST. Under the old ordering the sale would have
     * drawn from lot A (entered first), which is not FIFO - it is first-entered-first-out, and
     * it is the answer bulk stock-in would have produced for essentially every file it exists to
     * accept, since backdating is that feature's normal case rather than its edge case.
     *
     * <p>The damage was never a visibly wrong number. Both lots hold the same product at the
     * same quantity, so {@code quantityOnHand} lands on 10 either way. What differs is which
     * delivery the permanent {@code StockMovementAllocation} row names - and that row is the
     * whole recall/dispute trace.
     */
    @Test
    void fifoDrawsTheBackdatedLotFirstEvenThoughItWasRecordedSecond() {
        TenantLoginResponse admin = signup("Backdated FIFO Co");
        ProductResponse product = createProduct(admin, "FIFO-OCC-1", "KG");

        // Recorded first, arrived today.
        StockMutationResponse lotRecordedFirst =
                stockIn(admin, product.id(), 10, new BigDecimal("100.00"), OffsetDateTime.now()).getBody();
        // Recorded second, arrived a month ago - the backdated bulk stock-in row.
        OffsetDateTime aMonthAgo = OffsetDateTime.now().minusDays(30);
        StockMutationResponse lotRecordedSecond =
                stockIn(admin, product.id(), 10, new BigDecimal("100.00"), aMonthAgo).getBody();

        // The backdate round-trips rather than being quietly replaced by now().
        assertThat(lotRecordedSecond.movement().occurredAt())
                .isCloseTo(aMonthAgo, within(1, java.time.temporal.ChronoUnit.MINUTES));
        // ... while created_at stays the write-time fact, which is what makes it a stable
        // tiebreak underneath occurred_at rather than a second copy of the same information.
        // Read back from the persisted history (newest-written first) rather than from the
        // mutation responses: @CreationTimestamp is assigned when Hibernate flushes the insert,
        // which happens after the response DTO is composed, so created_at is legitimately null
        // on a stock-in response and only the round trip can prove anything about it. occurred_at
        // has no such gap - the service states it explicitly before the entity is built.
        List<StockMovementResponse> lots = history(admin, product.id());
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).id()).isEqualTo(lotRecordedSecond.movement().id());
        assertThat(lots.get(0).createdAt())
                .as("the second lot was WRITTEN later")
                .isAfter(lots.get(1).createdAt());
        assertThat(lots.get(0).occurredAt())
                .as("...and yet OCCURRED earlier - the inversion this whole column exists for")
                .isBefore(lots.get(1).occurredAt());

        StockMutationResponse sale = stockOut(admin, product.id(), 10).getBody();

        assertThat(sale.breakdown()).hasSize(1);
        assertThat(sale.breakdown().get(0).inMovementId())
                .as("FIFO must draw the older DELIVERY, not the older data-entry row")
                .isEqualTo(lotRecordedSecond.movement().id());
        assertThat(sale.breakdown().get(0).quantity()).isEqualTo(10);
        // The receipt line ("10 from the 30-days-ago delivery") reads the arrival date, not the
        // entry date - see StockMutationResponse.AllocationBreakdown.
        assertThat(sale.breakdown().get(0).inMovementOccurredAt())
                .isCloseTo(aMonthAgo, within(1, java.time.temporal.ChronoUnit.MINUTES));

        // Asserted from both ends, because "drew from B" and "did not draw from A" are separate
        // claims and only the pair rules out an allocation being written against both.
        assertThat(allocations(admin, lotRecordedSecond.movement().id())).hasSize(1);
        assertThat(allocations(admin, lotRecordedFirst.movement().id())).isEmpty();
    }

    // -----------------------------------------------------------------------------------
    // Section 8.4 - a delivery cannot have arrived on a date that has not happened yet.
    // -----------------------------------------------------------------------------------

    /**
     * Rejected, with a sentence rather than a constraint name. The database's own
     * {@code chk_stock_movements_occurred_at_not_future} is the backstop under this, not the
     * gate: a raw CHECK violation surfaces as a 500-shaped error naming a column, and design doc
     * 9.6 is explicit that a column name is never an error subject.
     *
     * <p>400 rather than 409 deliberately - nothing about the tenant's current state conflicts
     * here, the request is simply not a thing that can have happened. Contrast the oversell
     * case, which is 409 precisely because the same request would have been fine yesterday.
     */
    @Test
    void aDeliveryDateInTheFutureIsRejected() {
        TenantLoginResponse admin = signup("Future Date Co");
        ProductResponse product = createProduct(admin, "FIFO-FUTURE-1", "KG");

        ResponseEntity<ApiError> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(
                                5, new BigDecimal("100.00"), null, null, null, null, null,
                                OffsetDateTime.now().plusDays(10)),
                        authHeaders(admin)),
                ApiError.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).contains("cannot be in the future");
        // Nothing landed - the rejection happens before the ledger write, not after it.
        assertThat(history(admin, product.id())).isEmpty();
    }

    /**
     * The boundary the rule deliberately leaves open. A {@code received_date} cell is a DATE, so
     * it arrives as midnight in somebody's timezone, and a user's own machine may run minutes or
     * hours ahead of this server - so a delivery entered as "today" is routinely a few hours
     * past {@code now()} through nobody's fault. Refusing those would mean telling a user that
     * today is in the future. What the rule exists to catch is a mistyped year, and a day of
     * grace catches that exactly as well.
     */
    @Test
    void aDeliveryDateWithinTheClockSkewGraceIsAccepted() {
        TenantLoginResponse admin = signup("Clock Skew Co");
        ProductResponse product = createProduct(admin, "FIFO-SKEW-1", "KG");

        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + product.id() + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(
                                5, new BigDecimal("100.00"), null, null, null, null, null,
                                OffsetDateTime.now().plusHours(6)),
                        authHeaders(admin)),
                StockMutationResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().product().quantityOnHand()).isEqualTo(5);
    }

    // -----------------------------------------------------------------------------------
    // Helpers - self-contained per class, matching StockManagementIntegrationTest.
    // -----------------------------------------------------------------------------------

    private static org.assertj.core.data.TemporalUnitOffset within(long amount, java.time.temporal.ChronoUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(amount, unit);
    }

    private ResponseEntity<StockMutationResponse> stockIn(
            TenantLoginResponse admin, UUID productId, int quantity, BigDecimal unitPrice, OffsetDateTime occurredAt) {
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(
                        new StockInRequest(quantity, unitPrice, null, null, null, null, null, occurredAt),
                        authHeaders(admin)),
                StockMutationResponse.class);
    }

    private ResponseEntity<StockMutationResponse> stockOut(TenantLoginResponse admin, UUID productId, int quantity) {
        return restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-out",
                HttpMethod.POST,
                new HttpEntity<>(new StockOutRequest(quantity, null, null), authHeaders(admin)),
                StockMutationResponse.class);
    }

    private List<StockMovementResponse> history(TenantLoginResponse admin, UUID productId) {
        ResponseEntity<TestPage<StockMovementResponse>> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/history",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody().content();
    }

    private List<AllocationResponse> allocations(TenantLoginResponse admin, UUID inMovementId) {
        ResponseEntity<List<AllocationResponse>> response = restTemplate.exchange(
                "/api/stock-movements/" + inMovementId + "/allocations",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(admin)),
                new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    /** A plain product with no vendor line, so stock-in may be recorded without a companyVendorId. */
    private ProductResponse createProduct(TenantLoginResponse admin, String sku, String unitOfMeasure) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders productPartHeaders = new HttpHeaders();
        productPartHeaders.setContentType(MediaType.APPLICATION_JSON);
        body.add(
                "product",
                new HttpEntity<>(
                        new CreateProductRequest(
                                sku + " product", sku, null, null, null, unitOfMeasure, null, null, null),
                        productPartHeaders));

        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate
                .exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody();
    }

    private ResponseEntity<BulkUploadResponse> upload(TenantLoginResponse admin, byte[] file) {
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

        HttpHeaders headers = authHeaders(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange(
                "/api/products/bulk-upload", HttpMethod.POST, new HttpEntity<>(body, headers), BulkUploadResponse.class);
    }

    private byte[] workbook(List<Object[]> rows) {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int i = 0; i < COMPANY_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(COMPANY_HEADERS.get(i));
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

    private record TestPage<T>(List<T> content) {
    }
}
