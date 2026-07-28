package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.Payment;
import com.procurepal_services.stock_bridge_api.entity.PaymentProviderStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Plain JpaRepository - payments has no client_id, because the Monnify webhook
 * arrives unauthenticated with no TenantContext (see the Payment entity). Any
 * buyer-facing read MUST additionally assert {@code payment.getOrder().getClientId()}
 * matches the caller's tenant; there is no filter doing it for you here.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByTransactionReference(String transactionReference);

    List<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<Payment> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(
            UUID orderId, PaymentProviderStatus status);

    boolean existsByOrderIdAndStatus(UUID orderId, PaymentProviderStatus status);

    /**
     * The reconciliation sweep: attempts still PENDING past their grace period get
     * re-verified against the provider, so a dropped webhook cannot silently strand
     * a paid order.
     */
    List<Payment> findAllByStatusAndCreatedAtBefore(PaymentProviderStatus status, OffsetDateTime createdBefore);

    /**
     * Row-locks the attempt for the caller's transaction. This is the linchpin of
     * payment idempotency: the webhook, the browser-return verify and the
     * reconciliation sweep can all report the same success at once, and applying a
     * payment must happen exactly once. Load through this, re-check
     * {@code status.isFinal()} inside the lock, and return early if it is - the same
     * pattern (and the same reasoning) as
     * ProductRepository.findByIdAndClientIdForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.paymentReference = :paymentReference")
    Optional<Payment> findByPaymentReferenceForUpdate(@Param("paymentReference") String paymentReference);
}
