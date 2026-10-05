package com.procurepal_services.stock_bridge_api.product.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.product.StockStatus;
import com.procurepal_services.stock_bridge_api.product.category.dto.CompanyCategoryRequest;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.stock.dto.StockInRequest;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The on-device catalogue's feed (A3): a full snapshot, then only what changed - including the
 * changes that reach a list row without touching the product itself, deletions, and the one case
 * a naive cursor gets wrong (a transaction that commits behind a phone's cursor).
 *
 * <p>The feed may RE-SEND a product the phone already has: its cursor starts from the oldest
 * transaction running anywhere in the database, so a write by any other test (or tenant) in flight
 * during the snapshot moves it earlier. A re-send just overwrites a row with its current state, so
 * these tests assert what must arrive and what must not - never that nothing else did.
 *
 * <p>Real HTTP and real triggers against the local docker-compose Postgres, like the other
 * integration tests here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class ProductSyncIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProductSyncService productSyncService;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void aFullSnapshotPagesThroughEveryProductOnceWithTheListsOwnFigures() {
        TenantLoginResponse admin = signup("Sync Snapshot");
        UUID category = createCategory(admin, "Grains");
        List<UUID> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(createProduct(admin, "P" + i, i == 0 ? category : null, 10).id());
        }
        stockIn(admin, created.get(1), 50); // above its threshold
        stockIn(admin, created.get(2), 3); // at or below it

        List<ProductSyncRow> all = new ArrayList<>();
        ProductSnapshotPage first = snapshot(admin, null, 2);
        assertThat(first.cursor()).as("the first page fixes where the feed picks up").isNotBlank();
        assertThat(first.total()).isEqualTo(5L);
        assertThat(first.hasMore()).isTrue();
        all.addAll(first.products());
        ProductSnapshotPage page = first;
        while (page.hasMore()) {
            page = snapshot(admin, page.nextAfterId(), 2);
            assertThat(page.cursor()).isNull();
            assertThat(page.total()).isNull();
            all.addAll(page.products());
        }

        assertThat(all).extracting(ProductSyncRow::id).containsExactlyInAnyOrderElementsOf(created);
        ProductSyncRow grains = row(all, created.get(0));
        assertThat(grains.categoryName()).isEqualTo("Grains");
        assertThat(grains.unitOfMeasure()).isEqualTo("KG");
        assertThat(grains.stockStatus()).isEqualTo(StockStatus.OUT);
        assertThat(row(all, created.get(1)).stockStatus()).isEqualTo(StockStatus.OK);
        assertThat(row(all, created.get(2)).stockStatus()).isEqualTo(StockStatus.LOW);
        assertThat(row(all, created.get(2)).isLowStock()).isTrue();
    }

    @Test
    void aStockMovementComesThroughOnceAndTheCursorMovesOn() {
        TenantLoginResponse admin = signup("Sync Changes");
        UUID rice = createProduct(admin, "RICE", null, 10).id();
        String cursor = snapshot(admin, null, 100).cursor();

        stockIn(admin, rice, 20);
        stockIn(admin, rice, 5);

        ProductChangesPage changes = changes(admin, cursor);
        assertThat(changes.products()).extracting(ProductSyncRow::id).containsExactly(rice);
        assertThat(changes.products().get(0).quantityOnHand()).isEqualTo(25);
        assertThat(changes.cursor()).isNotEqualTo(cursor);

        ProductChangesPage nothingNew = changes(admin, changes.cursor());
        assertThat(nothingNew.products()).isEmpty();
        assertThat(nothingNew.removedIds()).isEmpty();
        assertThat(nothingNew.cursor()).isEqualTo(changes.cursor());
    }

    @Test
    void aDeletedProductIsReportedAsRemoved() {
        TenantLoginResponse admin = signup("Sync Delete");
        UUID doomed = createProduct(admin, "GONE", null, null).id();
        String cursor = snapshot(admin, null, 100).cursor();

        // Hard deletes happen through an import undo or a catalogue reset; the trigger is the same.
        jdbc.update("DELETE FROM product_vendors WHERE product_id = ?", doomed);
        jdbc.update("DELETE FROM products WHERE id = ?", doomed);

        ProductChangesPage changes = changes(admin, cursor);
        assertThat(changes.removedIds()).containsExactly(doomed);
        assertThat(changes.products()).isEmpty();
    }

    /** A rename touches no product row, but changes what every product in it shows. */
    @Test
    void renamingACategoryResendsItsProducts() {
        TenantLoginResponse admin = signup("Sync Category");
        UUID category = createCategory(admin, "Grains");
        UUID rice = createProduct(admin, "RICE", category, null).id();
        createProduct(admin, "OIL", null, null);
        String cursor = snapshot(admin, null, 100).cursor();

        restTemplate.exchange("/api/company-categories/" + category, HttpMethod.PUT,
                new HttpEntity<>(new CompanyCategoryRequest("Cereals"), auth(admin)), String.class);

        ProductChangesPage changes = changes(admin, cursor);
        assertThat(changes.products()).extracting(ProductSyncRow::id).contains(rice);
        assertThat(row(changes.products(), rice).categoryName()).isEqualTo("Cereals");
    }

    /** "+ 500 expected" changes when an expected delivery is written down or cancelled. */
    @Test
    void anExpectedDeliveryResendsTheProductsOnIt() {
        TenantLoginResponse admin = signup("Sync Expected");
        UUID rice = createProduct(admin, "RICE", null, null).id();
        String cursor = snapshot(admin, null, 100).cursor();
        UUID clientId = jdbc.queryForObject("SELECT client_id FROM products WHERE id = ?", UUID.class, rice);

        UUID delivery = jdbc.queryForObject(
                "INSERT INTO expected_deliveries (client_id) VALUES (?) RETURNING id", UUID.class, clientId);
        jdbc.update(
                "INSERT INTO expected_delivery_lines (expected_delivery_id, product_id, unit, quantity) VALUES (?, ?, 'KG', 500)",
                delivery, rice);

        ProductChangesPage opened = changes(admin, cursor);
        assertThat(opened.products()).extracting(ProductSyncRow::id).containsExactly(rice);
        assertThat(opened.products().get(0).expectedQuantity()).isEqualByComparingTo("500");

        jdbc.update("UPDATE expected_deliveries SET status = 'CANCELLED' WHERE id = ?", delivery);
        ProductChangesPage cancelled = changes(admin, opened.cursor());
        assertThat(cancelled.products()).extracting(ProductSyncRow::id).containsExactly(rice);
        assertThat(cancelled.products().get(0).expectedQuantity()).isNull();
    }

    /**
     * The case a plain sequence cursor gets wrong. Transaction A changes one product and stays
     * open; B changes another and commits. A cursor that moved past B would skip A when it
     * finally commits. The feed must hold back until A is done, then deliver both.
     */
    @Test
    void aSlowTransactionIsNeverSkippedByAFasterOneBehindIt() throws Exception {
        TenantLoginResponse admin = signup("Sync Slow");
        UUID slow = createProduct(admin, "SLOW", null, null).id();
        UUID fast = createProduct(admin, "FAST", null, null).id();
        String cursor = snapshot(admin, null, 100).cursor();

        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (var statement = held.createStatement()) {
                statement.executeUpdate("UPDATE products SET name = 'SLOW (renamed)' WHERE id = '" + slow + "'");
            }

            stockIn(admin, fast, 7); // a later transaction, committed straight away

            // A phone syncs now, while the slow transaction is still open, and keeps the cursor.
            cursor = changes(admin, cursor).cursor();

            held.commit();
        }

        // From that cursor, the slow change must still arrive. A cursor that had moved past the
        // still-open transaction (as a plain sequence would) would never deliver it.
        ProductChangesPage after = changes(admin, cursor);
        assertThat(after.products()).extracting(ProductSyncRow::id).contains(slow);
        assertThat(row(after.products(), slow).name()).isEqualTo("SLOW (renamed)");
    }

    @Test
    void oneCompanyNeverSeesAnothersProductsOrChanges() {
        TenantLoginResponse a = signup("Sync Tenant A");
        TenantLoginResponse b = signup("Sync Tenant B");
        UUID ownedByA = createProduct(a, "A1", null, null).id();
        UUID ownedByB = createProduct(b, "B1", null, null).id();
        String cursorA = snapshot(a, null, 100).cursor();

        stockIn(b, ownedByB, 9);

        assertThat(snapshot(a, null, 100).products()).extracting(ProductSyncRow::id).containsExactly(ownedByA);
        assertThat(changes(a, cursorA).products()).extracting(ProductSyncRow::id).doesNotContain(ownedByB);
        assertThat(changes(a, cursorA).removedIds()).doesNotContain(ownedByB);
    }

    /** Compaction keeps the log the size of the catalogue without losing anything not yet read. */
    @Test
    void compactionKeepsTheLatestChangeAnAnOldCursorStillReceives() {
        TenantLoginResponse admin = signup("Sync Compact");
        UUID rice = createProduct(admin, "RICE", null, null).id();
        String cursor = snapshot(admin, null, 100).cursor();
        stockIn(admin, rice, 1);
        stockIn(admin, rice, 2);
        stockIn(admin, rice, 3);

        productSyncService().compactChangeLog();

        Integer rowsForRice = jdbc.queryForObject("SELECT count(*) FROM product_changes WHERE product_id = ?", Integer.class, rice);
        assertThat(rowsForRice).isEqualTo(1);
        ProductChangesPage changes = changes(admin, cursor);
        assertThat(changes.products()).extracting(ProductSyncRow::id).containsExactly(rice);
        assertThat(changes.products().get(0).quantityOnHand()).isEqualTo(6);
    }

    @Test
    void aCursorTheServerDidNotIssueIsRefused() {
        TenantLoginResponse admin = signup("Sync Bad Cursor");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/products/sync/changes?cursor=nonsense", HttpMethod.GET, new HttpEntity<>(auth(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------------- helpers

    @Autowired
    private ProductSyncService productSyncServiceBean;

    private ProductSyncService productSyncService() {
        return productSyncServiceBean;
    }

    private static ProductSyncRow row(List<ProductSyncRow> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    private ProductSnapshotPage snapshot(TenantLoginResponse admin, UUID afterId, int limit) {
        String url = "/api/products/sync/snapshot?limit=" + limit + (afterId == null ? "" : "&afterId=" + afterId);
        ResponseEntity<ProductSnapshotPage> response =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(admin)), ProductSnapshotPage.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ProductChangesPage changes(TenantLoginResponse admin, String cursor) {
        ResponseEntity<ProductChangesPage> response = restTemplate.exchange(
                "/api/products/sync/changes?cursor={cursor}",
                HttpMethod.GET,
                new HttpEntity<>(auth(admin)),
                ProductChangesPage.class,
                Map.of("cursor", cursor));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void stockIn(TenantLoginResponse admin, UUID productId, int quantity) {
        ResponseEntity<StockMutationResponse> response = restTemplate.exchange(
                "/api/products/" + productId + "/stock/stock-in",
                HttpMethod.POST,
                new HttpEntity<>(new StockInRequest(quantity, null, null), auth(admin)),
                StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID createCategory(TenantLoginResponse admin, String name) {
        ResponseEntity<Map> response = restTemplate.exchange(
                "/api/company-categories", HttpMethod.POST, new HttpEntity<>(new CompanyCategoryRequest(name), auth(admin)), Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private ProductResponse createProduct(TenantLoginResponse admin, String sku, UUID categoryId, Integer lowStockThreshold) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_JSON);
        body.add("product", new HttpEntity<>(
                new CreateProductRequest("Product " + sku, sku, null, new BigDecimal("9.99"), lowStockThreshold, "KG", null, null, null, categoryId),
                part));
        HttpHeaders headers = auth(admin);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProductResponse> response =
                restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("create %s: %s", sku, response.getStatusCode()).isTrue();
        return response.getBody();
    }

    /**
     * Phase H, 100,000 products: a cached generic plan turned each page's 2,000-id lookups into a
     * linear scan of the company (3 s a page). The sync's transactions plan for their real values,
     * and the setting ends with them rather than following the pooled connection elsewhere.
     */
    @Test
    void syncPagesArePlannedForTheirOwnValuesAndTheSettingStaysInTheirTransaction() {
        TenantLoginResponse admin = signup("Sync Plans");
        createProduct(admin, "PLAN-1", null, null);
        UUID clientId = jdbc.queryForObject("SELECT client_id FROM users WHERE id = ?", UUID.class, admin.user().id());

        TenantContext.set(clientId);
        try {
            String inside = transactions.execute(status -> {
                productSyncService.snapshot(null, 10);
                return jdbc.queryForObject("SHOW plan_cache_mode", String.class);
            });
            String after = transactions.execute(status -> jdbc.queryForObject("SHOW plan_cache_mode", String.class));

            assertThat(inside).isEqualTo("force_custom_plan");
            assertThat(after).isEqualTo("auto");
        } finally {
            TenantContext.clear();
        }
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject(
                "/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com", PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private HttpHeaders auth(TenantLoginResponse admin) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(admin.tokens().accessToken());
        return headers;
    }
}
