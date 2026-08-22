package com.procurepal_services.stock_bridge_api.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.procurepal_services.stock_bridge_api.auth.dto.LoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginRequest;
import com.procurepal_services.stock_bridge_api.auth.dto.SuperAdminLoginResponse;
import com.procurepal_services.stock_bridge_api.auth.dto.TenantLoginResponse;
import com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest;
import com.procurepal_services.stock_bridge_api.entity.Client;
import com.procurepal_services.stock_bridge_api.entity.ClientType;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import com.procurepal_services.stock_bridge_api.entity.Role;
import com.procurepal_services.stock_bridge_api.entity.SuperAdmin;
import com.procurepal_services.stock_bridge_api.entity.User;
import com.procurepal_services.stock_bridge_api.entity.VendorLedgerEntryType;
import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus;
import com.procurepal_services.stock_bridge_api.order.OrderLifecycleService;
import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import com.procurepal_services.stock_bridge_api.repository.RoleRepository;
import com.procurepal_services.stock_bridge_api.repository.SuperAdminRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import com.procurepal_services.stock_bridge_api.repository.VendorLedgerEntryRepository;
import com.procurepal_services.stock_bridge_api.settlement.dto.EligibleVendorPayout;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchDetail;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutBatchSummary;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunPreview;
import com.procurepal_services.stock_bridge_api.settlement.dto.PayoutRunResponse;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementLine;
import com.procurepal_services.stock_bridge_api.settlement.dto.VendorStatementResponse;
import com.procurepal_services.stock_bridge_api.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * M7 end to end: commission accrual, escrow, reversal, biweekly payout batches and
 * the vendor statement.
 *
 * <h2>What this class is really testing</h2>
 * Money code does not fail by throwing. It fails by returning a plausible number,
 * so almost nothing below asserts "it worked" - it asserts a specific figure that a
 * near-miss implementation would get wrong:
 *
 * <ul>
 *   <li>The rounding fixtures are chosen so that half-up and half-even DISAGREE, and
 *       so that per-line rounding and order-level rounding differ by a kobo. An
 *       implementation that rounded in the wrong place, or once too often, passes a
 *       "commission is roughly 5%" assertion and fails these.</li>
 *   <li>The delivery fee on every fixture order is large and round, so a proceeds
 *       figure that wrongly included it would be obviously wrong rather than
 *       plausibly wrong.</li>
 *   <li>Every isolation test plants a SECOND vendor's money in the same window and
 *       asserts its ABSENCE, because a cross-vendor leak is a 200 with somebody
 *       else's balance in it, not an exception. Same reasoning, same shape, as
 *       {@code VendorWorkspaceIntegrationTest}.</li>
 * </ul>
 *
 * <h2>Why the fixtures are raw SQL and the ledger is written through the service</h2>
 * Orders are planted with JdbcTemplate for the reason the analytics tests give:
 * {@code created_at} is a {@code @CreationTimestamp} column JPA will not let a
 * caller set, and every figure here is a function of WHEN something happened. The
 * LEDGER, by contrast, is always written through {@code VendorLedgerService} - never
 * by SQL - because the arithmetic under test is exactly what that service does, and
 * a fixture that inserted ledger rows directly would be testing the fixture.
 *
 * <p>Accruals are dated into a fixed historical month so they are always older than
 * the current biweekly cutoff, whenever the suite runs. That is what lets a payout
 * run be tested at all without freezing the clock: eligibility deliberately has no
 * lower bound, so a 2019 entry is settleable today.
 *
 * <p>Every payout run in this class is scoped to ONE seller. An unscoped run would
 * sweep any other vendor with an unsettled balance into a batch this class then
 * failed to clean up.
 *
 * <p>Runs against the local docker-compose Postgres like every other integration
 * test here - see AuthIntegrationTest for why local Postgres over Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
class VendorSettlementIntegrationTest {

    private static final String FIXTURE_PREFIX = "PP-SETL-";
    private static final String VENDOR_SLUG_PREFIX = "settle-vendor-";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String SETTLEMENT = "/api/superadmin/settlement";
    private static final String STATEMENT = "/api/vendor/statement";

    /**
     * May 2019: far enough back that it is always older than the current fortnight's
     * cutoff, and a month no other test in the suite writes to.
     */
    private static final OffsetDateTime ACCRUED_AT = OffsetDateTime.parse("2019-05-06T09:00:00Z");

    private static final String WINDOW = "?from=2019-05-01T00:00:00Z&to=2019-06-01T00:00:00Z";

    /**
     * The rounding fixture. {@code 1250.50 x 0.0500 = 62.525} exactly - a true half -
     * so half-up gives 62.53 and half-even gives 62.52. Two such lines on one order
     * also separate per-line rounding from order-level rounding:
     * {@code 62.53 + 62.53 = 125.06}, while {@code round(2501.00 x 0.05) = 125.05}.
     * One kobo, and it is the whole point.
     */
    private static final BigDecimal AWKWARD_LINE = new BigDecimal("1250.50");

    private static final BigDecimal FIVE_PERCENT = new BigDecimal("0.0500");

    /** Deliberately large and round, so proceeds that wrongly included it are unmissable. */
    private static final BigDecimal DELIVERY_FEE = new BigDecimal("5000.00");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SuperAdminRepository superAdminRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private VendorLedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private VendorLedgerService vendorLedgerService;

    @Autowired
    private VendorSettlementService vendorSettlementService;

    @Autowired
    private OrderLifecycleService orderLifecycleService;

