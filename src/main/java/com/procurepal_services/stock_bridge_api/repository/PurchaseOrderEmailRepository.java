package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmail;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseOrderEmailRepository extends TenantScopedRepository<PurchaseOrderEmail, UUID> {

    /** The latest attempts first - the order page shows the top one. */
    List<PurchaseOrderEmail> findAllByExpectedDeliveryIdInOrderByCreatedAtDesc(List<UUID> expectedDeliveryIds);

    Optional<PurchaseOrderEmail> findFirstByExpectedDeliveryIdOrderByCreatedAtDesc(UUID expectedDeliveryId);

    /**
     * How many of this company's emails the provider accepted since {@code since} - the daily cap.
     * Counts what was handed over, not attempts that never left.
     */
    @Query("select count(e) from PurchaseOrderEmail e where e.clientId = :clientId "
            + "and e.createdAt >= :since and e.status <> com.procurepal_services.stock_bridge_api.entity.PurchaseOrderEmailStatus.FAILED")
    long countHandedOverSince(UUID clientId, OffsetDateTime since);

    /**
     * How many purchase-order emails ANY company has sent to this address since {@code since}. Native
     * on purpose: the tenant filter would otherwise hide every other company's rows, and the point of
     * this cap is that one stranger's inbox must not become a dumping ground across companies.
     */
    @Query(value = "select count(*) from purchase_order_emails where to_address = :address "
            + "and created_at >= :since and status <> 'FAILED'", nativeQuery = true)
    long countToAddressSince(String address, OffsetDateTime since);

    /**
     * A bounce webhook names the provider's id and nothing about who we are, so these two are native
     * and not tenant-filtered. Returns how many rows it touched (0 when the id is not ours - Resend
     * also sends every other kind of mail from the same account).
     */
    @Modifying
    @Query(value = "update purchase_order_emails set status = :status, problem = case :status "
            + "when 'BOUNCED' then 'It didn''t reach ' || coalesce(to_address, 'that address') "
            + "|| '. Check the address and fix it.' "
            + "when 'COMPLAINED' then 'The person who got it marked it as unwanted.' "
            + "else 'We couldn''t send it. Try again in a moment.' end "
            + "where provider_message_id = :providerMessageId", nativeQuery = true)
    int markByProviderMessageId(String providerMessageId, String status);

    /** A bounce also takes "email the order to this supplier" back to off (plan 4.3a). */
    @Modifying
    @Query(value = "update company_vendors set email_orders = false where id in ("
            + "select e.vendor_id from expected_deliveries e join purchase_order_emails p "
            + "on p.expected_delivery_id = e.id where p.provider_message_id = :providerMessageId)",
            nativeQuery = true)
    int switchOffEmailingForSupplierOf(String providerMessageId);

    /**
     * An email that bounced was the way the supplier "had" the order, so they don't have it after all:
     * the order goes back to To send (V76). Left alone when it reached them another way since.
     */
    @Modifying
    @Query(value = "update expected_deliveries set sent_revision = null, sent_at = null, sent_via = null "
            + "where sent_via = 'EMAIL' and id in (select p.expected_delivery_id from purchase_order_emails p "
            + "where p.provider_message_id = :providerMessageId)",
            nativeQuery = true)
    int unsendOrderOf(String providerMessageId);
}
