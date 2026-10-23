package com.procurepal_services.stock_bridge_api.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.branch.BranchHealthService;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchFigure;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchListResponse;
import com.procurepal_services.stock_bridge_api.branch.dto.BranchResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.StockTransferStatus;
import com.procurepal_services.stock_bridge_api.product.dto.CreateProductRequest;
import com.procurepal_services.stock_bridge_api.product.dto.ProductResponse;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.stock.dto.StockMutationResponse;
import com.procurepal_services.stock_bridge_api.transfer.dto.TransferResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Multiple branches, Phase 2, server side (MULTI_BRANCH_BUILD_PLAN.md section 5): sending, receiving in
 * parts and short, surplus, closing as missing, cancelling, both ends at once - and that the ledger at
 * each branch always adds up to its figure, and a transfer received in full leaves the company total
 * where it was (MULTI_BRANCH_UX_PLAN.md sections 5.4-5.7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class StockTransferIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private BranchHealthService branchHealthService;

    // -------------------------------------------------------------------------------- send, receive

    @Test
    void sendingTakesItOffTheShelfShowsItOnTheWayAndReceivingPutsItOnTheOtherShelf() {
        Company c = company("Send Receive");
        stockIn(c, c.main(), 10, "250");

        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 4);

        assertThat(sent.status()).isEqualTo(StockTransferStatus.ON_THE_WAY);
        assertThat(sent.waybill()).matches("WB-HEA-\\d{4}");
        assertThat(figure(c.main(), c.product())).isEqualTo(6);
        assertThat(figure(c.oshodi(), c.product())).isZero();
        assertThat(branchFigures(c.owner(), c.product()).get(c.oshodi()).onTheWay()).isEqualTo(4);
        assertThat(sent.shareText()).contains("Waybill " + sent.waybill()).contains("From Head Office to Oshodi")
                .contains("Carried by Musa").contains("Received by:");
        assertThat(sent.lines().getFirst().sentText()).isEqualTo("4");

        TransferResponse received = receiveAsSent(c.owner(), sent.id());

        assertThat(received.status()).isEqualTo(StockTransferStatus.RECEIVED);
        assertThat(figure(c.main(), c.product())).isEqualTo(6);
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(4);
        assertThat(productOf(c.owner(), c.product()).quantityOnHand()).as("the company total").isEqualTo(10);
        assertThat(branchFigures(c.owner(), c.product()).get(c.oshodi()).onTheWay()).isZero();
        assertLedgerMatchesFigures(c);
    }

    /** D-B5: profit at the receiving branch uses what the stock really cost, and the trace walks through. */
    @Test
    void receivedStockCarriesTheCostOfTheDeliveryItCameFrom() {
        Company c = company("Cost Carried");
        stockIn(c, c.main(), 3, "100");
        stockIn(c, c.main(), 5, "180");

        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 5);
        receiveAsSent(c.owner(), sent.id());

        List<Map<String, Object>> lots = jdbc.queryForList("""
                SELECT m.quantity, m.unit_price_at_time, src.unit_price_at_time AS source_price
                FROM stock_movements m JOIN stock_movements src ON src.id = m.source_lot_id
                WHERE m.transfer_id = :t AND m.movement_type = 'TRANSFER_IN' ORDER BY m.unit_price_at_time
                """, new MapSqlParameterSource("t", sent.id()));
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0)).containsEntry("quantity", 3);
        assertThat((BigDecimal) lots.get(0).get("unit_price_at_time")).isEqualByComparingTo("100");
        assertThat(lots.get(1)).containsEntry("quantity", 2);
        assertThat((BigDecimal) lots.get(1).get("unit_price_at_time")).isEqualByComparingTo("180");
        assertThat(lots).allSatisfy(lot -> assertThat((BigDecimal) lot.get("unit_price_at_time"))
                .isEqualByComparingTo((BigDecimal) lot.get("source_price")));
    }

    @Test
    void aSendResentWithTheSameIdIsRecordedOnce() {
        Company c = company("Send Twice");
        stockIn(c, c.main(), 10, null);
        Map<String, Object> body = transferBody(c.main(), c.oshodi(), c.product(), 4);

        TransferResponse first = post(c.owner(), "/api/stock-transfers", body, TransferResponse.class).getBody();
        TransferResponse second = post(c.owner(), "/api/stock-transfers", body, TransferResponse.class).getBody();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.waybill()).isEqualTo(first.waybill());
        assertThat(figure(c.main(), c.product())).isEqualTo(6);
    }

    @Test
    void sendingMoreThanTheBranchHoldsIsRefusedAndRecordsNothing() {
        Company c = company("Send Too Much");
        stockIn(c, c.main(), 2, null);
        stockIn(c, c.oshodi(), 20, null);

        ResponseEntity<String> refused =
                post(c.owner(), "/api/stock-transfers", transferBody(c.main(), c.oshodi(), c.product(), 5), String.class);

        assertThat(refused.getStatusCode()).as("judged against the sending branch, not the company").isEqualTo(HttpStatus.CONFLICT);
        assertThat(figure(c.main(), c.product())).isEqualTo(2);
        assertThat(transfersOf(c)).isZero();
    }

    // ------------------------------------------------------------------------- short, parts, extra

    @Test
    void receivedShortRecordsWhatIsMissingAndDamagedAndTellsTheSender() {
        Company c = company("Received Short");
        stockIn(c, c.main(), 10, null);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.oshodi());
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 6);

        TransferResponse received = receive(ade, sent.id(), lineOf(c.product(), 4, 1, 1)).getBody();

        assertThat(received.status()).isEqualTo(StockTransferStatus.RECEIVED_SHORT);
        assertThat(received.missing()).isEqualTo(2);
        assertThat(received.receivedByName()).isNotBlank();
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(4);
        assertThat(productOf(c.owner(), c.product()).quantityOnHand()).as("two are gone from the company").isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications WHERE user_id = :owner AND type = 'TRANSFER_RECEIVED_SHORT'
                """, new MapSqlParameterSource("owner", c.owner().user().id()), Long.class)).isEqualTo(1);
        assertLedgerMatchesFigures(c);
    }

    @Test
    void aWaybillCanBeReceivedInPartsAndAReceiptResentIsCountedOnce() {
        Company c = company("Received In Parts");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 6);
        Map<String, Object> firstReceipt = receiptBody(UUID.randomUUID(), lineOf(c.product(), 2, 0, 0));

        TransferResponse part = post(c.owner(), "/api/stock-transfers/" + sent.id() + "/receipts", firstReceipt,
                TransferResponse.class).getBody();
        post(c.owner(), "/api/stock-transfers/" + sent.id() + "/receipts", firstReceipt, TransferResponse.class);

        assertThat(part.status()).isEqualTo(StockTransferStatus.PARTLY_RECEIVED);
        assertThat(part.lines().getFirst().onTheWay()).isEqualTo(4);
        assertThat(figure(c.oshodi(), c.product())).as("the resent receipt adds nothing").isEqualTo(2);

        TransferResponse rest = receive(c.owner(), sent.id(), lineOf(c.product(), 4, 0, 0)).getBody();
        assertThat(rest.status()).isEqualTo(StockTransferStatus.RECEIVED);
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(6);
        assertLedgerMatchesFigures(c);
    }

    /** "Sent too many": the sending branch had more than its books said; the company total holds. */
    @Test
    void moreArrivingThanWasSentCorrectsTheSendingBranchAndKeepsTheCompanyTotal() {
        Company c = company("Sent Too Many");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 4);

        TransferResponse received = receive(c.owner(), sent.id(), lineOf(c.product(), 5, 0, 0)).getBody();

        assertThat(received.status()).isEqualTo(StockTransferStatus.RECEIVED);
        assertThat(received.lines().getFirst().quantitySent()).isEqualTo(5);
        assertThat(figure(c.main(), c.product())).isEqualTo(5);
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(5);
        assertThat(productOf(c.owner(), c.product()).quantityOnHand()).isEqualTo(10);
        assertLedgerMatchesFigures(c);
    }

    @Test
    void reportingMoreMissingThanIsStillOnTheWayIsRefused() {
        Company c = company("Too Much Missing");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 4);

        ResponseEntity<String> refused = receive(c.owner(), sent.id(), lineOf(c.product(), 3, 2, 0), String.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody()).contains("more than is still on the way");
        assertThat(figure(c.oshodi(), c.product())).isZero();
    }

    // --------------------------------------------------------------------- close, cancel, missing

    @Test
    void closingAsMissingAccountsForTheRestAndTheMissingStaysInViewUntilChecked() {
        Company c = company("Close Missing");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 5);
        receive(c.owner(), sent.id(), lineOf(c.product(), 2, 0, 0));

        TransferResponse closed = post(c.owner(), "/api/stock-transfers/" + sent.id() + "/close", null,
                TransferResponse.class).getBody();

        assertThat(closed.status()).isEqualTo(StockTransferStatus.RECEIVED_SHORT);
        assertThat(closed.missing()).isEqualTo(3);
        assertThat(branchFigures(c.owner(), c.product()).get(c.oshodi()).onTheWay()).isZero();

        ResponseEntity<String> noNote = post(c.owner(), "/api/stock-transfers/" + sent.id() + "/missing-checked",
                Map.of("note", " "), String.class);
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        TransferResponse checked = post(c.owner(), "/api/stock-transfers/" + sent.id() + "/missing-checked",
                Map.of("note", "Driver paid for it"), TransferResponse.class).getBody();
        assertThat(checked.missing()).isZero();
        assertThat(checked.missingNote()).isEqualTo("Driver paid for it");
        assertLedgerMatchesFigures(c);
    }

    @Test
    void cancellingBeforeAnythingArrivesPutsTheStockBackAndAfterwardsIsRefused() {
        Company c = company("Cancel Transfer");
        stockIn(c, c.main(), 10, null);
        TransferResponse first = send(c.owner(), c, c.main(), c.oshodi(), 4);

        TransferResponse cancelled = post(c.owner(), "/api/stock-transfers/" + first.id() + "/cancel", null,
                TransferResponse.class).getBody();

        assertThat(cancelled.status()).isEqualTo(StockTransferStatus.CANCELLED);
        assertThat(figure(c.main(), c.product())).isEqualTo(10);
        assertThat(branchFigures(c.owner(), c.product()).get(c.oshodi()).onTheWay()).isZero();
        assertThat(receiveRaw(c.owner(), first.id()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        TransferResponse second = send(c.owner(), c, c.main(), c.oshodi(), 3);
        receive(c.owner(), second.id(), lineOf(c.product(), 1, 0, 0));
        ResponseEntity<String> tooLate =
                post(c.owner(), "/api/stock-transfers/" + second.id() + "/cancel", null, String.class);
        assertThat(tooLate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooLate.getBody()).contains("already been received");
        assertLedgerMatchesFigures(c);
    }

    @Test
    void aTransfersLedgerRowsCannotBeUndoneOneByOne() {
        Company c = company("Void Transfer Row");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 4);
        UUID out = jdbc.queryForObject(
                "SELECT id FROM stock_movements WHERE transfer_id = :t AND movement_type = 'TRANSFER_OUT'",
                new MapSqlParameterSource("t", sent.id()), UUID.class);

        ResponseEntity<String> refused = post(c.owner(), "/api/products/" + c.product() + "/stock/movements/" + out + "/void", Map.of(), String.class);

        assertThat(refused.getStatusCode().is4xxClientError()).as(refused.getBody()).isTrue();
        assertThat(figure(c.main(), c.product())).isEqualTo(6);
    }

    // ---------------------------------------------------------------------- both ends, who may

    /** UX section 5.6: neighbouring depots - somebody who works at both records it arrived at once. */
    @Test
    void someoneWhoWorksAtBothEndsCanRecordItReceivedAsTheySendIt() {
        Company c = company("Both Ends");
        stockIn(c, c.main(), 10, null);
        Map<String, Object> body = transferBody(c.main(), c.oshodi(), c.product(), 4);
        body.put("receiveNow", true);

        TransferResponse done = post(c.owner(), "/api/stock-transfers", body, TransferResponse.class).getBody();

        assertThat(done.status()).isEqualTo(StockTransferStatus.RECEIVED);
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE type = 'TRANSFER_ON_THE_WAY' "
                + "AND link LIKE :link", new MapSqlParameterSource("link", "%" + done.id()), Long.class))
                .as("nobody to tell: it's already there").isZero();
    }

    @Test
    void aStorekeeperSendsOnlyFromTheirBranchReceivesOnlyAtTheirsAndIsToldItIsOnTheWay() {
        Company c = company("Keeper Sends");
        stockIn(c, c.main(), 10, null);
        stockIn(c, c.oshodi(), 10, null);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.oshodi());
        assertThat(branches(ade).sendTo()).as("names only, but every open branch")
                .extracting(BranchListResponse.BranchName::id).containsExactlyInAnyOrder(c.main(), c.oshodi());

        ResponseEntity<String> fromElsewhere =
                post(ade, "/api/stock-transfers", transferBody(c.main(), c.oshodi(), c.product(), 1), String.class);
        assertThat(fromElsewhere.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        TransferResponse toAde = send(c.owner(), c, c.main(), c.oshodi(), 2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM notifications WHERE user_id = :ade AND type = 'TRANSFER_ON_THE_WAY'
                """, new MapSqlParameterSource("ade", ade.user().id()), Long.class)).isEqualTo(1);

        TransferResponse fromAde = send(ade, c, c.oshodi(), c.main(), 3);
        assertThat(receiveRaw(ade, fromAde.id()).getStatusCode()).as("Head Office receives this one")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(receiveAsSent(ade, toAde.id()).status()).isEqualTo(StockTransferStatus.RECEIVED);

        ResponseEntity<String> again = receiveRaw(c.owner(), toAde.id());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("already received by");
        assertLedgerMatchesFigures(c);
    }

    /** Two people receive the same waybill at the same moment: it is received once. */
    @Test
    void twoPeopleReceivingTheSameWaybillAtOnceReceiveItOnce() throws Exception {
        Company c = company("Race Receive");
        stockIn(c, c.main(), 10, null);
        TransferResponse sent = send(c.owner(), c, c.main(), c.oshodi(), 4);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.oshodi());
        TenantLoginResponse bola = storekeeperAt(c, "bola", c.oshodi());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (TenantLoginResponse who : List.of(ade, bola)) {
                Callable<ResponseEntity<String>> receipt = () -> {
                    start.await();
                    return receiveRaw(who, sent.id());
                };
                results.add(pool.submit(receipt));
            }
            start.countDown();
            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> result : results) {
                statuses.add((HttpStatus) result.get().getStatusCode());
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(figure(c.oshodi(), c.product())).isEqualTo(4);
        assertLedgerMatchesFigures(c);
    }

    /** Sends both ways at once lock products in the same order, so neither waits on the other forever. */
    @Test
    void sendsBothWaysAtOnceWithTwoProductsDoNotDeadlock() throws Exception {
        Company c = company("Both Ways");
        UUID second = product(c.owner(), "Garri");
        for (UUID product : List.of(c.product(), second)) {
            stock(c.owner(), product, Map.of("quantity", 20, "branchId", c.main().toString()));
            stock(c.owner(), product, Map.of("quantity", 20, "branchId", c.oshodi().toString()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                boolean outward = i % 2 == 0;
                List<UUID> order = outward ? List.of(c.product(), second) : List.of(second, c.product());
                Map<String, Object> body = transferBody(outward ? c.main() : c.oshodi(), outward ? c.oshodi() : c.main(), order);
                results.add(pool.submit(() -> {
                    start.await();
                    return post(c.owner(), "/api/stock-transfers", body, String.class);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<String>> result : results) {
                assertThat(result.get().getStatusCode()).as(result.get().getBody()).isEqualTo(HttpStatus.CREATED);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("SELECT number FROM stock_transfers WHERE client_id = :c ORDER BY number",
                new MapSqlParameterSource("c", c.clientId()), Integer.class)).containsExactly(1, 2, 3, 4, 5, 6);
        assertLedgerMatchesFigures(c);
    }

    @Test
    void theTransfersListShowsWhatTouchesTheBranchesYouSee() {
        Company c = company("Transfer List");
        stockIn(c, c.main(), 10, null);
        UUID bolade = addBranch(c.owner(), "Bolade").getBody().id();
        send(c.owner(), c, c.main(), c.oshodi(), 1);
        send(c.owner(), c, c.main(), bolade, 1);
        TenantLoginResponse ade = storekeeperAt(c, "ade", c.oshodi());

        ResponseEntity<String> forAde = restTemplate.exchange("/api/stock-transfers?openOnly=true", HttpMethod.GET,
                new HttpEntity<>(auth(ade)), String.class);

        assertThat(forAde.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(forAde.getBody()).contains("Oshodi").doesNotContain("Bolade");
        assertThat(branchHealthService.health().totalMismatchProducts()).isZero();
    }

    // ------------------------------------------------------------------------------------ helpers

    private record Company(TenantLoginResponse owner, UUID clientId, UUID main, UUID oshodi, UUID product) {
    }

    private Company company(String name) {
        TenantLoginResponse owner = signup(name);
        UUID product = product(owner, "Rice");
        UUID main = branches(owner).branches().getFirst().id();
        UUID oshodi = addBranch(owner, "Oshodi").getBody().id();
        UUID clientId = jdbc.queryForObject("SELECT client_id FROM branches WHERE id = :b",
                new MapSqlParameterSource("b", main), UUID.class);
        return new Company(owner, clientId, main, oshodi, product);
    }

    /** Ledger at each branch, signed as section 4.3 says, equals the branch's figure. */
    private void assertLedgerMatchesFigures(Company c) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.id AS branch_id,
                       COALESCE((SELECT SUM(CASE m.movement_type
                                   WHEN 'IN' THEN m.quantity WHEN 'TRANSFER_IN' THEN m.quantity
                                   WHEN 'OUT' THEN -m.quantity WHEN 'TRANSFER_OUT' THEN -m.quantity
                                   ELSE m.quantity END)
                                 FROM stock_movements m WHERE m.branch_id = b.id AND m.product_id = :p), 0) AS ledger,
                       COALESCE((SELECT bs.quantity_on_hand FROM branch_stock bs
                                 WHERE bs.branch_id = b.id AND bs.product_id = :p), 0) AS figure
                FROM branches b WHERE b.client_id = :c
                """, new MapSqlParameterSource().addValue("p", c.product()).addValue("c", c.clientId()));
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("ledger")).intValue())
                .as("ledger at %s", row.get("branch_id")).isEqualTo(((Number) row.get("figure")).intValue()));
    }

    private TransferResponse send(TenantLoginResponse who, Company c, UUID from, UUID to, int quantity) {
        ResponseEntity<TransferResponse> response =
                post(who, "/api/stock-transfers", transferBody(from, to, c.product(), quantity), TransferResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private static Map<String, Object> transferBody(UUID from, UUID to, UUID product, int quantity) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", UUID.randomUUID().toString());
        body.put("fromBranchId", from.toString());
        body.put("toBranchId", to.toString());
        body.put("carriedByName", "Musa");
        body.put("lines", List.of(Map.of("productId", product.toString(), "quantity", quantity)));
        return body;
    }

    private static Map<String, Object> transferBody(UUID from, UUID to, List<UUID> products) {
        Map<String, Object> body = transferBody(from, to, products.getFirst(), 1);
        body.put("lines", products.stream().map(p -> Map.of("productId", p.toString(), "quantity", 1)).toList());
        return body;
    }

    private static Map<String, Object> lineOf(UUID product, int received, int missing, int damaged) {
        return Map.of("productId", product.toString(), "received", received, "missing", missing, "damaged", damaged);
    }

    private static Map<String, Object> receiptBody(UUID id, Map<String, Object> line) {
        Map<String, Object> body = new HashMap<>();
        body.put("id", id.toString());
        body.put("lines", List.of(line));
        return body;
    }

    private ResponseEntity<TransferResponse> receive(TenantLoginResponse who, UUID transfer, Map<String, Object> line) {
        ResponseEntity<TransferResponse> response = receive(who, transfer, line, TransferResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private <T> ResponseEntity<T> receive(TenantLoginResponse who, UUID transfer, Map<String, Object> line, Class<T> type) {
        return post(who, "/api/stock-transfers/" + transfer + "/receipts", receiptBody(UUID.randomUUID(), line), type);
    }

    private TransferResponse receiveAsSent(TenantLoginResponse who, UUID transfer) {
        ResponseEntity<TransferResponse> response = post(who, "/api/stock-transfers/" + transfer + "/receipts",
                Map.of("id", UUID.randomUUID().toString(), "asSent", true), TransferResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<String> receiveRaw(TenantLoginResponse who, UUID transfer) {
        return post(who, "/api/stock-transfers/" + transfer + "/receipts",
                Map.of("id", UUID.randomUUID().toString(), "asSent", true), String.class);
    }

    private long transfersOf(Company c) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM stock_transfers WHERE client_id = :c",
                new MapSqlParameterSource("c", c.clientId()), Long.class);
    }

    private void stockIn(Company c, UUID branch, int quantity, String unitPrice) {
        Map<String, Object> body = new HashMap<>();
        body.put("quantity", quantity);
        body.put("branchId", branch.toString());
        if (unitPrice != null) body.put("unitPrice", unitPrice);
        stock(c.owner(), c.product(), body);
    }

    private StockMutationResponse stock(TenantLoginResponse who, UUID product, Map<String, Object> body) {
        ResponseEntity<StockMutationResponse> response =
                post(who, "/api/products/" + product + "/stock/stock-in", body, StockMutationResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private int figure(UUID branchId, UUID productId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT quantity_on_hand FROM branch_stock WHERE branch_id = :b AND product_id = :p",
                new MapSqlParameterSource().addValue("b", branchId).addValue("p", productId), Integer.class);
        return found.isEmpty() ? 0 : found.getFirst();
    }

    private Map<UUID, BranchFigure> branchFigures(TenantLoginResponse who, UUID product) {
        Map<UUID, BranchFigure> byBranch = new HashMap<>();
        for (BranchFigure figure : productOf(who, product).branchStock()) {
            byBranch.put(figure.branchId(), figure);
        }
        return byBranch;
    }

    private TenantLoginResponse storekeeperAt(Company c, String username, UUID branch) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        body.put("roleId", roleRepository.findByName("STOREKEEPER").orElseThrow().getId().toString());
        body.put("branchIds", List.of(branch.toString()));
        assertThat(post(c.owner(), "/api/users", body, String.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(c.owner().user().clientIdentifier(), username, PASSWORD), TenantLoginResponse.class);
    }

    private BranchListResponse branches(TenantLoginResponse who) {
        return restTemplate.exchange("/api/branches", HttpMethod.GET, new HttpEntity<>(auth(who)), BranchListResponse.class)
                .getBody();
    }

    private ResponseEntity<BranchResponse> addBranch(TenantLoginResponse owner, String name) {
        ResponseEntity<BranchResponse> response = post(owner, "/api/branches", Map.of("name", name), BranchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private UUID product(TenantLoginResponse owner, String name) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("product", new HttpEntity<>(
                new CreateProductRequest(name + " " + unique, "TR-" + unique, null, null, null, null, null, null, null),
                jsonOnly()));
        HttpHeaders headers = auth(owner);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.exchange("/api/products", HttpMethod.POST, new HttpEntity<>(body, headers), ProductResponse.class)
                .getBody().id();
    }

    private ProductResponse productOf(TenantLoginResponse who, UUID product) {
        return restTemplate.exchange("/api/products/" + product, HttpMethod.GET, new HttpEntity<>(auth(who)),
                ProductResponse.class).getBody();
    }

    private <T> ResponseEntity<T> post(TenantLoginResponse who, String path, Object body, Class<T> type) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, json(who)), type);
    }

    private TenantLoginResponse signup(String name) {
        String unique = UUID.randomUUID().toString();
        return restTemplate.postForObject("/api/clients/signup",
                new ClientSignupRequest(name + " " + unique.substring(0, 8), null, "owner-" + unique + "@example.com",
                        PASSWORD, PASSWORD),
                TenantLoginResponse.class);
    }

    private static HttpHeaders jsonOnly() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders json(TenantLoginResponse who) {
        HttpHeaders headers = auth(who);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders auth(TenantLoginResponse who) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(who.tokens().accessToken());
        return headers;
    }
}
