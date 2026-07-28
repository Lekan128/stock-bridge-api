package com.procurepal_services.stock_bridge_api.order;

import com.procurepal_services.stock_bridge_api.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Allocates the human-readable order number, {@code PP-YYYY-NNNNNN}.
 *
 * <h2>Why an advisory lock rather than count()+1 alone</h2>
 * Two checkouts committing at the same instant would both count the same N and both
 * try to insert {@code PP-2026-000042}; {@code uq_orders_order_number} would reject
 * one of them and a buyer would see a 500 for no reason they could act on. A
 * transaction-scoped Postgres advisory lock serialises just the allocation - it is
 * held for microseconds and released automatically at commit or rollback, so no
 * cleanup path can leak it. Under READ COMMITTED, the count that runs after the lock
 * is granted always sees the previous holder's committed row.
 *
 * <h2>Why not a sequence</h2>
 * A sequence would be the obvious answer, but sequences are schema, and migrations
 * belong to exactly one module. This achieves the same guarantee with no DDL. The
 * unique constraint remains the real backstop either way.
 *
 * Gaps are tolerated (a rolled-back checkout burns a number) - the number is an
 * identifier a human quotes on the phone, not an audited count of anything.
 */
@Component
@RequiredArgsConstructor
public class OrderNumberAllocator {

    /**
     * An arbitrary but FIXED key. Postgres advisory locks share one namespace across
     * the database, so this constant must never be reused by another feature.
     */
    private static final long ORDER_NUMBER_LOCK_KEY = 728_401_553L;

    private static final String PREFIX = "PP-";

    private final OrderRepository orderRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Must be called inside the transaction that inserts the order - the lock ends with it. */
    public String allocate() {
        // Wrapped in a COUNT so the statement has a typed result column:
        // pg_advisory_xact_lock returns void, which the JDBC/Hibernate result
        // mapping has no type for.
        entityManager
                .createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS advisory_lock")
                .setParameter("key", ORDER_NUMBER_LOCK_KEY)
                .getSingleResult();

        String yearPrefix = PREFIX + OffsetDateTime.now(ZoneOffset.UTC).getYear() + "-";
        long next = orderRepository.countByOrderNumberStartingWith(yearPrefix) + 1;

        // Defensive: a count is only equal to "the highest number used" if nothing was
        // ever deleted or inserted out of band (test fixtures do exactly that). Walking
        // forward costs one indexed lookup per collision and cannot loop forever.
        String candidate = format(yearPrefix, next);
        while (orderRepository.existsByOrderNumber(candidate)) {
            candidate = format(yearPrefix, ++next);
        }
        return candidate;
    }

    private static String format(String yearPrefix, long sequence) {
        return yearPrefix + String.format("%06d", sequence);
    }
}
