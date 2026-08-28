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
     * <p>An update row's {@code quantity_on_hand} is ignored - quantity moves through the ledger
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
            assertThat(warning.column()).isEqualTo("quantity_on_hand");
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
            assertThat(error.column()).isEqualTo("unit_of_measure");
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
            assertThat(row.raw()).containsEntry("unit_of_measure", "KGX");
            assertThat(row.normalized().get("unit_of_measure")).isNull();
        });
        assertThat(rows(tenant, session.id(), "ERROR")).allSatisfy(row -> assertThat(row.errors())
                .anySatisfy(error -> {
                    assertThat(error.column()).isEqualTo("unit_of_measure");
                    assertThat(error.bulkFixCount()).isEqualTo(3);
                    assertThat(error.suggestion()).isNotNull();
                    assertThat(error.suggestion().value()).isEqualTo("KG");
                }));

        // And the repair path the grid actually uses: PATCH one row, get it back re-validated.
        ImportRowResponse repaired = patchRow(
                tenant,
                session.id(),
                rows(tenant, session.id(), "ERROR").get(0).id(),
                Map.of("unit_of_measure", "KG"));
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

    // ----------------------------------------------------------------- helpers

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
