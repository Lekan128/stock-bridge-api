package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmailStatus;
import com.procurepal_services.stock_bridge_api.repository.PurchaseOrderEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the email provider tells us afterwards about a purchase order we emailed: it bounced, it
 * was marked unwanted, or it could not be sent. Called by the Resend webhook, which knows only the
 * provider's message id - not which company or which order - so this finds the row by that id and
 * does nothing when the id is not one of ours (Resend carries every other kind of mail too).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderEmailOutcomes {

    private final PurchaseOrderEmailRepository repository;

    /** A permanent bounce: the order now says it didn't reach, and emailing that supplier starts off again. */
    @Transactional
    public void bounced(String providerMessageId) {
        apply(providerMessageId, PurchaseOrderEmailStatus.BOUNCED, true);
    }

    @Transactional
    public void complained(String providerMessageId) {
        apply(providerMessageId, PurchaseOrderEmailStatus.COMPLAINED, true);
    }

    @Transactional
    public void failed(String providerMessageId) {
        apply(providerMessageId, PurchaseOrderEmailStatus.FAILED, false);
    }

    private void apply(String providerMessageId, PurchaseOrderEmailStatus status, boolean switchOff) {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            return;
        }
        int touched = repository.markByProviderMessageId(providerMessageId, status.name());
        if (touched > 0) {
            log.info("A purchase order email was reported {} by the provider.", status);
            if (switchOff) {
                repository.switchOffEmailingForSupplierOf(providerMessageId);
                repository.unsendOrderOf(providerMessageId);
            }
        }
    }
}
