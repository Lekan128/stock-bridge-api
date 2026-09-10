package com.procurepal_services.stock_bridge_api.settlement;

import com.procurepal_services.stock_bridge_api.repository.VendorPayoutBatchRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Allocates the human-readable payout batch number, {@code PB-YYYY-NNNNNN}.
 *
 * <p>A deliberate copy of {@code OrderNumberAllocator}'s shape, down to the
 * advisory lock and the collision walk, because the problem is identical and a
 * second, cleverer solution to it would only be a second thing to reason about.
 * Read that class for the full argument; the short version is that
 * {@code count() + 1} alone lets two concurrent runs pick the same number and
 * collide on the unique index for no reason a human could act on, while a
 * transaction-scoped Postgres advisory lock serialises just the allocation and is
 * released automatically at commit or rollback.
 *
 * <p>A run creates one batch per vendor, so a single run can allocate a dozen of
 * these inside one transaction. The lock is taken once per allocation and held for
 * microseconds; the alternative - one number for the whole run, suffixed per
 * vendor - was rejected because it makes the number describe the run rather than
 * the payment, and the payment is what an operator reconciles against a bank
 * statement.
 */
@Component
@RequiredArgsConstructor
public class PayoutBatchNumberAllocator {

    /**
     * An arbitrary but FIXED key, and one that must never collide with
     * {@code OrderNumberAllocator}'s: Postgres advisory locks share a single
     * namespace across the whole database, so two features using the same key would
     * serialise against each other for no reason and deadlock is a short step away.
     */
    private static final long PAYOUT_BATCH_NUMBER_LOCK_KEY = 728_401_554L;

    private static final String PREFIX = "PB-";

    private final VendorPayoutBatchRepository payoutBatchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Must be called inside the transaction that inserts the batch - the lock ends with it. */
    public String allocate() {
        // Wrapped in a COUNT so the statement has a typed result column:
        // pg_advisory_xact_lock returns void, which the JDBC/Hibernate result mapping
        // has no type for.
        entityManager
                .createNativeQuery("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS advisory_lock")
                .setParameter("key", PAYOUT_BATCH_NUMBER_LOCK_KEY)
                .getSingleResult();

        // The calendar year of the RUN, in WAT, so a batch number and the payout
        // period it settles never straddle a new year differently. Cheap consistency
        // for something people read aloud.
        String yearPrefix = PREFIX + OffsetDateTime.now().atZoneSameInstant(PayoutCadence.ZONE).getYear() + "-";
        long next = payoutBatchRepository.countByBatchNumberStartingWith(yearPrefix) + 1;

        String candidate = format(yearPrefix, next);
        while (payoutBatchRepository.existsByBatchNumber(candidate)) {
            candidate = format(yearPrefix, ++next);
        }
        return candidate;
    }

    private static String format(String yearPrefix, long sequence) {
        return yearPrefix + String.format("%06d", sequence);
    }
}
