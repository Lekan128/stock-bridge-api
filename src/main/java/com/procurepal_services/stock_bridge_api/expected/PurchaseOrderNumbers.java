package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.repository.ExpectedDeliveryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands out PO-0001, PO-0002... per company (PURCHASE_ORDER_PLAN.md section 4.2).
 *
 * <p>Allocated on the server inside the creating transaction, never by the client, so a draft made
 * offline cannot collide with one made at another desk. Two orders made at the same moment by one
 * company are serialised by a transaction-scoped advisory lock keyed on the company, the same
 * technique {@code OrderNumberAllocator} uses for marketplace orders; the unique index on
 * {@code (client_id, po_sequence)} is the net underneath it.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderNumbers {

    private static final String PREFIX = "PO-";

    private final ExpectedDeliveryRepository expectedDeliveryRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** The next sequence for this company. Must run inside the transaction that saves the order. */
    public int nextSequence(UUID clientId) {
        // Wrapped in a COUNT so the statement has a typed result column: the lock call returns void.
        entityManager
                .createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 73))) AS l")
                .setParameter("key", "po:" + clientId)
                .getSingleResult();
        Integer highest = expectedDeliveryRepository.highestPoSequence(clientId);
        return (highest == null ? 0 : highest) + 1;
    }

    /** "PO-0007". Four digits to start with; past 9999 it simply gets longer. */
    public static String format(int sequence) {
        return PREFIX + String.format("%04d", sequence);
    }
}
