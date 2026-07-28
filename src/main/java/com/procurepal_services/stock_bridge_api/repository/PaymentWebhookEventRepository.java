package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.PaymentWebhookEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Not tenant-scoped: the webhook endpoint is public and has no tenant context. */
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {

    List<PaymentWebhookEvent> findAllByPaymentReferenceOrderByReceivedAtDesc(String paymentReference);

    List<PaymentWebhookEvent> findAllByTransactionReferenceOrderByReceivedAtDesc(String transactionReference);

    /**
     * Replay detection. Note this is a diagnostic aid, not the idempotency
     * mechanism - a replayed callback is made harmless by the guarded transaction
     * in PaymentRepository.findByPaymentReferenceForUpdate, not by refusing to
     * accept it here. Refusing early would also mean losing the record of the
     * replay, which is the thing you want when a provider misbehaves.
     */
    boolean existsByTransactionReferenceAndProcessedTrue(String transactionReference);

    /** Operational view, newest first: "show me what Monnify has been sending us". */
    Page<PaymentWebhookEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    long countBySignatureValidFalse();
}