    @Autowired
    private EscrowHoldPolicy escrowHoldPolicy;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private VendorFixture vendorA;
    private VendorFixture vendorB;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        cleanFixtures();
        vendorA = createVendor("Settle Vendor A");
        vendorB = createVendor("Settle Vendor B");
        buyer = signupClientId("Settle Buyer");
    }

    @AfterEach
    void tearDown() {
        cleanFixtures();
        TenantContext.clear();
    }

    // ---------------------------------------------------------------------------------
    // (a) Commission arithmetic, and the rounding rule
    // ---------------------------------------------------------------------------------

    /**
     * The headline arithmetic test. Two identical awkward lines, one order.
     *
     * <p>Three separate claims, each of which a wrong implementation would break on
     * its own: half-UP (62.53, not 62.52), per LINE (125.06, not 125.05), and
     * proceeds that exclude the delivery fee (2501.00, not 7501.00).
     */
    @Test
    void commissionIsRoundedHalfUpPerLineAndExcludesTheDeliveryFee() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrue(orderId);

        List<Object[]> rows = jdbc.query(
                "SELECT entry_type, amount FROM vendor_ledger_entries WHERE order_id = ? ORDER BY entry_type, amount",
                (rs, i) -> new Object[] {rs.getString(1), rs.getBigDecimal(2)},
                orderId);

        assertThat(rows).hasSize(4);
        assertThat(sumOf(orderId, VendorLedgerEntryType.SALE_PROCEEDS))
                .as("proceeds are the goods lines only - the 5,000 delivery fee is the platform's")
                .isEqualByComparingTo("2501.00");
        assertThat(sumOf(orderId, VendorLedgerEntryType.COMMISSION))
                .as("62.53 + 62.53, half-UP per line - not 62.52 (half-even) and not 125.05 (rounded once, on the order)")
                .isEqualByComparingTo("-125.06");

        // And the individual rows, so a total that happened to be right by two
        // compensating errors is still caught.
        assertThat(rows)
                .filteredOn(row -> "COMMISSION".equals(row[0]))
                .extracting(row -> (BigDecimal) row[1])
                .allSatisfy(amount -> assertThat(amount).isEqualByComparingTo("-62.53"));

        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("2375.94");
    }

    /**
     * A null rate is not a zero rate. V11 is explicit that null means "no commission
     * applies" and zero means "we agreed they pay nothing", and the ledger has to keep
     * them apart or the second becomes unprovable.
     */
    @Test
    void aNullRatePostsNoCommissionRowAtAllAndAnAgreedZeroPostsOne() {
        UUID noRate = plantDeliveredOrder(vendorA, null, new BigDecimal("1000.00"));
        UUID zeroRate = plantDeliveredOrder(vendorA, new BigDecimal("0.0000"), new BigDecimal("1000.00"));
        accrue(noRate);
        accrue(zeroRate);

        assertThat(countOf(noRate, VendorLedgerEntryType.COMMISSION))
                .as("null rate: no row, because there is no fee to state")
                .isZero();
        assertThat(countOf(zeroRate, VendorLedgerEntryType.COMMISSION))
                .as("agreed zero rate: a row saying zero, because that is a commercial fact worth showing")
                .isEqualTo(1);
        assertThat(sumOf(zeroRate, VendorLedgerEntryType.COMMISSION)).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------------------------
    // (b) When accrual fires, and when it must not
    // ---------------------------------------------------------------------------------

    /**
     * The real funnel: the buyer confirming receipt, through
     * {@code OrderLifecycleService.markReceived}. Not the service called directly -
     * the whole point of hooking the lifecycle is that every route into RECEIVED
     * carries the consequence.
     */
    @Test
    void confirmingReceiptAccruesExactlyOnce() {
        UUID orderId = plantOrder(vendorA, OrderStatus.DELIVERED, "PAID", FIVE_PERCENT, new BigDecimal("1000.00"));

        inTransaction(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            orderLifecycleService.markReceived(order, null);
        });

        assertThat(ledgerEntryRepository.findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(orderId))
                .hasSize(2);
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("950.00");
    }

    /**
     * Idempotency, from the direction that can actually happen: the buyer confirms at
     * the same moment the escrow sweep decides the fourteen days are up. Both paths
     * call the same method, and the second must be a no-op rather than a doubled
     * balance.
     */
    @Test
    void accrualIsIdempotentWhenTheSameDeliveryIsConfirmedTwice() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));

        int first = accrue(orderId);
        int second = accrue(orderId);
        int third = accrue(orderId);

        assertThat(first).isEqualTo(2);
        assertThat(second).as("a repeat writes nothing and does not throw").isZero();
        assertThat(third).isZero();
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("950.00");
    }

    /**
     * The database's own guarantee, below the check-first shortcut: two genuinely
     * simultaneous posts of the same event collide rather than both landing. Written
     * straight through SQL, which is the shape of the race the service's pre-check
     * cannot close.
     */
    @Test
    void theDatabaseRefusesTwoLedgerRowsForTheSameEvent() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);

        String key = jdbc.queryForObject(
                "SELECT idempotency_key FROM vendor_ledger_entries WHERE order_id = ? AND entry_type = 'SALE_PROCEEDS'",
                String.class,
                orderId);

        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO vendor_ledger_entries (seller_client_id, entry_type, amount, currency, "
                                + "order_id, order_item_id, occurred_at, idempotency_key) "
                                + "SELECT seller_client_id, entry_type, amount, currency, order_id, order_item_id, "
                                + "occurred_at, ? FROM vendor_ledger_entries WHERE idempotency_key = ?",
                        key,
                        key))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Nothing accrues before delivery is confirmed, however the order was paid. This
     * is the whole stakeholder decision in one assertion - a vendor is not paid for
     * what has not been delivered - and the statuses below are every state an order
     * can be in on the way there.
     */
    @Test
    void nothingAccruesForAnUndeliveredOrderHoweverItWasPaid() {
        List<UUID> undelivered = List.of(
                plantOrder(vendorA, OrderStatus.PENDING_PAYMENT, "PENDING", FIVE_PERCENT, new BigDecimal("1000.00")),
                plantOrder(vendorA, OrderStatus.PLACED, "PAID", FIVE_PERCENT, new BigDecimal("1000.00")),
                plantOrder(vendorA, OrderStatus.CONFIRMED, "PAID", FIVE_PERCENT, new BigDecimal("1000.00")),
                plantOrder(vendorA, OrderStatus.PROCESSING, "PAID", FIVE_PERCENT, new BigDecimal("1000.00")),
                plantOrder(vendorA, OrderStatus.OUT_FOR_DELIVERY, "PAID", FIVE_PERCENT, new BigDecimal("1000.00")),
                // Delivered, but the buyer has not signed for it. The seller's own
                // assertion is deliberately not enough - see VendorLedgerService.
                plantOrder(vendorA, OrderStatus.DELIVERED, "PAID", FIVE_PERCENT, new BigDecimal("1000.00")),
                // Paid on delivery, which vendor baskets cannot actually choose, and
                // which must still earn nothing if one ever appears.
                plantOrder(vendorA, OrderStatus.DELIVERED, "ON_DELIVERY", FIVE_PERCENT, new BigDecimal("1000.00")));

        for (UUID orderId : undelivered) {
            assertThat(ledgerEntryRepository.findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(orderId))
                    .as("order %s", orderId)
                    .isEmpty();
        }
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("0.00");
    }

    /** ProcurePal sells, but the platform owing itself commission is not a thing. Its ledger stays empty. */
    @Test
    void thePlatformOwnersOwnSalesNeverAccrue() {
        UUID orderId = plantOrder(procurePal(), OrderStatus.DELIVERED, "PAID", FIVE_PERCENT, new BigDecimal("9999.00"));
        accrue(orderId);

        assertThat(ledgerEntryRepository.findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(orderId))
                .isEmpty();
        assertThat(vendorLedgerService.balanceFor(platformOwnerId())).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------------------------
    // (c) Escrow release for a delivery the buyer never confirmed
    // ---------------------------------------------------------------------------------

    /**
     * The timeout rule. An order delivered long ago and never confirmed accrues
     * anyway - and the order stays DELIVERED, because moving it to RECEIVED would
     * write stock into somebody else's inventory, which the platform must never do on
     * a buyer's behalf.
     */
    @Test
    void anUnconfirmedDeliveryReleasesEscrowAfterTheTimeoutWithoutTouchingTheOrder() {
        UUID stale = plantOrder(
                vendorA,
                OrderStatus.DELIVERED,
                "PAID",
                FIVE_PERCENT,
                OffsetDateTime.now().minusDays(VendorLedgerService.ESCROW_RELEASE_DAYS + 1),
                new BigDecimal("1000.00"));
        UUID fresh = plantOrder(
                vendorA,
                OrderStatus.DELIVERED,
                "PAID",
                FIVE_PERCENT,
                OffsetDateTime.now().minusDays(1),
                new BigDecimal("4000.00"));

        vendorLedgerService.releaseUnconfirmedDeliveries();

        assertThat(sumOf(stale, VendorLedgerEntryType.SALE_PROCEEDS)).isEqualByComparingTo("1000.00");
        assertThat(ledgerEntryRepository.findAllByOrderIdOrderByOccurredAtAscCreatedAtAsc(fresh))
                .as("one day old: still in escrow, still the buyer's call")
                .isEmpty();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, stale))
                .as("escrow release is not receipt - only the buyer may move stock into their own inventory")
                .isEqualTo("DELIVERED");
    }

    /** And the sweep is safe to repeat, which it has to be: it runs every hour. */
    @Test
    void theEscrowSweepIsIdempotent() {
        plantOrder(
                vendorA,
                OrderStatus.DELIVERED,
                "PAID",
                FIVE_PERCENT,
                OffsetDateTime.now().minusDays(VendorLedgerService.ESCROW_RELEASE_DAYS + 1),
                new BigDecimal("1000.00"));

        vendorLedgerService.releaseUnconfirmedDeliveries();
        BigDecimal afterFirst = vendorLedgerService.balanceFor(vendorA.clientId());
        vendorLedgerService.releaseUnconfirmedDeliveries();
        vendorLedgerService.releaseUnconfirmedDeliveries();

        assertThat(afterFirst).isEqualByComparingTo("950.00");
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo(afterFirst);
    }

    // ---------------------------------------------------------------------------------
    // (d) Reversal - the thing VENDOR_RESEARCH says breaks first
    // ---------------------------------------------------------------------------------

    /**
     * A refund after delivery reverses the proceeds AND the commission. Reversing only
     * the sale would leave the platform holding a fee on a sale that did not happen,
     * which is the specific failure Section C item 6 names.
     */
    @Test
    void aRefundAfterDeliveryReversesBothProceedsAndCommission() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrue(orderId);
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("2375.94");

        ResponseEntity<String> response = restTemplate.exchange(
                SETTLEMENT + "/orders/" + orderId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Goods returned damaged"), authHeaders(superAdminToken())),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sumOf(orderId, VendorLedgerEntryType.SALE_REVERSAL)).isEqualByComparingTo("-2501.00");
        assertThat(sumOf(orderId, VendorLedgerEntryType.COMMISSION_REVERSAL))
                .as("the fee comes back at exactly what was charged - 125.06, not a recomputed 125.05")
                .isEqualByComparingTo("125.06");
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId()))
                .as("a fully reversed order leaves the vendor exactly where they started")
                .isEqualByComparingTo("0.00");

        assertThat(jdbc.queryForObject("SELECT payment_status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("REFUNDED");

        // Nothing was edited or deleted: the original rows are still there, and the
        // corrections name them.
        assertThat(countOf(orderId, VendorLedgerEntryType.SALE_PROCEEDS)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM vendor_ledger_entries WHERE order_id = ? AND reverses_entry_id IS NOT NULL",
                        Integer.class,
                        orderId))
                .isEqualTo(4);
    }

    /** Reversing twice does not credit the buyer twice. */
    @Test
    void reversingAnAlreadyReversedOrderIsRefusedRatherThanRepeated() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);

        assertThat(reverse(orderId).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reverse(orderId).getStatusCode())
                .as("409, and loudly: reversing an order that earned nothing is somebody on the wrong order")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("0.00");
    }

    /**
     * The clawback case. A refund AFTER the vendor was paid drives the balance
     * negative, and no batch is created for them until new sales clear it - which is
     * VENDOR_RESEARCH Section A's "block next payout until cleared", achieved with no
     * extra state at all.
     */
    @Test
    void aRefundAfterPayoutLeavesANegativeBalanceAndBlocksTheNextPayout() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);
        PayoutBatchSummary batch = runAndPay(vendorA);
        assertThat(batch.netAmount()).isEqualByComparingTo("950.00");
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("0.00");

        reverse(orderId);

        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("-950.00");
        PayoutRunResponse rerun = run(vendorA);
        assertThat(rerun.batchesCreated()).isZero();
        assertThat(rerun.skipped()).isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------
    // (e) The three escrow states
    // ---------------------------------------------------------------------------------

    /**
     * The escrow figures, all three at once, from one vendor with one order in each
     * state. This is the test that would catch a definition drifting: pending money
     * counted as held, or held money counted twice because it is also pending.
     */
    @Test
    void escrowSeparatesPendingFromHeldFromSettled() {
        // Paid by the buyer, not yet confirmed delivered: pending. Not in the ledger.
        plantOrder(vendorA, OrderStatus.DELIVERED, "PAID", FIVE_PERCENT, new BigDecimal("2000.00"));
        // Confirmed delivered: held, and payable at the next run.
        UUID confirmed = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(confirmed);

        VendorStatementResponse statement = statementAs(vendorA);

        assertThat(statement.escrow().pendingProceeds()).isEqualByComparingTo("2000.00");
        assertThat(statement.escrow().pendingProjectedCommission()).isEqualByComparingTo("100.00");
        assertThat(statement.escrow().pendingNet())
                .as("paid by the buyer but not yet earned: neither held nor payable")
                .isEqualByComparingTo("1900.00");
        assertThat(statement.escrow().pendingOrderCount()).isEqualTo(1);

        assertThat(statement.escrow().heldBalance())
                .as("delivered and confirmed, not yet paid out: held")
                .isEqualByComparingTo("950.00");
        assertThat(statement.escrow().payableNow()).isEqualByComparingTo("950.00");
        assertThat(statement.escrow().inFlight())
                .as("nothing has been batched yet")
                .isEqualByComparingTo("0.00");

        // Now batch it: still held and still owed, but no longer payable at the next
        // run - it is on an instruction somebody is about to pay.
        run(vendorA);
        VendorStatementResponse batched = statementAs(vendorA);
        assertThat(batched.escrow().heldBalance()).isEqualByComparingTo("950.00");
        assertThat(batched.escrow().payableNow()).isEqualByComparingTo("0.00");
        assertThat(batched.escrow().inFlight()).isEqualByComparingTo("950.00");

        // And once the transfer is recorded, the balance is discharged - by a new
        // ledger row, not by deleting anything.
        markPaid(latestBatch(vendorA).id());
        VendorStatementResponse settled = statementAs(vendorA);
        assertThat(settled.escrow().heldBalance()).isEqualByComparingTo("0.00");
        assertThat(settled.escrow().payableNow()).isEqualByComparingTo("0.00");
        assertThat(settled.escrow().inFlight()).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------------------------
    // (e2) The MATURITY HOLD (M9) - the change this module exists for
    // ---------------------------------------------------------------------------------

    /**
     * The headline claim of M9, in one test and in both directions: a confirmed but
     * IMMATURE entry is excluded from a payout run, and the SAME entry after maturity
     * is included.
     *
     * <p>Nothing about the fixture changes between the two halves except the ledger's
     * own {@code matures_at}, which is moved on the row rather than re-accrued -
     * through the append-only escape hatch, deliberately, because re-accruing would
     * mean a different entry and would prove only that two different orders behave
     * differently. Moving the maturity is the closest a test can get to waiting seven
     * days.
     *
     * <p>The delivery is dated into the historical window so {@code occurred_at} is
     * always far behind the current cutoff. That isolates the variable: the first run
     * is refused for exactly one reason, and it is not the fortnight.
     */
    @Test
    void aConfirmedButImmatureEntryIsExcludedFromAPayoutRunAndTheSameEntryIsIncludedOnceMature() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);

        // Immature: hold still running, however long ago the sale occurred.
        setMaturity(orderId, OffsetDateTime.now().plusDays(3));

        assertThat(vendorLedgerService.balanceFor(vendorA.clientId()))
                .as("it HAS accrued - the buyer confirmed, so the vendor has earned it and it is a real balance")
                .isEqualByComparingTo("950.00");

        PayoutRunResponse tooEarly = run(vendorA);
        assertThat(tooEarly.batchesCreated())
                .as("but no run may claim it while the hold is running")
                .isZero();
        assertThat(tooEarly.skipped()).isEqualTo(1);
        assertThat(countOf(null, VendorLedgerEntryType.PAYOUT)).isZero();

        // And the statement says so rather than leaving the vendor to infer it.
        VendorStatementResponse held = statementAs(vendorA);
        assertThat(held.escrow().heldBalance()).isEqualByComparingTo("950.00");
        assertThat(held.escrow().maturing())
                .as("earned, not payable - the bucket M9 exists to create")
                .isEqualByComparingTo("950.00");
        assertThat(held.escrow().payableNow()).isEqualByComparingTo("0.00");
        assertThat(held.escrow().nextMaturityAt()).isNotNull();
        assertThat(held.escrow().maturingTranches()).hasSize(1);
        assertThat(held.escrow().maturingTranches().getFirst().amount()).isEqualByComparingTo("950.00");
        assertThat(held.escrow().maturingTranches().getFirst().payableOnRunAfter())
                .as("the second clock: the first fortnight cutoff AFTER it ripens, not the day it ripens")
                .isAfter(held.escrow().maturingTranches().getFirst().maturesAt());

        // Now let the hold expire. Same rows, same amounts, same vendor.
        setMaturity(orderId, ACCRUED_AT.plusDays(7));

        PayoutRunResponse now = run(vendorA);
        assertThat(now.batchesCreated()).isEqualTo(1);
        assertThat(now.totalNet()).isEqualByComparingTo("950.00");
        assertThat(now.batches().getFirst().lineCount())
                .as("both rows of the accrual, together - the commission never matures apart from its sale")
                .isEqualTo(2);

        VendorStatementResponse batched = statementAs(vendorA);
        assertThat(batched.escrow().maturing()).isEqualByComparingTo("0.00");
        assertThat(batched.escrow().inFlight()).isEqualByComparingTo("950.00");
    }

    /**
     * The maturity a row is stamped with is the hold that was in force when it
     * accrued, and CHANGING THE SETTING DOES NOT MOVE IT.
     *
     * <p>This is the question an operator asks first and the one a silent wrong answer
     * is worst on: an implementation that computed maturity from the current setting
     * would move every vendor's already-promised payment date the moment somebody
     * touched the number, with no row anywhere recording that it moved. Here the hold
     * is raised to 60 days AFTER the accrual and the entry's own date does not budge.
     */
    @Test
    void changingTheHoldDoesNotRestampMoneyThatHasAlreadyAccrued() {
        int originalHold = escrowHoldPolicy.holdDays();
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);

        java.time.Instant stamped = maturityOf(orderId).toInstant();
        assertThat(stamped)
                .as("stamped at accrual from the hold then in force")
                .isEqualTo(ACCRUED_AT.plusDays(originalHold).toInstant());

        try {
            jdbc.update("UPDATE vendor_settlement_settings SET escrow_hold_days = 60 WHERE singleton");

            assertThat(maturityOf(orderId).toInstant())
                    .as("FUTURE ACCRUALS ONLY - the ledger is append-only, so this can never be restamped")
                    .isEqualTo(stamped);

            // A new confirmation gets the NEW hold, which is the other half of the rule.
            UUID later = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("500.00"));
            accrue(later);
            assertThat(maturityOf(later).toInstant())
                    .as("and a change DOES apply from now on - otherwise the setting would do nothing")
                    .isEqualTo(ACCRUED_AT.plusDays(60).toInstant());
        } finally {
            jdbc.update("UPDATE vendor_settlement_settings SET escrow_hold_days = ? WHERE singleton", originalHold);
        }
    }

    /**
     * A refund DURING the hold window - which is the entire reason the hold exists.
     *
     * <p>The sale is immature; the reversal is not, because a correction never waits.
     * The two together mean the vendor is owed exactly zero and no run pays anything,
     * which is the outcome the owner asked for ("locked for 7 days to protect the
     * buyer and us from fraud"). The intermediate figures are asserted too, because
     * they are surprising and correct: a NEGATIVE payable sitting against an equal
     * positive maturing.
     */
    @Test
    void aRefundInsideTheHoldWindowReversesCorrectlyAndNothingIsPaidWhileTheHoldRuns() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrue(orderId);
        setMaturity(orderId, OffsetDateTime.now().plusDays(4));

        assertThat(reverse(orderId).getStatusCode()).isEqualTo(HttpStatus.OK);

        // The reversal reverses in full, at exactly what was charged - the M7 guarantee,
        // unaffected by the sale being immature.
        assertThat(sumOf(orderId, VendorLedgerEntryType.SALE_REVERSAL)).isEqualByComparingTo("-2501.00");
        assertThat(sumOf(orderId, VendorLedgerEntryType.COMMISSION_REVERSAL))
                .as("125.06, not a recomputed 125.05")
                .isEqualByComparingTo("125.06");
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId()))
                .as("a fully reversed order leaves the vendor exactly where they started")
                .isEqualByComparingTo("0.00");

        // The corrections are immediately mature and the sale is not, which is what
        // makes this safe: the run's net can only go DOWN while the hold runs.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM vendor_ledger_entries WHERE order_id = ? "
                                + "AND entry_type IN ('SALE_REVERSAL','COMMISSION_REVERSAL') "
                                + "AND matures_at = occurred_at",
                        Integer.class,
                        orderId))
                .as("a reversal that had to serve its own hold could land after the payout it reverses")
                .isEqualTo(4);

        VendorStatementResponse statement = statementAs(vendorA);
        assertThat(statement.escrow().maturing()).isEqualByComparingTo("2375.94");
        assertThat(statement.escrow().payableNow())
                .as("negative, legitimately: the correction is mature and the sale it corrects is not")
                .isEqualByComparingTo("-2375.94");
        assertThat(statement.escrow().heldBalance())
                .as("and the two net to what the vendor is actually owed")
                .isEqualByComparingTo("0.00");

        // And no run pays anything while the hold is running, which is the whole point:
        // the money the refund clawed back was never eligible to leave.
        PayoutRunResponse attempted = run(vendorA);
        assertThat(attempted.batchesCreated()).isZero();
        assertThat(countOf(null, VendorLedgerEntryType.PAYOUT)).isZero();

        // What happens AFTER the hold expires is M7's clawback rule and not M9's, and it
        // is worth naming rather than asserting here because the two interact in a way a
        // reader would otherwise assume wrongly. The reversal is stamped when the
        // OPERATOR acted, which is now - after the current fortnight's cutoff - so a run
        // made today would see the matured sale and not yet the reversal, and would
        // batch it. That is the pre-existing behaviour tested by
        // aRefundAfterPayoutLeavesANegativeBalanceAndBlocksTheNextPayout: the reversal
        // lands in the NEXT run, drives the balance negative, and nets off against
        // future sales. The hold narrows that window; it does not close it, and
        // pretending otherwise here would be a test asserting something the code does
        // not do.
    }

    /** The database's own guarantees about maturity, below every service that could be edited. */
    @Test
    void theDatabaseRefusesAMaturityBeforeItsEventAndAHeldCorrection() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);
        UUID proceeds = jdbc.queryForObject(
                "SELECT id FROM vendor_ledger_entries WHERE order_id = ? AND entry_type = 'SALE_PROCEEDS'",
                UUID.class,
                orderId);

        // The UPDATE has to go around the append-only trigger, because that trigger
        // would refuse it first and the test would prove nothing about the CHECK. The
        // INSERT does not need to (the trigger is UPDATE/DELETE only) and goes through
        // the same helper only so the two read alike.
        assertThatThrownBy(() -> withLedgerMaintenance(
                        "UPDATE vendor_ledger_entries SET matures_at = occurred_at - INTERVAL '1 day' WHERE id = '"
                                + proceeds + "'"))
                .hasMessageContaining("chk_vendor_ledger_entries_maturity");

        assertThatThrownBy(() -> withLedgerMaintenance(
                        "INSERT INTO vendor_ledger_entries (seller_client_id, entry_type, amount, currency, "
                                + "order_id, order_item_id, reverses_entry_id, occurred_at, matures_at, "
                                + "idempotency_key) SELECT seller_client_id, 'SALE_REVERSAL', -1.00, currency, "
                                + "order_id, order_item_id, id, occurred_at, occurred_at + INTERVAL '7 days', "
                                + "'M9-HELD-REVERSAL-TEST' FROM vendor_ledger_entries WHERE id = '" + proceeds + "'"))
                .as("a correction that waits is the failure the hold exists to prevent, arriving through the hold")
                .hasMessageContaining("chk_vendor_ledger_entries_correction_is_immediate");
    }

    /**
     * Moves an order's accrual maturity - the only way a test can simulate seven days
     * passing on a row the application is not allowed to touch.
     *
     * <p>The fact that this needs a sledgehammer is itself the point of the design. Which
     * sledgehammer it needs changed with V16, and the history is worth keeping because the
     * warning it removes was a real one.
     *
     * <p>This used to DROP the append-only trigger for the duration, because V14's
     * documented {@code app.ledger_maintenance} hatch could not be used for an UPDATE:
     * its branch did {@code RETURN COALESCE(OLD, NEW)}, which is right for a DELETE (the
     * only thing this suite had ever needed it for) but on an UPDATE returns the OLD row,
     * so the statement succeeded, reported rows affected, and changed nothing. A test
     * built on it would have passed vacuously, which is why this helper avoided it and
     * why V15's header carries a warning to anyone writing a hand-corrective data fix.
     *
     * <p>V16 fixed the function - {@code NEW} for an UPDATE, {@code OLD} for a DELETE -
     * so the documented hatch is now the honest tool for this and the DDL is gone. That
     * also means this suite is a standing regression test for the fix: if the hatch ever
     * silently no-ops again, every maturity assertion below fails at once, rather than a
     * migration finding out in production.
     * {@code LedgerAppendOnlyTriggerIntegrationTest} covers the four cases directly.
     */
    private void setMaturity(UUID orderId, OffsetDateTime maturesAt) {
        withLedgerMaintenance("UPDATE vendor_ledger_entries SET matures_at = '" + maturesAt
                + "' WHERE order_id = '" + orderId + "' AND entry_type IN ('SALE_PROCEEDS','COMMISSION')");
    }

    private OffsetDateTime maturityOf(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT matures_at FROM vendor_ledger_entries WHERE order_id = ? AND entry_type = 'SALE_PROCEEDS'",
                OffsetDateTime.class,
                orderId);
    }

    /**
     * One statement, run with V14's maintenance escape hatch open, on ONE connection.
     *
     * <p>The single connection is the whole trick: {@code app.ledger_maintenance} is
     * session state and a JdbcTemplate call takes whichever connection the pool hands it,
     * so a bare {@code SET} followed by a separate statement would very likely land on two
     * different sessions and be refused. Same reasoning {@link #cleanFixtures()} gives for
     * its own use of the setting.
     *
     * <p>RESET in a finally, always: the pool is shared with every other test in the suite,
     * and a connection handed back with the guard disarmed would silently exempt whatever
     * ran next.
     *
     * <p>Note this deliberately does NOT suspend the table's CHECK constraints - a BEFORE
     * trigger returning NEW hands the row straight on to constraint evaluation - which is
     * what lets the maturity and correction-window constraints below be tested through this
     * helper at all.
     */
    private void withLedgerMaintenance(String sql) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.ledger_maintenance = 'on'");
                try {
                    statement.execute(sql);
                } finally {
                    statement.execute("RESET app.ledger_maintenance");
                }
            }
            return null;
        });
    }

    // ---------------------------------------------------------------------------------
    // (f) Payout batches
    // ---------------------------------------------------------------------------------

    /**
     * A batch contains exactly the eligible lines and nothing else, and re-running does
     * not pay twice. The second half is the one that matters: a run that double-pays
     * looks completely normal until a bank statement is reconciled.
     */
    @Test
    void aPayoutBatchClaimsExactlyTheEligibleLinesAndCannotBeRunTwice() {
        UUID eligible = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(eligible);
        // Same vendor, delivered, but never confirmed and too recent to auto-release.
        plantOrder(vendorA, OrderStatus.DELIVERED, "PAID", FIVE_PERCENT, new BigDecimal("7777.00"));
        // Another vendor's money, in the same period.
        UUID theirs = plantDeliveredOrder(vendorB, FIVE_PERCENT, new BigDecimal("3000.00"));
        accrue(theirs);

        PayoutRunResponse first = run(vendorA);

        assertThat(first.batchesCreated()).isEqualTo(1);
        assertThat(first.totalNet()).isEqualByComparingTo("950.00");
        PayoutBatchSummary batch = first.batches().getFirst();
        assertThat(batch.sellerClientId()).isEqualTo(vendorA.clientId());
        assertThat(batch.status()).isEqualTo(VendorPayoutBatchStatus.PENDING);
        assertThat(batch.lineCount()).isEqualTo(2);
        assertThat(batch.proceedsTotal()).isEqualByComparingTo("1000.00");
        assertThat(batch.commissionTotal()).isEqualByComparingTo("-50.00");

        PayoutBatchDetail detail = batchDetail(batch.id());
        assertThat(detail.lines()).hasSize(2);
        assertThat(detail.lines()).allSatisfy(line -> assertThat(line.orderId()).isEqualTo(eligible));
        assertThat(detail.linesTotal())
                .as("the frozen total still agrees with the lines behind it")
                .isEqualByComparingTo(batch.netAmount());

        PayoutRunResponse second = run(vendorA);
        assertThat(second.batchesCreated()).as("a second click pays nothing again").isZero();
        assertThat(second.skipped()).isEqualTo(1);
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("950.00");
    }

    /**
     * Marking a batch paid is the ONLY thing that posts a payout entry, and it needs a
     * human and a bank reference. There is no automated disbursement in this system,
     * so those two are the entire evidence that a vendor was paid.
     */
    @Test
    void aBatchIsPaidOnlyByAHumanRecordingATransfer() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);
        PayoutBatchSummary pending = run(vendorA).batches().getFirst();

        assertThat(countOf(null, VendorLedgerEntryType.PAYOUT))
                .as("a PENDING batch has moved no money, so the ledger says nothing")
                .isZero();

        // A missing reference is refused before anything is written.
        ResponseEntity<String> noReference = restTemplate.exchange(
                SETTLEMENT + "/batches/" + pending.id() + "/mark-paid",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "sent it, honest"), authHeaders(superAdminToken())),
                String.class);
        assertThat(noReference.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        PayoutBatchSummary paid = markPaid(pending.id());
        assertThat(paid.status()).isEqualTo(VendorPayoutBatchStatus.PAID);
        assertThat(paid.settledAt()).isNotNull();
        assertThat(paid.settledBy()).isNotNull();
        assertThat(paid.paymentReference()).isNotBlank();
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("0.00");

        // Twice is a 409, not a second payout entry.
        assertThat(restTemplate
                        .exchange(
                                SETTLEMENT + "/batches/" + pending.id() + "/mark-paid",
                                HttpMethod.POST,
                                new HttpEntity<>(
                                        Map.of("paymentReference", "TRF-DUPLICATE"), authHeaders(superAdminToken())),
                                String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("0.00");
    }

    /**
     * A bounced transfer releases the batch's claim so the money goes into the next
     * run, and posts nothing to the ledger - because nothing happened to the money.
     */
    @Test
    void aFailedBatchReleasesItsLinesBackIntoTheNextRun() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);
        PayoutBatchSummary first = run(vendorA).batches().getFirst();

        ResponseEntity<PayoutBatchSummary> failed = restTemplate.exchange(
                SETTLEMENT + "/batches/" + first.id() + "/mark-failed",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Account name did not match"), authHeaders(superAdminToken())),
                PayoutBatchSummary.class);

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failed.getBody().status()).isEqualTo(VendorPayoutBatchStatus.FAILED);
        assertThat(failed.getBody().settledAt())
                .as("'when was it settled' must have no answer for a batch that was not")
                .isNull();
        assertThat(countOf(null, VendorLedgerEntryType.PAYOUT)).isZero();
        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("950.00");

        PayoutRunResponse rerun = run(vendorA);
        assertThat(rerun.batchesCreated())
                .as("the same period is re-runnable after a failure - that is what the partial unique index is for")
                .isEqualTo(1);
        assertThat(rerun.totalNet()).isEqualByComparingTo("950.00");
    }

    /**
     * The preview an operator reads before pressing anything. It has to list the vendors it
     * will SKIP as well as the ones it will pay, and say why - "where is this vendor" is the
     * question the screen exists to answer, and a silently shorter list answers it with
     * silence.
     */
    @Test
    void thePreviewListsSkippedVendorsAndSaysWhy() {
        UUID payable = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(payable);
        UUID owing = plantDeliveredOrder(vendorB, FIVE_PERCENT, new BigDecimal("800.00"));
        accrue(owing);
        reverse(owing);

        PayoutRunPreview preview = body(restTemplate.exchange(
                SETTLEMENT + "/runs/preview",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken())),
                PayoutRunPreview.class));

        assertThat(preview.cutoff()).isBefore(OffsetDateTime.now());
        assertThat(preview.nextCutoff()).isAfter(OffsetDateTime.now());

        EligibleVendorPayout mine = rowFor(preview, vendorA.clientId());
        assertThat(mine.eligible()).isTrue();
        assertThat(mine.netAmount()).isEqualByComparingTo("950.00");
        assertThat(mine.ineligibleReason()).isNull();

        // Vendor B's reversal was posted TODAY, so it sits after the cutoff and the accrual
        // before it does not: their eligible set is the sale alone. The point of the
        // assertion is that they are LISTED, with a stated outcome, rather than dropped.
        EligibleVendorPayout theirs = rowFor(preview, vendorB.clientId());
        assertThat(theirs.sellerName()).isEqualTo("Settle Vendor B");
        assertThat(theirs.lineCount()).isPositive();

        // And after a run, the same vendor is listed as already run rather than as earning
        // nothing - two very different things for somebody deciding whether to click again.
        run(vendorA);
        EligibleVendorPayout afterRun = rowFor(
                body(restTemplate.exchange(
                        SETTLEMENT + "/runs/preview",
                        HttpMethod.GET,
                        new HttpEntity<>(authHeaders(superAdminToken())),
                        PayoutRunPreview.class)),
                vendorA.clientId());
        assertThat(afterRun.eligible()).isFalse();
        assertThat(afterRun.ineligibleReason()).contains("already exists for this period");
    }

    private static EligibleVendorPayout rowFor(PayoutRunPreview preview, UUID sellerId) {
        return preview.vendors().stream()
                .filter(row -> row.sellerClientId().equals(sellerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no preview row for " + sellerId));
    }

    /** The cutoff comes from the calendar, not from the click - which is what makes an operator-run reproducible. */
    @Test
    void theCutoffIsAFortnightBoundaryInLagosAndAlwaysInThePast() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cutoff = PayoutCadence.cutoffFor(now);

        assertThat(cutoff).isBefore(now);
        assertThat(PayoutCadence.nextCutoffAfter(now)).isAfter(now);
        assertThat(java.time.Duration.between(cutoff, PayoutCadence.nextCutoffAfter(now))
                        .toDays())
                .isEqualTo(PayoutCadence.PERIOD_DAYS);

        java.time.ZonedDateTime local = cutoff.atZoneSameInstant(PayoutCadence.ZONE);
        assertThat(local.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(local.getDayOfWeek())
                .as("the anchor is a Monday, so every fortnight boundary is one")
                .isEqualTo(java.time.DayOfWeek.MONDAY);
    }

    // ---------------------------------------------------------------------------------
    // (g) The statement
    // ---------------------------------------------------------------------------------

    /**
     * The reconciliation guarantee, which is the whole reason a statement is worth
     * printing: opening plus every movement equals closing, and the running balance on
     * the last line agrees with it.
     */
    @Test
    void theStatementReconciles() {
        // Dated into the last few days rather than into 2019, uniquely in this class:
        // a reversal is stamped when the OPERATOR acts, which is now, so a window that
        // holds both the sale and its reversal has to reach up to the present. The
        // two-year maximum every dated surface in this application enforces makes
        // "2019 to now" a 400, which is the right refusal and the wrong test.
        OffsetDateTime soldAt = OffsetDateTime.now().minusDays(2);
        UUID sold = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrueAt(sold, soldAt);
        UUID returned = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("400.00"));
        accrueAt(returned, soldAt);
        reverse(returned);

        VendorStatementResponse statement = body(restTemplate.exchange(
                STATEMENT + "?from=" + soldAt.minusDays(1).toInstant() + "&to="
                        + OffsetDateTime.now().plusDays(1).toInstant(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(vendorA.login())),
                VendorStatementResponse.class));

        BigDecimal reconstructed = statement.openingBalance()
                .add(statement.movements().salesProceeds())
                .add(statement.movements().commission())
                .add(statement.movements().reversals())
                .add(statement.movements().payouts());

        assertThat(reconstructed)
                .as("opening + sales + commission + reversals + payouts = closing, exactly")
                .isEqualByComparingTo(statement.closingBalance());
        assertThat(statement.movements().netMovement())
                .isEqualByComparingTo(statement.closingBalance().subtract(statement.openingBalance()));
        assertThat(statement.lines().getLast().runningBalance())
                .as("the last running balance is the closing balance - so a dispute is findable at a row")
                .isEqualByComparingTo(statement.closingBalance());

        // And the figures themselves, so a reconciliation that balanced at the wrong
        // numbers is still caught: 2,501.00 + 400.00 sold, 125.06 + 20.00 charged,
        // and the 400.00 order returned with its 20.00 fee given back.
        assertThat(statement.movements().salesProceeds()).isEqualByComparingTo("2901.00");
        assertThat(statement.movements().commission()).isEqualByComparingTo("-145.06");
        assertThat(statement.movements().reversals()).isEqualByComparingTo("-380.00");
        assertThat(statement.movements().payouts()).isEqualByComparingTo("0.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("2375.94");
    }

    /**
     * The commercial-incident test. Vendor B's money is three times vendor A's and
     * sits in the same window, so an unscoped query would be quotably wrong.
     */
    @Test
    void aVendorsStatementContainsOnlyTheirOwnMoney() {
        UUID mine = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        UUID theirs = plantDeliveredOrder(vendorB, FIVE_PERCENT, new BigDecimal("3000.00"));
        accrue(mine);
        accrue(theirs);

        VendorStatementResponse a = statementAs(vendorA);
        assertThat(a.sellerClientId()).isEqualTo(vendorA.clientId());
        assertThat(a.closingBalance()).isEqualByComparingTo("950.00");
        assertThat(a.lines()).extracting(VendorStatementLine::orderId).containsOnly(mine);
        assertThat(a.lines()).extracting(VendorStatementLine::orderId).doesNotContain(theirs);

        // And the other direction, so a query that pinned the WRONG seller is caught.
        VendorStatementResponse b = statementAs(vendorB);
        assertThat(b.closingBalance()).isEqualByComparingTo("2850.00");
        assertThat(b.lines()).extracting(VendorStatementLine::orderId).containsOnly(theirs);
    }

    /** ProcurePal is a seller and reaches its own statement - empty, and explained, rather than a 403. */
    @Test
    void thePlatformOwnerGetsAnEmptyStatementRatherThanARefusal() {
        VendorStatementResponse statement = body(restTemplate.exchange(
                STATEMENT + WINDOW,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(loginAsPlatformOwner())),
                VendorStatementResponse.class));

        assertThat(statement.ledgerBearing()).isFalse();
        assertThat(statement.closingBalance()).isEqualByComparingTo("0.00");
        assertThat(statement.lines()).isEmpty();
    }

    /** A buying company sells nothing, so it has no statement - guard, not permission. */
    @Test
    void aBuyingCompanyIsRefusedOnTheStatementSurface() {
        TenantLoginResponse owner = signup("Buys Only Settle Ltd");
        assertThat(owner.user().permissions()).contains("VIEW_MARKETPLACE_ANALYTICS");

        assertThat(restTemplate
                        .exchange(STATEMENT, HttpMethod.GET, new HttpEntity<>(authHeaders(owner)), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The export is CSV, and it carries the same numbers as the JSON rather than a second rendering of them. */
    @Test
    void theCsvExportCarriesTheSameFiguresAsTheStatement() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrue(orderId);

        ResponseEntity<String> csv = restTemplate.exchange(
                STATEMENT + "/export" + WINDOW,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(vendorA.login())),
                String.class);

        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getFirst("Content-Disposition")).contains("attachment").contains(".csv");
        assertThat(csv.getBody()).contains("SALE_PROCEEDS").contains("COMMISSION");
        assertThat(csv.getBody()).contains("-62.53").contains("1250.50");
        assertThat(csv.getBody()).contains("Closing balance,2375.94");
    }

    // ---------------------------------------------------------------------------------
    // (h) Who may not reach the money surfaces
    // ---------------------------------------------------------------------------------

    /**
     * Neither a buying company nor a vendor reaches the operator's settlement desk. A
     * vendor is the interesting one: they are the SUBJECT of these routes, and being
     * the subject is not standing to run them.
     */
    @Test
    void neitherABuyingCompanyNorAVendorReachesTheSuperAdminPayoutSurfaces() {
        TenantLoginResponse company = signup("No Payouts Here Ltd");

        for (String route : List.of("/runs/preview", "/batches")) {
            assertThat(status(SETTLEMENT + route, HttpMethod.GET, company))
                    .as("company GET %s", route)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(status(SETTLEMENT + route, HttpMethod.GET, vendorA.login()))
                    .as("vendor GET %s", route)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        assertThat(status(SETTLEMENT + "/runs", HttpMethod.POST, vendorA.login()))
                .as("and emphatically not the write side")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(SETTLEMENT + "/escrow/release", HttpMethod.POST, vendorA.login()))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A vendor cannot ask for another vendor's statement, because the route takes no seller id at all. */
    @Test
    void thereIsNoVendorRouteThatTakesASellerId() {
        UUID theirs = plantDeliveredOrder(vendorB, FIVE_PERCENT, new BigDecimal("3000.00"));
        accrue(theirs);

        // The obvious attempt: smuggle a seller id onto the vendor's own route.
        VendorStatementResponse smuggled = body(restTemplate.exchange(
                STATEMENT + WINDOW + "&sellerId=" + vendorB.clientId() + "&sellerClientId=" + vendorB.clientId(),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(vendorA.login())),
                VendorStatementResponse.class));

        assertThat(smuggled.sellerClientId())
                .as("the seller comes from the guard, never from the query string")
                .isEqualTo(vendorA.clientId());
        assertThat(smuggled.closingBalance()).isEqualByComparingTo("0.00");

        // And the operator's route, which DOES take one, is not reachable by a vendor.
        assertThat(status(SETTLEMENT + "/vendors/" + vendorB.clientId() + "/statement", HttpMethod.GET, vendorA.login()))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The operator's view of a vendor's statement, which must be the SAME record the vendor
     * sees - a payment dispute answered from a second, operator-only rendering is a dispute
     * about which screen is right.
     *
     * <p>This test exists because the obvious implementation is broken in a way nothing else
     * catches: sharing the vendor path's {@code VendorGuard.readOwnSales} escape hatch makes
     * every operator request a 403, because that hatch asserts the CALLER's tenant may sell
     * and a super admin has no tenant at all. The failure reads as "your account does not
     * sell", which is a confusing thing for a super admin to be told.
     */
    @Test
    void anOperatorSeesTheSameStatementTheVendorSees() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, AWKWARD_LINE, AWKWARD_LINE);
        accrue(orderId);

        VendorStatementResponse asVendor = statementAs(vendorA);
        VendorStatementResponse asOperator = body(restTemplate.exchange(
                SETTLEMENT + "/vendors/" + vendorA.clientId() + "/statement" + WINDOW,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken())),
                VendorStatementResponse.class));

        assertThat(asOperator.sellerClientId()).isEqualTo(vendorA.clientId());
        assertThat(asOperator.closingBalance()).isEqualByComparingTo(asVendor.closingBalance());
        assertThat(asOperator.movements().commission())
                .isEqualByComparingTo(asVendor.movements().commission());
        assertThat(asOperator.lines()).hasSameSizeAs(asVendor.lines());
        // The order context resolves for the operator too - it is served by a different
        // scoping path, and a null order number here would mean that path is not joining.
        assertThat(asOperator.lines()).allSatisfy(line -> assertThat(line.orderNumber()).isNotBlank());
        assertThat(asOperator.escrow().heldBalance()).isEqualByComparingTo(asVendor.escrow().heldBalance());
    }

    // ---------------------------------------------------------------------------------
    // (i) Append-only
    // ---------------------------------------------------------------------------------

    /**
     * The rule the whole module rests on, tested where it is enforced: below every
     * service, every guard and every ORM setting - which is the shape of a data fix,
     * a migration, or a future admin screen.
     */
    @Test
    void theDatabaseRefusesToUpdateOrDeleteALedgerRow() {
        UUID orderId = plantDeliveredOrder(vendorA, FIVE_PERCENT, new BigDecimal("1000.00"));
        accrue(orderId);
        UUID entryId = jdbc.queryForObject(
                "SELECT id FROM vendor_ledger_entries WHERE order_id = ? LIMIT 1", UUID.class, orderId);

        assertThatThrownBy(() ->
                        jdbc.update("UPDATE vendor_ledger_entries SET amount = 999999.00 WHERE id = ?", entryId))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM vendor_ledger_entries WHERE id = ?", entryId))
                .hasMessageContaining("append-only");

        assertThat(vendorLedgerService.balanceFor(vendorA.clientId())).isEqualByComparingTo("950.00");
    }

    // ---------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------

    private record VendorFixture(UUID clientId, String slug, UUID productId, String sku, TenantLoginResponse login) {
    }

    /**
     * A vendor straight through the repositories rather than through the onboarding
     * service, matching {@code VendorWorkspaceIntegrationTest}: this class tests
     * settlement, and going through the approval flow would couple every test here to
     * another module's fixtures.
     */
    private VendorFixture createVendor(String name) {
        String slug = VENDOR_SLUG_PREFIX + UUID.randomUUID();
        Client client = clientRepository.saveAndFlush(Client.builder()
                .name(name)
                .slug(slug)
                .adminContactEmail(null)
                .clientType(ClientType.VENDOR)
                .commissionRate(FIVE_PERCENT)
                .active(true)
                .build());

        Role vendorRole = roleRepository.findByName("VENDOR").orElseThrow();
        String username = "vendor-" + UUID.randomUUID();
        TenantContext.set(client.getId());
        try {
            userRepository.saveAndFlush(User.builder()
                    .username(username)
                    .passwordHash(passwordEncoder.encode(PASSWORD))
                    .role(vendorRole)
                    .active(true)
                    .root(true)
                    .build());
        } finally {
            TenantContext.clear();
        }

        UUID productId = UUID.randomUUID();
        String sku = FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO products (id, client_id, name, sku, unit_price, quantity_on_hand, "
                        + "is_active, is_marketplace_listed, approval_status, min_order_quantity) "
                        + "VALUES (?, ?, ?, ?, ?, ?, TRUE, TRUE, 'APPROVED', 1)",
                productId,
                client.getId(),
                name + " Product",
                sku,
                new BigDecimal("1000.00"),
                5000);

        TenantLoginResponse login = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest(slug, username, PASSWORD), TenantLoginResponse.class);
        assertThat(login).isNotNull();

        return new VendorFixture(client.getId(), slug, productId, sku, login);
    }

    private VendorFixture procurePal() {
        return new VendorFixture(platformOwnerId(), "procurepal", null, null, null);
    }

    private UUID platformOwnerId() {
        return jdbc.queryForObject("SELECT id FROM clients WHERE is_platform_owner LIMIT 1", UUID.class);
    }

    /** A DELIVERED, PAID order dated in the historical window. */
    private UUID plantDeliveredOrder(VendorFixture seller, BigDecimal rate, BigDecimal... lineTotals) {
        return plantOrder(seller, OrderStatus.DELIVERED, "PAID", rate, ACCRUED_AT, lineTotals);
    }

    private UUID plantOrder(
            VendorFixture seller, OrderStatus status, String paymentStatus, BigDecimal rate, BigDecimal... lineTotals) {
        return plantOrder(seller, status, paymentStatus, rate, ACCRUED_AT, lineTotals);
    }

    /**
     * One order with N lines, dated exactly. Raw SQL for the reason the class doc
     * gives: {@code created_at} and {@code delivered_at} decide what every figure here
     * means, and JPA will not let a caller set the first.
     */
    private UUID plantOrder(
            VendorFixture seller,
            OrderStatus status,
            String paymentStatus,
            BigDecimal rate,
            OffsetDateTime at,
            BigDecimal... lineTotals) {
        UUID id = UUID.randomUUID();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (BigDecimal line : lineTotals) {
            subtotal = subtotal.add(line);
        }
        boolean placed = status != OrderStatus.PENDING_PAYMENT;
        boolean delivered = status == OrderStatus.DELIVERED || status == OrderStatus.RECEIVED;

        jdbc.update(
                "INSERT INTO orders (id, order_number, client_id, seller_client_id, checkout_group_id, "
                        + "status, payment_status, payment_method, currency, subtotal, delivery_fee, total, "
                        + "placed_at, delivered_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'MONNIFY', 'NGN', ?, ?, ?, ?, ?, ?, ?)",
                id,
                FIXTURE_PREFIX + UUID.randomUUID().toString().substring(0, 12),
                buyer,
                seller.clientId(),
                id,
                status.name(),
                paymentStatus,
                subtotal,
                DELIVERY_FEE,
                subtotal.add(DELIVERY_FEE),
                placed ? java.sql.Timestamp.from(at.toInstant()) : null,
                delivered ? java.sql.Timestamp.from(at.toInstant()) : null,
                java.sql.Timestamp.from(at.toInstant()),
                java.sql.Timestamp.from(at.toInstant()));

        for (BigDecimal line : lineTotals) {
            jdbc.update(
                    "INSERT INTO order_items (id, order_id, product_id, product_name, product_sku, "
                            + "unit_price, quantity, line_total, commission_rate) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    UUID.randomUUID(),
                    id,
                    seller.productId() == null ? platformOwnerProductId() : seller.productId(),
                    "Fixture line",
                    seller.sku() == null ? "PP-SETL-owner" : seller.sku(),
                    line,
                    line,
                    rate);
        }
        return id;
    }

    /** Any product of the platform owner's, for the one fixture that plants a ProcurePal order. */
    private UUID platformOwnerProductId() {
        return jdbc.queryForObject(
                "SELECT id FROM products WHERE client_id = ? LIMIT 1", UUID.class, platformOwnerId());
    }

    /**
     * Cleanup, in dependency order, and the one place the append-only escape hatch is
     * used anywhere in this repository.
     *
     * <p>{@code app.ledger_maintenance} is the session setting V14's trigger honours.
     * It is set and reset on ONE connection - a JdbcTemplate call takes whichever
     * connection the pool hands it, so a bare {@code SET} would very likely land on a
     * different connection from the DELETE that needs it. Nothing in the application
     * sets this, and nothing should.
     */
    private void cleanFixtures() {
        String sellers = "SELECT id FROM clients WHERE slug LIKE '" + VENDOR_SLUG_PREFIX + "%'";
        jdbc.update("DELETE FROM vendor_payout_batch_lines WHERE payout_batch_id IN "
                + "(SELECT id FROM vendor_payout_batches WHERE seller_client_id IN (" + sellers + "))");

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET app.ledger_maintenance = 'on'");
                statement.execute("DELETE FROM vendor_ledger_entries WHERE seller_client_id IN (" + sellers + ")");
                statement.execute("DELETE FROM vendor_ledger_entries WHERE order_id IN "
                        + "(SELECT id FROM orders WHERE order_number LIKE '" + FIXTURE_PREFIX + "%')");
                statement.execute("RESET app.ledger_maintenance");
            }
            return null;
        });

        jdbc.update("DELETE FROM vendor_payout_batches WHERE seller_client_id IN (" + sellers + ")");
        jdbc.update("DELETE FROM orders WHERE order_number LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM products WHERE sku LIKE ?", FIXTURE_PREFIX + "%");
        jdbc.update("DELETE FROM clients WHERE client_type = 'VENDOR' AND slug LIKE ?", VENDOR_SLUG_PREFIX + "%");
    }

    // ---------------------------------------------------------------------------------
    // Drivers
    // ---------------------------------------------------------------------------------

    /**
     * Accrual dated into the historical window, through the service rather than
     * through SQL - the arithmetic under test is exactly what this method does.
     */
    private int accrue(UUID orderId) {
        return accrueAt(orderId, ACCRUED_AT);
    }

    private int accrueAt(UUID orderId, OffsetDateTime occurredAt) {
        return inTransaction(() -> {
            Order order = orderRepository.findById(orderId).orElseThrow();
            return vendorLedgerService.accrueForConfirmedDelivery(order, occurredAt, "Delivery confirmed", null);
        });
    }

    private ResponseEntity<String> reverse(UUID orderId) {
        return restTemplate.exchange(
                SETTLEMENT + "/orders/" + orderId + "/reverse",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "Returned"), authHeaders(superAdminToken())),
                String.class);
    }

    private PayoutRunResponse run(VendorFixture seller) {
        return body(restTemplate.exchange(
                SETTLEMENT + "/runs?sellerId=" + seller.clientId(),
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(superAdminToken())),
                PayoutRunResponse.class));
    }

    private PayoutBatchSummary markPaid(UUID batchId) {
        return body(restTemplate.exchange(
                SETTLEMENT + "/batches/" + batchId + "/mark-paid",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("paymentReference", "TRF-" + UUID.randomUUID()), authHeaders(superAdminToken())),
                PayoutBatchSummary.class));
    }

    private PayoutBatchSummary runAndPay(VendorFixture seller) {
        PayoutBatchSummary batch = run(seller).batches().getFirst();
        return markPaid(batch.id());
    }

    private PayoutBatchSummary latestBatch(VendorFixture seller) {
        return vendorSettlementService.batchesForSeller(seller.clientId()).getFirst();
    }

    private PayoutBatchDetail batchDetail(UUID batchId) {
        return body(restTemplate.exchange(
                SETTLEMENT + "/batches/" + batchId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(superAdminToken())),
                PayoutBatchDetail.class));
    }

    private VendorStatementResponse statementAs(VendorFixture seller) {
        return body(restTemplate.exchange(
                STATEMENT + WINDOW,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(seller.login())),
                VendorStatementResponse.class));
    }

    private BigDecimal sumOf(UUID orderId, VendorLedgerEntryType type) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM vendor_ledger_entries WHERE order_id = ? AND entry_type = ?",
                BigDecimal.class,
                orderId,
                type.name());
    }

    /** A null order id counts every row of that kind for THIS test's vendors. */
    private int countOf(UUID orderId, VendorLedgerEntryType type) {
        if (orderId != null) {
            return jdbc.queryForObject(
                    "SELECT count(*) FROM vendor_ledger_entries WHERE order_id = ? AND entry_type = ?",
                    Integer.class,
                    orderId,
                    type.name());
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM vendor_ledger_entries WHERE entry_type = ? AND seller_client_id IN (?, ?)",
                Integer.class,
                type.name(),
                vendorA.clientId(),
                vendorB.clientId());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    // ---------------------------------------------------------------------------------
    // Auth and plumbing
    // ---------------------------------------------------------------------------------

    private HttpStatus status(String path, HttpMethod method, TenantLoginResponse as) {
        return (HttpStatus) restTemplate
                .exchange(path, method, new HttpEntity<>(Map.of(), authHeaders(as)), String.class)
                .getStatusCode();
    }

    private static <T> T body(ResponseEntity<T> response) {
        assertThat(response.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TenantLoginResponse loginAsPlatformOwner() {
        TenantLoginResponse response = restTemplate.postForObject(
                "/api/auth/login", new LoginRequest("procurepal", "admin", "Demo1234!"), TenantLoginResponse.class);
        assertThat(response)
                .as("the procurepal demo tenant must be seeded - see db/seed/V9001__seed_procurepal_marketplace.sql")
                .isNotNull();
        return response;
    }

    private String superAdminToken() {
        String uniqueUsername = "superadmin-" + UUID.randomUUID();
        superAdminRepository.save(SuperAdmin.builder()
                .username(uniqueUsername)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .build());
        SuperAdminLoginResponse response = restTemplate.postForObject(
                "/api/superadmin/auth/login",
                new SuperAdminLoginRequest(uniqueUsername, PASSWORD),
                SuperAdminLoginResponse.class);
        return response.tokens().accessToken();
    }

    private UUID signupClientId(String name) {
        TenantLoginResponse response = signup(name);
        return jdbc.queryForObject(
                "SELECT id FROM clients WHERE slug = ?", UUID.class, response.user().clientIdentifier());
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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
