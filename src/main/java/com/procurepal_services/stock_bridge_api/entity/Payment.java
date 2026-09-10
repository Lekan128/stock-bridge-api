package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One payment ATTEMPT against the provider - not one row per order. A buyer who
 * abandons Monnify's hosted checkout and tries again gets a second row; keeping
 * only the latest would destroy the evidence a payment dispute needs.
 *
 * Explicitly NOT a TenantAwareEntity. The Monnify webhook arrives unauthenticated
 * on a public endpoint with no TenantContext at all, so a tenant filter here
 * would either block the write outright or mis-scope it. Buyer-facing reads must
 * therefore be scoped through {@code order.clientId} in service code - there is no
 * filter to fall back on.
 *
 * <h2>ONE PAYMENT CAN COVER SEVERAL ORDERS</h2>
 * A basket holding several sellers' goods splits into one order per seller (V12),
 * but the buyer pays ONCE and Monnify mints one transaction. The decision, taken
 * over the alternative of one Monnify checkout per seller:
 *
 * <p><b>One payment, fanned out across the checkout group.</b> {@link #order} is
 * the ANCHOR - the first order of the group - and {@link #amount} is the sum
 * across the whole group. Settlement applies to every order in the anchor's
 * {@code checkout_group_id}, inside one transaction. {@code order_id} therefore
 * means "the order this payment hangs off", not "the only order it pays for", and
 * that is the one piece of dishonesty in this schema; it is tolerated because the
 * alternative was worse in every direction.
 *
 * <p><b>Why not a payment per order.</b> It would mean sending a buyer to Monnify's
 * hosted checkout three times for one basket, entering their card three times, with
 * three chances to abandon. Partial payment then becomes an ordinary outcome rather
 * than an anomaly: one seller's goods are paid for and dispatched while another's sit
 * unpaid, and the buyer's "order" is half real. It also triples the fixed per
 * transaction fee for no benefit to anyone.
 *
 * <p><b>Why not a payments row per order sharing one transaction reference.</b> That
 * splits the idempotency guard across N rows, and the guard is the single most
 * load-bearing property here: {@code findByPaymentReferenceForUpdate} takes ONE row
 * lock and re-checks ONE final status. With N rows a replayed webhook could take them
 * in a different order and interleave, and "all N applied or none did" would have to
 * be re-established by hand. Keeping exactly one payments row per attempt keeps
 * exactly one thing to lock and one thing to check.
 *
 * <h2>Failure modes of this choice, stated plainly</h2>
 * <ul>
 *   <li><b>Anchor cancelled while the group is unpaid.</b> The payments row still
 *       points at it. Settlement resolves the group from the anchor's
 *       {@code checkout_group_id}, which survives cancellation, so the remaining
 *       orders still settle - but the anchor itself is terminal and is skipped. A
 *       buyer who cancels one order of an unpaid group and then pays will have paid
 *       the ORIGINAL group total, which is now more than they owe. That is an
 *       overpayment, which the amount check treats as payment and flags for manual
 *       reconciliation. Cancelling a member of a group that has a live checkout open
 *       is the sharp edge here; it is not currently prevented.</li>
 *   <li><b>Partial fulfilment after settlement.</b> Not a payment problem - each
 *       order fulfils independently by design - but a refund of ONE order in a group
 *       has no per-order payment row to reverse against. The refund path is a later
 *       module's, and it must reverse against the order, not against this row.</li>
 *   <li><b>A group whose members are edited between init and settle.</b> The amount is
 *       frozen on this row at init; the group total is recomputed at settlement. If
 *       they disagree the amount check fires, which is the correct and safe
 *       direction.</li>
 * </ul>
 *
 * paymentReference is OURS (sent to Monnify as paymentReference) and unique, so a
 * retry can never collide with an earlier attempt. transactionReference is
 * Monnify's and stays null until they tell us.
 *
 * amountPaid is separate from amount so an underpayment is visible rather than
 * rounded away: an underpaid attempt is FAILED with the payload retained, never
 * PAID.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The ANCHOR order. See the class javadoc: a payment settles this order's whole
     * checkout group, not this order alone. Read {@code order.getCheckoutGroupId()},
     * never {@code order.getId()}, when asking what a payment covers.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(name = "payment_reference", nullable = false, updatable = false, length = 100)
    private String paymentReference;

    @Column(name = "transaction_reference", length = 100)
    private String transactionReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentProviderStatus status;

    /** What was asked for: the SUM across every order in the anchor's checkout group. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "amount_paid", precision = 14, scale = 2)
    private BigDecimal amountPaid;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Provider-controlled channel string (CARD, ACCOUNT_TRANSFER, USSD, ...), not one of our enums. */
    @Column(name = "payment_method_used", length = 50)
    private String paymentMethodUsed;

    @Column(name = "checkout_url", columnDefinition = "TEXT")
    private String checkoutUrl;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    /**
     * Raw JSON of the last verify/webhook payload, kept whole for dispute
     * forensics. Mapped as a String rather than a parsed structure deliberately:
     * we must be able to store exactly what the provider sent, including fields
     * we do not model and would otherwise silently drop.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_payload")
    private String providerPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "verified_via", length = 20)
    private PaymentVerificationSource verifiedVia;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
