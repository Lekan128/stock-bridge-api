package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.SesNotificationEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Not tenant-scoped: the SNS endpoint is public and has no tenant context. */
public interface SesNotificationEventRepository extends JpaRepository<SesNotificationEvent, UUID> {

    /**
     * The idempotency question, asked before any state is changed: has this exact
     * SNS {@code MessageId} already been applied?
     *
     * <p>{@code AndProcessedTrue} is the whole point. A message id may legitimately
     * appear on several rows - one refused for a bad signature, one applied, one or
     * more retries recorded afterwards - and only the applied one means "do not do
     * this again". Asking {@code existsByMessageId} instead would make a single
     * probe with a guessed id permanently block the real notification that follows.
     *
     * <p>This is the check, not the guarantee. The guarantee is the partial unique
     * index {@code uq_ses_notification_events_processed_message_id}, which holds even
     * when two application instances receive the same retry at the same moment and
     * both pass this check.
     */
    boolean existsByMessageIdAndProcessedTrue(String messageId);

    /** Everything ever received under one id: the original, the retries, the rejects. */
    List<SesNotificationEvent> findAllByMessageIdOrderByReceivedAtDesc(String messageId);

    /** Operational view: "show me what SNS has been sending us". */
    Page<SesNotificationEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    /**
     * How many deliveries failed signature verification. Not a curiosity: this
     * endpoint is public and the only thing standing between it and an attacker who
     * wants to unverify every customer on the platform is the signature, so a
     * non-zero and growing value here is somebody trying.
     */
    long countBySignatureValidFalse();
}
