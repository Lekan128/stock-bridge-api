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
