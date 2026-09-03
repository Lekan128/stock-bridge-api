package com.procurepal_services.stock_bridge_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorRequest;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.CompanyVendorResponse;
import com.procurepal_services.stock_bridge_api.companyvendor.dto.ProductVendorResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportResultResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportRowResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.ImportSessionResponse;
import com.procurepal_services.stock_bridge_api.imports.dto.PatchRowRequest;
import com.procurepal_services.stock_bridge_api.imports.dto.ValueMappingRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The import session, end to end through the real HTTP stack - see AuthIntegrationTest for why
 * local Postgres over Testcontainers.
 *
 * <p>Everything asserted here is a claim the specs make in words and that nothing else in the
 * suite can check: that an update row's quantity is ignored <em>and said so</em>, that a repeated
 * product code with a second supplier stops being an error, that one answer settles every row
 * carrying a name, that a double-clicked Commit imports once, and that an undo refuses cleanly
 * rather than unpicking a sale.
 *
 * <p>Files are uploaded as CSV rather than .xlsx throughout. It goes through the same
 * {@code SpreadsheetReader} door, the same column mapper and the same handlers - only M2's reader
 * differs, and M2 already tests that - so the tests stay legible instead of being half POI
 * scaffolding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class BulkImportSessionIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private static final String CATALOG_HEADERS =
            "name,sku,description,cost_price,quantity_on_hand,low_stock_threshold,unit_of_measure,"
                    + "packaging_unit,packaging_size,vendor_name,vendor_sku,is_preferred_vendor\n";

    /**
     * The same columns as a tenant downloads today - UNIT_UX_CONTRACT.md section 9.4's spellings,
     * in section 9's grouping. {@link #CATALOG_HEADERS} above is deliberately the OLD set, so
     * every other test in this class doubles as the header-alias test; this one is used where
     * the point being made is about the current sheet.
     */
    private static final String CATALOG_HEADERS_TODAY =
            "name,sku,description,stock_unit,pack,units_per_pack,opening_stock,low_stock_alert_at,"
                    + "cost_price,vendor_name,vendor_sku,is_preferred_vendor\n";

    private static final String STOCK_IN_HEADERS =
            "sku,product_name,vendor_name,quantity,unit,unit_cost,packaging_size,received_date,reference\n";

    @Autowired
    private TestRestTemplate restTemplate;

    // ------------------------------------------------------------ import modes

    /**
     * BULK_IMPORT_DESIGN.md section 6.3, top-left cell: an existing code under CREATE_ONLY is an
     * error on that row. This is today's behaviour, kept as the default (decision 13.1) because it
     * is the safe answer for someone who has not thought about it.
     */
    @Test
    void createOnlyRejectsARowWhoseProductCodeAlreadyExists() {
        TenantLoginResponse tenant = signup("Mode Create Only Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,MODE-1,,42000,,,KG,,,,,\n");

        ImportSessionResponse session =
                upload(tenant, CATALOG_HEADERS + "Rice 50kg,MODE-1,,44000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");

        assertThat(session.status().name()).isEqualTo("NEEDS_REVIEW");
        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "ERROR").errors())
                .anySatisfy(error -> {
                    assertThat(error.column()).isEqualTo("sku");
                    assertThat(error.message()).contains("already stock");
                    // Design 9.6: the message names the product, never the column, and points at
                    // the mode that would have worked.
                    assertThat(error.message()).contains("Add or update");
                });
    }

    /**
     * Section 6.3, middle row: an existing code under CREATE_OR_UPDATE updates the changed fields.
     * This is the mode that makes "re-upload my supplier's updated price list" - the most natural
     * recurring workflow this feature has - possible at all.
     */
    @Test
    void createOrUpdateUpdatesAnExistingProductAndLeavesItsStockAlone() {
        TenantLoginResponse tenant = signup("Mode Upsert Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,UPSERT-1,,42000,20,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS + "Rice 50kg premium,UPSERT-1,Better rice,44000,,5,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_OR_UPDATE");
        assertThat(session.status().name()).isEqualTo("READY");
        assertThat(firstRow(tenant, session, "ALL").resolvedEntityId()).isNotNull();

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.createdCount()).isZero();

        ProductResponse product = productBySku(tenant, "UPSERT-1");
        assertThat(product.name()).isEqualTo("Rice 50kg premium");
        assertThat(product.lowStockThreshold()).isEqualTo(5);
        // The ledger owns the counter and no update row may move it - contract section 8.1.
        assertThat(product.quantityOnHand()).isEqualTo(20);
    }

    /** Section 6.3, bottom-right cell: a new code under UPDATE_ONLY is an error on that row. */
    @Test
    void updateOnlyRejectsARowWhoseProductCodeDoesNotExistYet() {
        TenantLoginResponse tenant = signup("Mode Update Only Co");

        ImportSessionResponse session = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,NEVER-SEEN-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "UPDATE_ONLY");

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "ERROR").errors())
                .anySatisfy(error -> {
                    assertThat(error.column()).isEqualTo("sku");
                    assertThat(error.message()).contains("no product in your catalog with this code");
                    // Non-null on EVERY error, even one made once. Contract section 4 wants the
                    // count to arrive with the error; the frontend is what decides that a count
                    // of one does not deserve a bulk button.
                    assertThat(error.bulkFixCount()).isEqualTo(1);
                });
    }

    // ------------------------------------------------------- the loud warnings

    /**
     * Contract section 8.8, and the single most dangerous thing this feature could do quietly.
     *
     * <p>An update row's {@code opening_stock} is ignored - quantity moves through the ledger
     * or not at all - and the review grid has to SAY so. A user re-importing a price list that
     * happens to carry a stock column would otherwise believe they had just corrected their
     * inventory. It is a warning rather than an error because the row's other columns are
     * perfectly good, so the assertion is specifically that the row is WARNING and not ERROR, and
     * that the sentence points at bulk stock-in.
     */
    @Test
    void anUpdateRowsIgnoredQuantityProducesAWarningAndNotSilence() {
        TenantLoginResponse tenant = signup("Ignored Quantity Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,IGNQTY-1,,42000,20,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS + "Rice 50kg,IGNQTY-1,,44000,999,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_OR_UPDATE");

        assertThat(session.warningCount()).isEqualTo(1);
        assertThat(session.errorCount()).isZero();
        ImportRowResponse row = firstRow(tenant, session, "ALL");
        assertThat(row.status().name()).isEqualTo("WARNING");
        assertThat(row.warnings()).anySatisfy(warning -> {
            // UNIT_UX_CONTRACT.md section 5.1 renamed the column, and the field key follows the
            // header (BULK_IMPORT_CONTRACT.md section 5). The old spelling stays an accepted
            // header on the way IN - see theOldQuantityOnHandHeaderStillMaps - but what comes back
            // out is the current key, because that is what the grid renders a cell for.
            assertThat(warning.column()).isEqualTo("opening_stock");
            assertThat(warning.message()).contains("ignored when updating");
            assertThat(warning.message()).contains("Record stock you received");
        });

        // A warning does not block: the file is still importable, and the quantity still does
        // nothing.
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "IGNQTY-1").quantityOnHand()).isEqualTo(20);
    }

    /**
     * Contract section 8.12 / MULTI_VENDOR_INVENTORY_DESIGN.md section 5.3: once a product has any
     * movement, its unit of measure is fixed, and an update row trying to change it is an ERROR on
     * that cell with the reason spelled out - not a silent ignore. Everything already in the
     * ledger is counted in the old unit, so changing it retroactively rewrites history.
     */
    @Test
    void anUpdateRowCannotChangeTheUnitOfMeasureOnceStockHasMoved() {
        TenantLoginResponse tenant = signup("Immutable Unit Co");
        // The opening balance writes a real movement, which is what makes the unit immutable.
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,IMMUT-1,,42000,20,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS + "Rice 50kg,IMMUT-1,,42000,,,LITER,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_OR_UPDATE");

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "ERROR").errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("stock_unit");
            assertThat(error.message()).contains("already had stock recorded");
            // The reason spelled out, not just a refusal.
            assertThat(error.message()).contains("Kilogram");
        });
    }

    // --------------------------------------------------------- continuation rows

    /**
     * Design 7.1's one-to-many convention: a repeated product code naming a DIFFERENT supplier is
     * a second vendor line, not a duplicate - and the response carries {@code continuationOf} so
     * the grid can nest it under its parent and the file's structure is legible to someone who has
     * never heard of the convention.
     *
     * <p>The third row here repeats the same code with the SAME supplier, which stays an error.
     * Both halves matter: narrowing the rule without keeping the genuine duplicate an error would
     * mean silently creating one product from two rows that meant two.
     */
    @Test
    void aRepeatedProductCodeWithASecondSupplierIsAContinuationRowAndNotADuplicate() {
        TenantLoginResponse tenant = signup("Continuation Co");
        createVendor(tenant, "Dangote Nigeria Plc");
        createVendor(tenant, "Ade Foods Ltd");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,CONT-1,,42000,,,KG,,,Dangote Nigeria Plc,DN-1,TRUE\n"
                        + ",CONT-1,,41000,,,,,,Ade Foods Ltd,AF-1,\n"
                        + ",CONT-1,,40000,,,,,,Ade Foods Ltd,AF-2,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        List<ImportRowResponse> rows = rows(tenant, session.id(), "ALL");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).continuationOf()).isNull();
        assertThat(rows.get(1).continuationOf())
                .as("row 3 is a second supplier for the product on row 2")
                .isEqualTo(rows.get(0).excelRow());
        assertThat(rows.get(1).errors()).isEmpty();
        assertThat(rows.get(2).errors())
                .as("the same supplier twice is still a duplicate")
                .anySatisfy(error -> assertThat(error.column()).isEqualTo("sku"));

        // Skip the genuine duplicate and the file is importable - one product, two suppliers.
        skipRow(tenant, session.id(), rows.get(2).id());
        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.createdCount()).isEqualTo(1);

        ProductResponse product = productBySku(tenant, "CONT-1");
        assertThat(vendorLines(tenant, product.id()))
                .extracting(ProductVendorResponse::companyVendorName)
                .containsExactlyInAnyOrder("Dangote Nigeria Plc", "Ade Foods Ltd");
    }

    // -------------------------------------------------------- value resolution

    /**
     * Design 6.4, and the part that decides whether this feels smart or feels like a form: three
     * rows naming a supplier we do not have is ONE question, not three, and answering it once
     * settles all three.
     *
     * <p>Also design 13.2's inline vendor creation, which has to appear explicitly in the result
     * rather than happening silently - hence the assertion on {@code vendorsCreated}.
     */
    @Test
    void oneAnswerAboutAnUnknownSupplierSettlesEveryRowThatNamesThem() {
        TenantLoginResponse tenant = signup("Value Resolution Co");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,VR-1,,42000,,,KG,,,Dangote Ltd,,\n"
                        + "Beans 100kg,VR-2,,52000,,,KG,,,Dangote Ltd,,\n"
                        + "Garri 25kg,VR-3,,22000,,,KG,,,Dangote Ltd,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.unresolvedValues()).singleElement().satisfies(value -> {
            assertThat(value.column()).isEqualTo("vendor_name");
            assertThat(value.columnLabel()).isEqualTo("Supplier");
            assertThat(value.value()).isEqualTo("Dangote Ltd");
            assertThat(value.rowCount()).as("three rows, one decision").isEqualTo(3);
            assertThat(value.excelRows()).containsExactly(2, 3, 4);
            assertThat(value.allowCreateNew()).isTrue();
            assertThat(value.resolution()).isNull();
        });

        ImportSessionResponse answered = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "vendor_name",
                        "Dangote Ltd",
                        new ValueResolution("CREATE_NEW", null, null, Map.of("name", "Dangote Ltd"))));
        assertThat(answered.unresolvedValues()).singleElement().satisfies(value -> assertThat(value.resolution())
                .isNotNull());
        assertThat(answered.warningCount()).as("the unknown-supplier warning is gone from every row").isZero();

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.createdCount()).isEqualTo(3);
        assertThat(result.vendorsCreated()).as("said out loud, never silently").isEqualTo(1);
        assertThat(result.lines())
                .extracting(line -> line.text())
                .anySatisfy(text -> assertThat(text).contains("added to your directory"));

        assertThat(vendorLines(tenant, productBySku(tenant, "VR-1").id()))
                .extracting(ProductVendorResponse::companyVendorName)
                .containsExactly("Dangote Ltd");
    }

    // ------------------------------------------------------------- the ISSUES filter

    /**
     * Contract section 3: {@code status=ISSUES} means ERROR+WARNING in ONE correctly-paged
     * response. It is the review screen's default view, so a warning that fell off the seam
     * between two separately-paged requests would be exactly the silent drop section 8.8 forbids.
     */
    @Test
    void theIssuesFilterReturnsErrorsAndWarningsInOneResponse() {
        TenantLoginResponse tenant = signup("Issues Filter Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,ISS-WARN,,42000,10,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,ISS-WARN,,42000,999,,KG,,,,,\n"
                        + "Beans 100kg,ISS-ERR,,52000,,,NOT-A-UNIT,,,,,\n"
                        + "Garri 25kg,ISS-OK,,22000,,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_OR_UPDATE");

        assertThat(session.warningCount()).isEqualTo(1);
        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(session.validCount()).isEqualTo(1);

        TestPage<ImportRowResponse> issues = rowsPage(tenant, session.id(), "ISSUES");
        assertThat(issues.totalElements()).isEqualTo(2);
        assertThat(issues.content())
                .extracting(row -> row.status().name())
                .containsExactlyInAnyOrder("ERROR", "WARNING");

        assertThat(rowsPage(tenant, session.id(), "ALL").totalElements()).isEqualTo(3);
    }

    /**
     * Contract section 4: {@code bulkFixCount} arrives with the error, because the twelve broken
     * rows may be spread over four pages and a count derived from what is on screen would be wrong
     * in a way nobody would notice until they trusted it. This is what powers "Fix all 3 rows".
     *
     * <p>The broken value is {@code KGX} rather than the contract's own {@code KGS} example, and
     * that is worth recording: M2's forgiving parse now absorbs {@code KGS} - along with
     * {@code kilo}, {@code kilogrammes} and {@code kg's} - so the example error in the spec is no
     * longer reachable. Design 5.2 predicted exactly that ("mostly handled by the forgiving
     * parse; whatever survives gets a dropdown"), and this test is about what survives.
     */
    @Test
    void anErrorSharedByThreeRowsCarriesItsBulkFixCountAndItsSuggestion() {
        TenantLoginResponse tenant = signup("Bulk Fix Co");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,BF-1,,42000,,,KGX,,,,,\n"
                        + "Beans 100kg,BF-2,,52000,,,KGX,,,,,\n"
                        + "Garri 25kg,BF-3,,22000,,,KGX,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.errorCount()).isEqualTo(3);
        // `raw` must stay populated on an error row. Validation nulls `normalized` on exactly the
        // cells it rejects, so the grid falls back to `raw` to show what the user actually typed -
        // without it the offending cell renders an em-dash directly above a message about its
        // contents, which is what M7 found and fixed on the frontend side. Design 9.3's mock
        // draws that cell containing the bad value, not a dash.
        assertThat(rows(tenant, session.id(), "ERROR")).allSatisfy(row -> {
            assertThat(row.raw()).containsEntry("stock_unit", "KGX");
            assertThat(row.normalized().get("stock_unit")).isNull();
        });
        assertThat(rows(tenant, session.id(), "ERROR")).allSatisfy(row -> assertThat(row.errors())
                .anySatisfy(error -> {
                    assertThat(error.column()).isEqualTo("stock_unit");
                    assertThat(error.bulkFixCount()).isEqualTo(3);
                    assertThat(error.suggestion()).isNotNull();
                    assertThat(error.suggestion().value()).isEqualTo("KG");
                }));

        // And the repair path the grid actually uses: PATCH one row, get it back re-validated.
        ImportRowResponse repaired = patchRow(
                tenant,
                session.id(),
                rows(tenant, session.id(), "ERROR").get(0).id(),
                Map.of("stock_unit", "KG"));
        assertThat(repaired.status().name()).isEqualTo("VALID");
        assertThat(repaired.errors()).isEmpty();
        // The two that are left now know they are two.
        assertThat(rows(tenant, session.id(), "ERROR")).hasSize(2).allSatisfy(row -> assertThat(row.errors())
                .anySatisfy(error -> assertThat(error.bulkFixCount()).isEqualTo(2)));
    }

    // ------------------------------------------------------------- idempotency

    /**
     * Design 11: a double-clicked Commit must not double-import. The transition READY to
     * COMMITTING happens once, under a row lock, and the loser is told which case it hit rather
     * than being left to guess whether their stock went in twice.
     */
    @Test
    void asecondCommitIsRefusedRatherThanImportingTwice() {
        TenantLoginResponse tenant = signup("Idempotent Commit Co");
        ImportSessionResponse session = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,IDEM-1,,42000,10,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");

        ResponseEntity<ImportResultResponse> first = commitRaw(tenant, session.id());
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().createdCount()).isEqualTo(1);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/imports/" + session.id() + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already been imported");

        // One product, not two, and one lot, not two.
        assertThat(productBySku(tenant, "IDEM-1").quantityOnHand()).isEqualTo(10);
    }

    // -------------------------------------------------------------------- undo

    /**
     * Design 6.6: deactivate what it created, reverse the stock it recorded, never delete a ledger
     * row (contract section 8.10). The compensating movement is the point - the counter comes back
     * to zero because a second, opposite fact was recorded, not because the first one was erased.
     */
    @Test
    void undoingACatalogImportDeactivatesWhatItCreatedAndReversesTheOpeningStock() {
        TenantLoginResponse tenant = signup("Undo Catalog Co");
        ImportSessionResponse session = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,UNDO-1,,42000,40,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.undoable()).isTrue();
        assertThat(productBySku(tenant, "UNDO-1").quantityOnHand()).isEqualTo(40);

        ResponseEntity<ImportResultResponse> undone = restTemplate.exchange(
                "/api/imports/" + session.id() + "/undo",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);

        assertThat(undone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().undoable()).as("undoing twice would double the reversal").isFalse();

        ProductResponse product = productBySku(tenant, "UNDO-1");
        assertThat(product.active()).isFalse();
        assertThat(product.quantityOnHand()).isZero();
    }

    /**
     * The refusal, which design 6.6 insists is a message and not a failure: "3 of these 40
     * deliveries have already been sold from, so this import can't be undone as a batch."
     *
     * <p>The body shape is the load-bearing part. The frontend recognises it structurally, by
     * {@code typeof message === 'string' && Array.isArray(blockers)}, and degrades to a generic
     * toast on anything else - so a 409 carrying a plain error would still be a true sentence
     * while losing every blocker, and the user would never learn WHICH delivery is in the way.
     */
    @Test
    void undoingAStockInImportIsRefusedWithBlockersOnceALotHasBeenSoldFrom() {
        TenantLoginResponse tenant = signup("Undo Blocked Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,UNDOBLK-1,,42000,,,KG,,,,,\n");
        ProductResponse product = productBySku(tenant, "UNDOBLK-1");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS + "UNDOBLK-1,Rice 50kg,,40,KG,42000,,,WB-1\n",
                "STOCK_IN",
                null);
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "UNDOBLK-1").quantityOnHand()).isEqualTo(40);

        stockOut(tenant, product.id(), 5);

        ResponseEntity<UndoBlockedBody> refused = restTemplate.exchange(
                "/api/imports/" + session.id() + "/undo",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                UndoBlockedBody.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        UndoBlockedBody body = refused.getBody();
        assertThat(body).isNotNull();
        assertThat(body.message()).contains("already been sold from");
        // One delivery, one blocker - the commonest shape a blocked undo takes, and the one the
        // shared template used to render as "1 of these 1 delivery have already been sold from".
        assertThat(body.message())
                .as("the refusal is prose a person would write, not a template with its verb left plural")
                .isEqualTo("This delivery has already been sold from, so this import can't be undone all at once.");
        assertThat(body.blockers()).singleElement().satisfies(blocker -> {
            assertThat(blocker.label()).isEqualTo("Rice 50kg");
            assertThat(blocker.reason()).isEqualTo("Already sold from");
            assertThat(blocker.entityId()).isEqualTo(product.id().toString());
        });

        // Refused means refused: the ledger is untouched and the stock is where the sale left it.
        assertThat(productBySku(tenant, "UNDOBLK-1").quantityOnHand()).isEqualTo(35);
    }

    // ----------------------------------------------------------------- stock-in

    /**
     * The stock-in happy path plus contract section 8.11, which is not an edge case: the
     * pre-filled sheet is a download of the whole catalog, so a two-line delivery arrives with
     * most quantity cells empty, and those rows are a SILENT skip - not an error, and not a
     * warning either, because four hundred warnings is the same as none.
     */
    @Test
    void aStockInRowWithNoQuantityIsSilentlySkippedAndTheRestIsRecorded() {
        TenantLoginResponse tenant = signup("Stock In Skip Co");
        commitCatalog(
                tenant,
                "CREATE_ONLY",
                CATALOG_HEADERS
                        + "Rice 50kg,SIS-1,,42000,,,KG,,,,,\n"
                        + "Beans 100kg,SIS-2,,52000,,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "SIS-1,Rice 50kg,,40,KG,42000,,,WB-9\n"
                        + "SIS-2,Beans 100kg,,,KG,,,,\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).isZero();
        assertThat(session.skippedCount()).as("the empty row is skipped, not flagged").isEqualTo(1);
        assertThat(session.status().name()).isEqualTo("READY");

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.movementsCreated()).isEqualTo(1);
        assertThat(productBySku(tenant, "SIS-1").quantityOnHand()).isEqualTo(40);
        assertThat(productBySku(tenant, "SIS-2").quantityOnHand()).isZero();
    }

    /**
     * Design 6.7's escape hatch, and the case that motivated it: "a supplier's truck arrives with
     * eleven things you stock and one you have never bought before". The unknown code becomes one
     * resolution card, "create this product" collects the minimum - a name and a base unit,
     * because a product with no unit cannot be stocked into - and the very row that asked for it
     * then stocks into it.
     */
    @Test
    void anUnknownProductCodeOnADeliveryCanBeCreatedInlineAndStockedIntoInTheSameImport() {
        TenantLoginResponse tenant = signup("Inline Product Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,INL-KNOWN,,42000,,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "INL-KNOWN,Rice 50kg,,10,KG,42000,,,WB-2\n"
                        + "INL-NEW,Palm Oil 25L,,4,LITER,18000,,,WB-2\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).as("an unmatched code is a real error until it is answered").isEqualTo(1);
        assertThat(session.unresolvedValues()).anySatisfy(value -> {
            assertThat(value.column()).isEqualTo("sku");
            assertThat(value.kind().name()).isEqualTo("PRODUCT");
            assertThat(value.value()).isEqualTo("INL-NEW");
            assertThat(value.allowCreateNew()).isTrue();
            assertThat(value.allowSkipRows()).as("leaving the delivery out is always an option").isTrue();
        });

        ImportSessionResponse answered = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "sku",
                        "INL-NEW",
                        new ValueResolution(
                                "CREATE_NEW", null, null, Map.of("name", "Palm Oil 25L", "unitOfMeasure", "LITER"))));
        assertThat(answered.errorCount()).isZero();
        assertThat(answered.status().name()).isEqualTo("READY");

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.productsCreated()).as("said out loud in the result, never silently").isEqualTo(1);
        assertThat(result.movementsCreated()).isEqualTo(2);
        assertThat(productBySku(tenant, "INL-NEW").quantityOnHand()).isEqualTo(4);
    }

    /**
     * Answering "leave those rows out" has to make the question go away.
     *
     * <p>Found by the frontend audit: a value resolved by SKIP_ROWS was still being listed as
     * unresolved, so the panel above the grid kept asking a question the user had already
     * answered - the most irritating possible failure of a screen whose entire pitch is "one
     * decision, forty-seven rows". A value whose only remaining rows are skipped is not a
     * question any more, and both handlers drop it.
     */
    @Test
    void skippingTheRowsThatCarryAnUnknownValueRemovesTheQuestionEntirely() {
        TenantLoginResponse tenant = signup("Skip Rows Resolution Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,SKR-KNOWN,,42000,,,KG,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "SKR-KNOWN,Rice 50kg,,10,KG,42000,,,WB-3\n"
                        + "SKR-GHOST,Something we never bought,,4,KG,18000,,,WB-3\n",
                "STOCK_IN",
                null);
        assertThat(session.unresolvedValues())
                .anySatisfy(value -> assertThat(value.value()).isEqualTo("SKR-GHOST"));

        ImportSessionResponse answered = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest("sku", "SKR-GHOST", new ValueResolution("SKIP_ROWS", null, null, null)));

        assertThat(answered.unresolvedValues())
                .as("the panel must not keep asking about rows that are no longer going anywhere")
                .noneSatisfy(value -> assertThat(value.value()).isEqualTo("SKR-GHOST"));
        assertThat(answered.errorCount()).isZero();
        assertThat(answered.skippedCount()).isEqualTo(1);
        assertThat(answered.status().name()).isEqualTo("READY");

        ImportResultResponse result = commit(tenant, session.id());
        assertThat(result.movementsCreated()).isEqualTo(1);
        assertThat(result.productsCreated()).isZero();
    }

    /**
     * Contract section 3: the confirm screen is prose composed server-side (design 9.4), and the
     * button says what it does rather than "Confirm". Also contract section 8.6 - the words
     * "escrow", "session", "staging" and "batch" appear nowhere a user can see.
     */
    @Test
    void theConfirmScreensProseIsComposedByTheServerAndSaysWhatTheButtonWillDo() {
        TenantLoginResponse tenant = signup("Preview Prose Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,PRV-1,,42000,40,,KG,,,,,\n"
                        + "Beans 100kg,PRV-2,,52000,,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        PreviewBody preview = restTemplate
                .exchange(
                        "/api/imports/" + session.id() + "/preview",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        PreviewBody.class)
                .getBody();

        assertThat(preview.headline()).isEqualTo("Import 2 rows from products.csv");
        assertThat(preview.confirmLabel()).isEqualTo("Import 2 rows");
        assertThat(preview.blocked()).isFalse();
        assertThat(preview.lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("create");
            assertThat(line.text()).isEqualTo("2 new products");
        });
        // The stock line, because design 3's fix means an import now touches the ledger and
        // design 9.4 is explicit that a user must not discover that afterwards.
        assertThat(preview.lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("stock");
            assertThat(line.text()).isEqualTo("Opening balance of 40 kg recorded across 1 product");
        });
        for (PreviewLine line : preview.lines()) {
            assertThat(line.text().toLowerCase()).doesNotContain("escrow", "session", "staging", "batch");
        }
    }

    /**
     * Design 6.2: someone using our own template must never see a mapping screen, and someone
     * pasting a supplier's price list needs one. The same file with unrecognisable headers for the
     * required columns is the only thing that forces it.
     */
    @Test
    void theMappingStepIsSkippedEntirelyWhenEveryRequiredColumnResolves() {
        TenantLoginResponse tenant = signup("Mapping Skip Co");

        ImportSessionResponse ourTemplate = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,MAP-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(ourTemplate.needsMapping()).isFalse();
        assertThat(ourTemplate.requiredFieldsMissing()).isEmpty();

        // "Item" and "Code" are in the alias table; "Widget Label" is in nothing.
        ImportSessionResponse aliased =
                upload(tenant, "Item,Code,Cost\nRice 50kg,MAP-2,42000\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(aliased.needsMapping()).as("a small alias table earns its place").isFalse();
        assertThat(aliased.columnMapping()).containsEntry("item", "name").containsEntry("code", "sku");

        ImportSessionResponse unmappable =
                upload(tenant, "Widget Label,Thing Ref\nRice 50kg,MAP-3\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(unmappable.needsMapping()).isTrue();
        assertThat(unmappable.requiredFieldsMissing()).contains("name", "sku");
        assertThat(unmappable.unmappedHeaders()).contains("widget_label", "thing_ref");

        // And the mapping screen's PATCH re-reads the file through the new lens.
        ImportSessionResponse mapped = patchMapping(
                tenant, unmappable.id(), Map.of("widget_label", "name", "thing_ref", "sku"));
        assertThat(mapped.needsMapping()).isFalse();
        assertThat(mapped.status().name()).isEqualTo("READY");
        assertThat(firstRow(tenant, mapped, "ALL").normalized()).containsEntry("sku", "MAP-3");
    }

    // ------------------------------------------------------------- the async path

    /**
     * M5's first hard requirement, and the one that fails most silently: above
     * {@code ASYNC_ROW_THRESHOLD} the commit answers a literal <b>202</b>, and nothing else makes
     * the client poll.
     *
     * <p>The frontend inspects {@code response.status === 202} and on anything else treats the
     * body as the finished result - so a 200 with an empty body here would send the confirm
     * screen to a result that does not exist yet, with no error anywhere to explain it. This test
     * then does exactly what the client does: polls {@code GET /{id}} until the status leaves
     * COMMITTING, and reads {@code GET /{id}/result}. The status reaching COMMITTED is the other
     * half of the requirement - a run that stayed in COMMITTING would poll to the five-minute
     * ceiling and then lie to the user about having failed.
     */
    @Test
    void aFileOverTheAsyncThresholdCommitsBehindALiteral202AndFinishesOnItsOwn() throws Exception {
        TenantLoginResponse tenant = signup("Async Commit Co");
        StringBuilder csv = new StringBuilder(CATALOG_HEADERS);
        int rows = 520;
        for (int i = 1; i <= rows; i++) {
            csv.append("Product ").append(i).append(",ASYNC-").append(i).append(",,1000,,,KG,,,,,\n");
        }
        ImportSessionResponse session = upload(tenant, csv.toString(), "PRODUCT_CATALOG", "CREATE_ONLY");
        assertThat(session.rowCount()).isEqualTo(rows);
        assertThat(session.status().name()).isEqualTo("READY");

        ResponseEntity<String> accepted = restTemplate.exchange(
                "/api/imports/" + session.id() + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                String.class);
        assertThat(accepted.getStatusCode()).as("literal 202 - nothing else starts the client polling")
                .isEqualTo(HttpStatus.ACCEPTED);

        ImportSessionResponse settled = pollUntilSettled(tenant, session.id());
        assertThat(settled.status().name()).isEqualTo("COMMITTED");

        ImportResultResponse result = restTemplate
                .exchange(
                        "/api/imports/" + session.id() + "/result",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        ImportResultResponse.class)
                .getBody();
        assertThat(result.createdCount()).isEqualTo(rows);
        assertThat(result.undoable()).isTrue();
    }

    // ------------------------------------------------------- report and access

    /**
     * Design 9.5's report - every row and what happened to it, including the ones that did
     * nothing, because "what happened to this line of my file?" is the only question it is ever
     * opened to answer.
     *
     * <p>Reachable by a plain authenticated GET, which is what the frontend's blob download
     * needs: the bearer token lives in memory, so these can never be {@code <a href>}s.
     */
    @Test
    void theReportIsAnAuthenticatedGetCoveringEveryRowIncludingTheSkippedOnes() {
        TenantLoginResponse tenant = signup("Report Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,RPT-1,,42000,,,KG,,,,,\n"
                        + "Beans 100kg,RPT-2,,52000,,,KG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");
        skipRow(tenant, session.id(), rows(tenant, session.id(), "ALL").get(1).id());
        commit(tenant, session.id());

        ResponseEntity<byte[]> report = restTemplate.exchange(
                "/api/imports/" + session.id() + "/report",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tenant)),
                byte[].class);

        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(report.getHeaders().getFirst("Content-Disposition")).contains("import-report.xlsx");
        String text = new String(report.getBody(), StandardCharsets.ISO_8859_1);
        assertThat(text).as("a real .xlsx, not an error page rendered as bytes").startsWith("PK");
        assertThat(report.getBody().length).isGreaterThan(2000);
    }

    /**
     * An import is one company's private work in progress. Another tenant guessing the id in the
     * linkable {@code /app/products/import/:sessionId} URL gets 404 and not 403 - a 403 would
     * confirm the id exists, which is the one bit of information a probe is after.
     */
    @Test
    void anotherCompanyCannotSeeAnImportEvenWithItsId() {
        TenantLoginResponse owner = signup("Private Import Co");
        TenantLoginResponse stranger = signup("Nosy Neighbour Co");
        ImportSessionResponse session = upload(
                owner, CATALOG_HEADERS + "Rice 50kg,PRIV-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");

        ResponseEntity<String> peek = restTemplate.exchange(
                "/api/imports/" + session.id(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(stranger)),
                String.class);

        assertThat(peek.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The recent-imports list carries {@code summaryText} composed here, so the frontend never
     * string-builds a count - the same rule the preview and result lines follow (contract
     * section 4).
     */
    @Test
    void theRecentImportsListSaysWhatEachOneDidWithoutTheFrontendCountingAnything() {
        TenantLoginResponse tenant = signup("Recent Imports Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,RCNT-1,,42000,,,KG,,,,,\n");

        TestPage<SummaryBody> list = restTemplate
                .exchange(
                        "/api/imports?kind=PRODUCT_CATALOG",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<SummaryBody>>() {})
                .getBody();

        assertThat(list.content()).singleElement().satisfies(summary -> {
            assertThat(summary.status()).isEqualTo("COMMITTED");
            assertThat(summary.summaryText()).isEqualTo("1 created");
            assertThat(summary.undoable()).isTrue();
        });
    }

    /**
     * An uncommitted file can be thrown away, and a committed one cannot - it has products and
     * ledger rows pointing at it, both {@code ON DELETE RESTRICT}, and the honest answer for one
     * of those is undo rather than delete.
     */
    @Test
    void anUncommittedFileCanBeDiscardedAndACommittedOneCannot() {
        TenantLoginResponse tenant = signup("Discard Co");
        ImportSessionResponse draft = upload(
                tenant, CATALOG_HEADERS + "Rice 50kg,DISC-1,,42000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");

        assertThat(discard(tenant, draft.id())).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(restTemplate
                        .exchange(
                                "/api/imports/" + draft.id(),
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(tenant)),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        ImportSessionResponse committed = upload(
                tenant, CATALOG_HEADERS + "Beans 100kg,DISC-2,,52000,,,KG,,,,,\n", "PRODUCT_CATALOG", "CREATE_ONLY");
        commit(tenant, committed.id());
        assertThat(discard(tenant, committed.id())).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------- units, prices and honest totals

    /**
     * P0-4, and the reason UNIT_UX_CONTRACT.md section 6.3 exists.
     *
     * <p>The confirm screen used to sum the RAW entered quantities and label the sum with the
     * single unit if the file happened to use one: a sheet of 190 bags previewed as "190 BAG"
     * while the ledger recorded 9,500 kg. A screen whose entire job is "show what is about to
     * happen before it happens" was stating a number that is never written anywhere.
     *
     * <p>Both forms now appear, converted first and typed second, exactly as the contract words
     * it - and the assertion below is on the whole sentence rather than on a substring, because
     * the failure this guards against is a number that looks perfectly plausible on its own.
     */
    @Test
    void aStockInPreviewStatesTheLedgersNumberAndTheOneTheUserTyped() {
        TenantLoginResponse tenant = signup("Honest Totals Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,HON-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS + "HON-1,Rice 50kg,,190,BAG,,,,\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).isZero();
        assertThat(previewOf(tenant, session).lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("stock");
            assertThat(line.text()).isEqualTo("9,500 kg (190 bags) across 1 product");
        });
    }

    /**
     * Section 6.3's second half: "when the file mixes units the parenthetical is dropped and only
     * the stock-unit total is shown."
     *
     * <p>Two rows, one counted in bags and one in kilograms. There is no honest single sentence
     * of the form "N somethings" for what the user typed - 190 bags and 40 kg is not 230 of
     * anything - so the restatement goes away and only the number the ledger will hold survives.
     * The old code printed that sum and called the result "units".
     */
    @Test
    void aMixedEntryUnitStockInPreviewDropsTheRestatementAndKeepsTheLedgersNumber() {
        TenantLoginResponse tenant = signup("Mixed Entry Units Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,MIX-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "MIX-1,Rice 50kg,,190,BAG,,,,\n"
                        + "MIX-1,Rice 50kg,,40,KG,,,,\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).isZero();
        assertThat(previewOf(tenant, session).lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("stock");
            assertThat(line.text()).isEqualTo("9,540 kg across 1 product");
        });
    }

    /**
     * The other kind of mixing, and the one no conversion can rescue: two products counted in two
     * different stock units. Contract section 2.2 is explicit that cross-category conversion "is
     * not a conversion and must never be offered", so kilograms and litres are stated separately
     * rather than added together and labelled "units".
     */
    @Test
    void aStockInPreviewNeverAddsKilogramsToLitres() {
        TenantLoginResponse tenant = signup("Two Stock Units Co");
        commitCatalog(
                tenant,
                "CREATE_ONLY",
                CATALOG_HEADERS
                        + "Rice 50kg,TSU-1,,900,,,KG,BAG,50,,,\n"
                        + "Groundnut Oil,TSU-2,,1200,,,LITER,,,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "TSU-1,Rice 50kg,,2,BAG,,,,\n"
                        + "TSU-2,Groundnut Oil,,25,LITER,,,,\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).isZero();
        assertThat(previewOf(tenant, session).lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("stock");
            assertThat(line.text()).isEqualTo("100 kg and 25 L across 2 products");
        });
    }

    /**
     * Section 6.3's last line: "the catalog import's opening-balance line follows the same rule."
     *
     * <p>There is no bracketed restatement here and that is correct rather than an omission -
     * {@code opening_stock} is declared to be in the row's stock unit, so what was typed and what
     * is recorded are the same number. What can still differ is WHICH stock unit, and the old
     * code answered that by summing across them and writing "units".
     */
    @Test
    void theCatalogOpeningBalanceLineNeverSumsAcrossStockUnits() {
        TenantLoginResponse tenant = signup("Opening Balance Units Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,OBU-1,,900,40,,KG,,,,,\n"
                        + "Groundnut Oil,OBU-2,,1200,25,,LITER,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(previewOf(tenant, session).lines()).anySatisfy(line -> {
            assertThat(line.key()).isEqualTo("stock");
            assertThat(line.text()).isEqualTo("Opening balance of 40 kg and 25 L recorded across 2 products");
        });
    }

    /**
     * UNIT_UX_CONTRACT.md section 9.1's headline behaviour, end to end: a catalog row of
     * <b>30</b> with pack Keg and units per pack 50 commits as <b>1,500 ml</b>, and a row with no
     * pack commits as its bare number.
     *
     * <h2>What this replaced, twice</h2>
     * First a cross-field WARNING ("20 kg - did you mean 20 bags?"), whose predicate
     * ({@code opening <= packaging_size}) interrogated somebody who genuinely had 20 kg and said
     * nothing at all to somebody who typed 60 meaning 60 bags. Then an
     * {@code opening_stock_counted_in} COLUMN, on the sound-looking reasoning that a quantity
     * whose unit is inferred from a neighbour is what section 1 forbids everywhere else.
     *
     * <p>What that missed is that the neighbour is not an inference, it is the declaration. "30
     * bags" states its unit in English, and a row saying Keg, 50, 30 states it just as plainly.
     * The column was asking a question the row had already answered two cells to its left, so
     * section 9.1 deletes it and reads the row.
     */
    @Test
    void aCatalogRowsOpeningStockCountsPacksAndReachesTheLedgerConverted() {
        TenantLoginResponse tenant = signup("Pack Counted Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS_TODAY
                        + "Palm Oil,PACKQ-1,,Milliliter (ml),Keg,50,30,5,475,,,\n"
                        + "Bolts,PACKQ-2,,Piece,,,600,50,3,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).isZero();
        commit(tenant, session.id());

        // Thirty kegs of 50 ml.
        ProductResponse oil = productBySku(tenant, "PACKQ-1");
        assertThat(oil.quantityOnHand()).isEqualTo(1_500);
        // low_stock_alert_at follows the same rule - five kegs, 250 ml.
        assertThat(oil.lowStockThreshold()).isEqualTo(250);
        // cost_price follows the pack too, and this is the AMENDED section 9.2. N475 for one 50 ml
        // keg is stored as N9.50 per ml. The section originally anchored entry to the stock unit
        // as well as storage, which put a quantity in kegs beside a price per ml on one row - the
        // mixed basis this remediation exists to remove, and a user caught it on their first real
        // file. Storage is still per stock unit, which is what makes two suppliers with different
        // keg sizes comparable.
        assertThat(oil.costPrice()).isEqualByComparingTo("9.5");

        // No pack declared, so the bare number is stock units, exactly as it always was.
        ProductResponse bolts = productBySku(tenant, "PACKQ-2");
        assertThat(bolts.quantityOnHand()).isEqualTo(600);
        assertThat(bolts.lowStockThreshold()).isEqualTo(50);
    }

    /**
     * Thirty kegs and a half-full one is a real shelf, and an integer count of packs cannot say
     * it - section 9.1. Converted, then rounded HALF_UP at scale 0 like every other quantity in
     * the system (section 3.1).
     */
    @Test
    void aFractionalPackCountIsAcceptedAndRoundedTheSameWayEveryOtherQuantityIs() {
        TenantLoginResponse tenant = signup("Half Keg Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS_TODAY + "Palm Oil,HALF-1,,Milliliter (ml),Keg,50,30.5,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.errorCount()).isZero();
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "HALF-1").quantityOnHand()).isEqualTo(1_525);
    }

    /**
     * Section 3.1's other refusal: a conversion that rounds to zero is an error, never a silent
     * nothing. Recording "we have none" for stock somebody typed is the same class of defect as
     * recording the wrong number.
     */
    @Test
    void anOpeningStockThatRoundsToZeroIsRefusedRatherThanStoredAsNothing() {
        TenantLoginResponse tenant = signup("Rounds To Zero Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS_TODAY + "Gold Dust,ZERO-1,,Kilogram (kg),,,0.4,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "ERROR").errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("opening_stock");
            assertThat(error.message()).contains("less than one whole kg");
        });
    }

    /**
     * Non-negotiable 3 on the catalog grid: what the user typed and what the ledger will record,
     * together. The cell keeps their 30 - replacing it with 1,500 would answer a question they
     * did not ask - and the conversion renders underneath it.
     */
    @Test
    void aCatalogRowCountedInPacksCarriesTheLedgersNumberUnderIt() {
        TenantLoginResponse tenant = signup("Catalog Echo Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS_TODAY
                        + "Palm Oil,ECHO-1,,Milliliter (ml),Keg,50,30,,,,,\n"
                        + "Bolts,ECHO-2,,Piece,,,600,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        List<ImportRowResponse> rows = rows(tenant, session.id(), "ALL");
        ImportRowResponse oil = rows.stream()
                .filter(row -> "ECHO-1".equals(row.normalized().get("sku")))
                .findFirst()
                .orElseThrow();
        assertThat(oil.normalized().get("opening_stock").toString()).isEqualTo("30");
        assertThat(oil.baseQuantityText()).isEqualTo("= 1,500 ml");

        // A row counted in its own stock unit has nothing to convert, so there is no echo - "=
        // 600 Piece" under a cell reading 600 is noise.
        ImportRowResponse bolts = rows.stream()
                .filter(row -> "ECHO-2".equals(row.normalized().get("sku")))
                .findFirst()
                .orElseThrow();
        assertThat(bolts.baseQuantityText()).isNull();
    }

    /**
     * Contract section 6.2: the row carries its own product's unit set, so the "Counted in" cell
     * is a two-option select rather than a thirty-option one - the Flatfile pattern
     * BULK_IMPORT_DESIGN.md section 4 already cites, applied to the column that needed it.
     *
     * <p>The negative assertion is the load-bearing one. Non-negotiable 1 is that no quantity
     * field anywhere offers a unit with no conversion factor, and a Carton is exactly that for a
     * product whose packs are bags: picking it was a guaranteed 400, which is the complaint this
     * whole remediation started from (P1-1).
     */
    @Test
    void everyStockInRowCarriesItsOwnProductsUnitOptionsAndTheLedgersNumber() {
        TenantLoginResponse tenant = signup("Per Row Options Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,OPT-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session =
                upload(tenant, STOCK_IN_HEADERS + "OPT-1,Rice 50kg,,2,BAG,,,,\n", "STOCK_IN", null);

        ImportRowResponse row = firstRow(tenant, session, "ALL");
        assertThat(row.fieldOptions()).isNotNull().containsKey("counted_in");
        List<ImportFieldDescriptor.Option> options = row.fieldOptions().get("counted_in");
        assertThat(options).extracting(ImportFieldDescriptor.Option::value).contains("KG", "BAG");
        assertThat(options).extracting(ImportFieldDescriptor.Option::label).contains("kg", "Bag of 50 kg");
        assertThat(options).extracting(ImportFieldDescriptor.Option::value).doesNotContain("CARTON", "DRUM");

        // Non-negotiable 3 on the grid: what was typed, and what the ledger will take, together.
        assertThat(row.baseQuantityText()).isEqualTo("= 100 kg");

        // The reference column states the same answer the sheet does, recomputed from the product
        // rather than echoed from the file.
        assertThat(row.normalized().get("how_you_count_it")).isEqualTo("kg · or Bag of 50 kg");
    }

    /**
     * A row counted in its product's own stock unit has nothing to convert, so section 6.2's
     * {@code baseQuantityText} is null and Jackson drops it. "= 40 kg" printed under a cell
     * reading 40 is noise, and noise is what makes a user stop reading the useful ones.
     */
    @Test
    void aRowCountedInItsOwnStockUnitCarriesNoConversionLine() {
        TenantLoginResponse tenant = signup("No Conversion Line Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,NOCONV-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session =
                upload(tenant, STOCK_IN_HEADERS + "NOCONV-1,Rice 50kg,,40,KG,,,,\n", "STOCK_IN", null);

        assertThat(firstRow(tenant, session, "ALL").baseQuantityText()).isNull();
    }

    /**
     * P0-1 through the import path, which is the half of it that round-trips.
     *
     * <p>{@code cost_per_unit} is per the row's "Counted in". Twenty bags at N45,000 a bag on a
     * 50 kg-bag product is N900 per kg, and N900 per kg is what every downstream figure is
     * denominated in - {@code Product.costPrice}, the movement's {@code unitPriceAtTime}, the
     * supplier's {@code lastCostPrice}. Before this it wrote N45,000 per kg: a fifty-fold error,
     * silent, and compounded into every later weighted average.
     *
     * <p>The division happens inside {@code StockManagementService}, in the same call that
     * multiplies the quantity by the same factor. This test exists to prove the handler hands the
     * unit over rather than doing the arithmetic itself - a handler that divided as well would
     * land on N18 per kg and a handler that forgot the unit would land on N45,000.
     */
    @Test
    void aCostPerBagLandsInTheLedgerAsACostPerStockUnit() {
        TenantLoginResponse tenant = signup("Price Basis Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,PRICE-1,,,,,KG,BAG,50,,,\n");

        ImportSessionResponse session =
                upload(tenant, STOCK_IN_HEADERS + "PRICE-1,Rice 50kg,,20,BAG,45000,,,\n", "STOCK_IN", null);
        assertThat(session.errorCount()).isZero();
        commit(tenant, session.id());

        ProductResponse product = productBySku(tenant, "PRICE-1");
        assertThat(product.quantityOnHand()).isEqualTo(1000);
        assertThat(product.costPrice()).isEqualByComparingTo("900");
    }

    /**
     * Section 3.1's refusal, reachable and readable.
     *
     * <p>The old check asked "is it the base unit or the packaging unit?" - a second
     * implementation of "which units does this product accept", which is what P1-1 was. It now
     * resolves against the one set, and the message names every valid answer in the grammar a
     * person would use: "bags of 50 kg", not the picker's "Bag of 50 kg" spliced into the middle
     * of a sentence.
     */
    @Test
    void aUnitThisProductCannotBeCountedInIsRefusedByNamingTheOnesItCan() {
        TenantLoginResponse tenant = signup("Unit Not Stocked Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,UNS-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session =
                upload(tenant, STOCK_IN_HEADERS + "UNS-1,Rice 50kg,,5,CARTON,,,,\n", "STOCK_IN", null);

        assertThat(session.errorCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "ERROR").errors()).anySatisfy(error -> {
            assertThat(error.column()).isEqualTo("counted_in");
            assertThat(error.message()).contains("Rice 50kg is counted in");
            assertThat(error.message()).contains("bags of 50 kg");
            assertThat(error.message()).contains("we don't know how to count it in cartons");
            // Never the raw picker label mid-sentence - that capital B is the tell that a machine
            // assembled the sentence.
            assertThat(error.message()).doesNotContain("Bag of 50 kg");
        });
    }

    /**
     * Contract section 5.2: {@code packaging_size} is removed from the sheet, and a number left in
     * it on an old saved copy is accepted, ignored, and warned about - never silently dropped and
     * never used.
     *
     * <p>The warning lands only on rows that record something. The pre-filled template is the
     * tenant's whole catalog, so a six-line delivery arrives as a four-hundred-row file; warning
     * on all four hundred would be the same as warning on none (contract section 8.11's
     * reasoning, applied to the column beside it).
     */
    @Test
    void aPackagingSizeLeftOnAnOldStockInTemplateIsIgnoredOutLoudAndOnlyWhereItMatters() {
        TenantLoginResponse tenant = signup("Ignored Pack Size Co");
        commitCatalog(
                tenant,
                "CREATE_ONLY",
                CATALOG_HEADERS
                        + "Rice 50kg,IPS-1,,900,,,KG,BAG,50,,,\n"
                        + "Beans 100kg,IPS-2,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                STOCK_IN_HEADERS
                        + "IPS-1,Rice 50kg,,2,BAG,,25,,\n"
                        // No quantity: nothing arrived, so nothing is said about any of its other
                        // columns either.
                        + "IPS-2,Beans 100kg,,,BAG,,25,,\n",
                "STOCK_IN",
                null);

        assertThat(session.errorCount()).isZero();
        assertThat(session.warningCount()).isEqualTo(1);
        assertThat(firstRow(tenant, session, "WARNING").warnings()).anySatisfy(warning -> {
            // The stock-in sheet's ignored column keeps its old HEADER ("packaging_size") but its
            // field key moved with section 9.4, and the grid addresses cells by field key.
            assertThat(warning.column()).isEqualTo("units_per_pack");
            assertThat(warning.message()).contains("take the pack from your product setup");
        });

        // Ignored means ignored: 2 bags is 100 kg from the product's own pack, never 50 kg from
        // the number in the column we just said we were not reading.
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "IPS-1").quantityOnHand()).isEqualTo(100);
    }

    /**
     * The rename is a rename of the header, not a break in it. Contract section 5.1/5.2 keep
     * {@code quantity_on_hand}, {@code unit} and {@code unit_cost} accepted on read forever -
     * tenants hold saved copies of every template we have ever published, and a rename that costs
     * a customer a morning is not a rename worth making.
     *
     * <p>Both files below use the OLD headers throughout, which is also why every other test in
     * this class still does.
     */
    @Test
    void theHeadersOfEveryTemplateWeHaveEverPublishedStillMap() {
        TenantLoginResponse tenant = signup("Old Headers Co");
        ImportSessionResponse catalog = upload(
                tenant,
                CATALOG_HEADERS + "Rice 50kg,OLDH-1,,900,40,,KG,BAG,50,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");
        assertThat(catalog.needsMapping()).isFalse();
        assertThat(catalog.unmappedHeaders()).isEmpty();
        assertThat(catalog.columnMapping())
                .containsEntry("quantity_on_hand", "opening_stock")
                .containsEntry("low_stock_threshold", "low_stock_alert_at")
                .containsEntry("unit_of_measure", "stock_unit")
                .containsEntry("packaging_unit", "pack")
                .containsEntry("packaging_size", "units_per_pack");
        commit(tenant, catalog.id());

        ImportSessionResponse stockIn =
                upload(tenant, STOCK_IN_HEADERS + "OLDH-1,Rice 50kg,,2,BAG,45000,,,\n", "STOCK_IN", null);
        assertThat(stockIn.needsMapping()).isFalse();
        assertThat(stockIn.unmappedHeaders()).isEmpty();
        assertThat(stockIn.columnMapping())
                .containsEntry("unit", "counted_in")
                .containsEntry("unit_cost", "cost_per_unit");
        assertThat(stockIn.errorCount()).isZero();
    }

    /**
     * M2's handover, and the one thing a reference column must never do: appear on the mapping
     * screen as a column we did not understand. {@code how_you_count_it} answers the question the
     * sheet asks; being told we do not recognise it would undo exactly the reassurance it exists
     * to give.
     */
    @Test
    void theStockInSheetsReferenceColumnIsRecognisedRatherThanReportedAsUnknown() {
        TenantLoginResponse tenant = signup("Reference Column Co");
        commitCatalog(tenant, "CREATE_ONLY", CATALOG_HEADERS + "Rice 50kg,REFC-1,,900,,,KG,BAG,50,,,\n");

        ImportSessionResponse session = upload(
                tenant,
                "sku,product_name,how_you_count_it,vendor_name,quantity,counted_in,cost_per_unit,"
                        + "received_date,reference\n"
                        + "REFC-1,Rice 50kg,kg · or Bag of 50 kg,,2,Bag of 50 kg,45000,,\n",
                "STOCK_IN",
                null);

        assertThat(session.unmappedHeaders()).isEmpty();
        assertThat(session.needsMapping()).isFalse();
        assertThat(session.errorCount()).isZero();
        // The composed pack label the template writes into the cell reads back as the pack -
        // "Bag of 50 kg" is not a unit, it is a unit and a size, and M2's SheetUnitOptions is
        // what undoes the composition.
        ImportRowResponse row = firstRow(tenant, session, "ALL");
        assertThat(row.normalized().get("counted_in")).isEqualTo("BAG");
        assertThat(row.baseQuantityText()).isEqualTo("= 100 kg");
    }

    // ------------------------------------------------- the bulk fix actually fixing

    /**
     * BULK_IMPORT_DESIGN.md section 9.3 calls {@code [Fix all 12 "KGS" rows]} "the single
     * highest-value interaction on the page", and BULK_IMPORT_CONTRACT.md section 8.3 makes
     * "every fix offers its bulk form" a non-negotiable. Until this test, the form did nothing.
     *
     * <h2>The bug, which returned 200 and changed nothing</h2>
     * {@code PATCH /value-mappings} persisted every arm of the union and re-validated, but
     * {@code newState} built a row's inputs from the file and the user's own cell edits only.
     * Nothing read a LITERAL answer for an ordinary column - the handlers consult
     * {@code ValueMappings} themselves for {@code vendor_name} and {@code sku}, which is why the
     * supplier card always worked, and for every other column nothing consulted it at all. So the
     * request succeeded, the grid refetched, and the same broken rows came back.
     *
     * <p>The canonical case is a unit column, which makes this the repair loop for exactly the
     * confusion UNIT_UX_CONTRACT.md exists to end - it was dead in the one place it mattered most.
     */
    @Test
    void oneBulkFixRepairsEveryRowThatSharesTheSameBadValue() {
        TenantLoginResponse tenant = signup("Bulk Fix Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,BULK-1,,900,,,KGG,,,,,\n"
                        + "Beans 100kg,BULK-2,,900,,,KGG,,,,,\n"
                        + "Millet 25kg,BULK-3,,900,,,KGG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        assertThat(session.errorCount()).isEqualTo(3);
        // The count that powers the button's label, so it says "Fix all 3" and not "Fix all".
        assertThat(firstRow(tenant, session, "ERROR").errors())
                .anySatisfy(error -> assertThat(error.bulkFixCount()).isEqualTo(3));

        ImportSessionResponse fixed = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "stock_unit", "KGG", new ValueResolution("LITERAL", null, "KG", null)));

        assertThat(fixed.errorCount()).isZero();
        assertThat(rows(tenant, session.id(), "ALL"))
                .hasSize(3)
                .allSatisfy(row -> {
                    assertThat(row.status().name()).isEqualTo("VALID");
                    assertThat(row.normalized().get("stock_unit")).isEqualTo("KG");
                });

        // And it is a real repair, not a cosmetic one: the products import with the unit.
        commit(tenant, session.id());
        assertThat(productBySku(tenant, "BULK-2").unitOfMeasure()).isEqualTo("KG");
    }

    /**
     * A bulk answer is about the FILE; a cell edit is about one row. The row wins.
     *
     * <p>Correcting row 4 by hand and then bulk-fixing the rest is an ordinary sequence, not a
     * conflict - and silently overwriting the considered decision with the sweeping one would be
     * the review grid undoing the user's work in front of them. {@code editedKeys} is the record
     * of what was typed by hand, and the overlay skips those cells.
     */
    @Test
    void aHandEditSurvivesABulkFixAppliedAfterwards() {
        TenantLoginResponse tenant = signup("Edit Beats Bulk Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Groundnut Oil,EDIT-1,,900,,,KGG,,,,,\n"
                        + "Rice 50kg,EDIT-2,,900,,,KGG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        ImportRowResponse oil = rows(tenant, session.id(), "ALL").stream()
                .filter(row -> "EDIT-1".equals(row.normalized().get("sku")))
                .findFirst()
                .orElseThrow();
        patchRow(tenant, session.id(), oil.id(), Map.of("stock_unit", "LITER"));

        resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "stock_unit", "KGG", new ValueResolution("LITERAL", null, "KG", null)));

        Map<String, Object> bySku = new java.util.LinkedHashMap<>();
        rows(tenant, session.id(), "ALL")
                .forEach(row -> bySku.put(
                        String.valueOf(row.normalized().get("sku")), row.normalized().get("stock_unit")));
        assertThat(bySku).containsEntry("EDIT-1", "LITER").containsEntry("EDIT-2", "KG");
    }

    /**
     * The BLANK arm, on an ordinary column: clear the cell everywhere it says this, rather than
     * substitute into it. Same overlay, same precedence rules - it is the answer "that column was
     * nonsense, drop it" given once instead of forty-seven times.
     */
    @Test
    void aBulkBlankClearsTheCellOnEveryMatchingRow() {
        TenantLoginResponse tenant = signup("Bulk Blank Co");
        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS
                        + "Rice 50kg,BLANK-1,,900,,,KGG,,,,,\n"
                        + "Beans 100kg,BLANK-2,,900,,,KGG,,,,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");
        assertThat(session.errorCount()).isEqualTo(2);

        ImportSessionResponse cleared = resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "stock_unit", "KGG", new ValueResolution("BLANK", null, null, null)));

        assertThat(cleared.errorCount()).isZero();
        assertThat(rows(tenant, session.id(), "ALL"))
                .allSatisfy(row -> assertThat(row.normalized().get("stock_unit")).isNull());
    }

    /**
     * The REFERENCE path is untouched, and this is the regression guard for that.
     *
     * <p>{@code vendor_name} is named by {@code selfResolvedColumns}, so the engine's generic
     * substitution stays off it and {@code resolveVendor} keeps handling all five arms at commit
     * time. A LITERAL there means "go looking under this other name", not "put this text in the
     * cell" - and the difference only shows up where it matters, which is what the vendor line
     * below is checking.
     */
    @Test
    void aLiteralOnTheSupplierColumnStillGoesThroughTheHandlerAndNotTheOverlay() {
        TenantLoginResponse tenant = signup("Literal Supplier Co");
        CompanyVendorResponse dangote = createVendor(tenant, "Dangote Nigeria Plc");

        ImportSessionResponse session = upload(
                tenant,
                CATALOG_HEADERS + "Rice 50kg,LITV-1,,900,,,KG,,,Dangote Ltd,,\n",
                "PRODUCT_CATALOG",
                "CREATE_ONLY");

        resolveValue(
                tenant,
                session.id(),
                new ValueMappingRequest(
                        "vendor_name", "Dangote Ltd",
                        new ValueResolution("LITERAL", null, "Dangote Nigeria Plc", null)));
        commit(tenant, session.id());

        // The cell still says what the file said - the handler resolved the pointer, it did not
        // rewrite the row - and the vendor line landed on the real supplier.
        assertThat(vendorLines(tenant, productBySku(tenant, "LITV-1").id()))
                .anySatisfy(line -> assertThat(line.companyVendorId()).isEqualTo(dangote.id()));
    }

    // ----------------------------------------------------------------- helpers

    private PreviewBody previewOf(TenantLoginResponse tenant, ImportSessionResponse session) {
        return restTemplate
                .exchange(
                        "/api/imports/" + session.id() + "/preview",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        PreviewBody.class)
                .getBody();
    }


    private HttpStatus discard(TenantLoginResponse tenant, UUID sessionId) {
        return (HttpStatus) restTemplate
                .exchange(
                        "/api/imports/" + sessionId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(tenant)),
                        String.class)
                .getStatusCode();
    }

    /**
     * What the client does on the async path: ask again until the status leaves COMMITTING.
     *
     * <p>The real client backs off from 1.2s to 5s with a five-minute ceiling; a test polling a
     * local Postgres does not need either, but it does need the same stopping condition, because
     * that condition is the contract.
     */
    private ImportSessionResponse pollUntilSettled(TenantLoginResponse tenant, UUID sessionId) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            ImportSessionResponse session = restTemplate
                    .exchange(
                            "/api/imports/" + sessionId,
                            HttpMethod.GET,
                            new HttpEntity<>(authHeaders(tenant)),
                            ImportSessionResponse.class)
                    .getBody();
            if (!"COMMITTING".equals(session.status().name())) {
                return session;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("The import never left COMMITTING - the client would poll to its ceiling and lie");
    }

    private ImportSessionResponse upload(TenantLoginResponse tenant, String csv, String kind, String mode) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "products.csv";
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

    /** Upload and commit in one go, for building the fixture a test is actually about. */
    private void commitCatalog(TenantLoginResponse tenant, String mode, String csv) {
        commit(tenant, upload(tenant, csv, "PRODUCT_CATALOG", mode).id());
    }

    private ImportResultResponse commit(TenantLoginResponse tenant, UUID sessionId) {
        ResponseEntity<ImportResultResponse> response = commitRaw(tenant, sessionId);
        assertThat(response.getStatusCode()).as("commit must answer 200 below the async threshold")
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<ImportResultResponse> commitRaw(TenantLoginResponse tenant, UUID sessionId) {
        return restTemplate.exchange(
                "/api/imports/" + sessionId + "/commit",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(tenant)),
                ImportResultResponse.class);
    }

    private List<ImportRowResponse> rows(TenantLoginResponse tenant, UUID sessionId, String status) {
        return rowsPage(tenant, sessionId, status).content();
    }

    private TestPage<ImportRowResponse> rowsPage(TenantLoginResponse tenant, UUID sessionId, String status) {
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/rows?status=" + status,
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<TestPage<ImportRowResponse>>() {})
                .getBody();
    }

    private ImportRowResponse firstRow(TenantLoginResponse tenant, ImportSessionResponse session, String status) {
        List<ImportRowResponse> rows = rows(tenant, session.id(), status);
        assertThat(rows).as("expected at least one %s row", status).isNotEmpty();
        return rows.get(0);
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

    private void skipRow(TenantLoginResponse tenant, UUID sessionId, UUID rowId) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        restTemplate.exchange(
                "/api/imports/" + sessionId + "/rows/" + rowId + "/skip",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("skipped", true), headers),
                ImportRowResponse.class);
    }

    private ImportSessionResponse patchMapping(
            TenantLoginResponse tenant, UUID sessionId, Map<String, String> columnMapping) {
        HttpHeaders headers = authHeaders(tenant);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate
                .exchange(
                        "/api/imports/" + sessionId + "/mapping",
                        HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("columnMapping", columnMapping), headers),
                        ImportSessionResponse.class)
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

    private List<ProductVendorResponse> vendorLines(TenantLoginResponse tenant, UUID productId) {
        return restTemplate
                .exchange(
                        "/api/products/" + productId + "/vendors",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(tenant)),
                        new ParameterizedTypeReference<List<ProductVendorResponse>>() {})
                .getBody();
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

    /** Spring Data's {@code Page} is not deserializable as-is; only these two fields are read. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TestPage<T>(List<T> content, int totalElements) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SummaryBody(String status, String summaryText, boolean undoable) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewBody(String headline, List<PreviewLine> lines, String confirmLabel, boolean blocked) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PreviewLine(String key, String label, int count, String text) {
    }

    /**
     * Contract section 4's 409 body, re-declared here rather than imported, so the test asserts on
     * the JSON the frontend actually parses rather than on our own record definition.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record UndoBlockedBody(String message, List<Blocker> blockers) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Blocker(int excelRow, String label, String reason, String entityId) {
        }
    }
}
