package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatch;
import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Plain JpaRepository, and not tenant-scoped, for the same reason
 * {@code VendorLedgerEntryRepository} is not: a batch belongs to a seller, is
 * written by a super admin who belongs to no tenant, and is read by both. The
 * seller id is always an explicit predicate.
 *
 * <p>The super-admin surface reads across every seller on purpose - that is a
 * platform-operations question and a super admin principal has no tenant filter to
 * lift in the first place, exactly as {@code PlatformRevenueService} documents.
 * The vendor surface must always go through
 * {@link #findAllBySellerClientIdOrderByPeriodEndDesc}.
 */
public interface VendorPayoutBatchRepository extends JpaRepository<VendorPayoutBatch, UUID> {

    boolean existsByBatchNumber(String batchNumber);

    long countByBatchNumberStartingWith(String prefix);

    /** A vendor's own payout history. The seller predicate is the isolation, not a convenience. */
    Page<VendorPayoutBatch> findAllBySellerClientIdOrderByPeriodEndDesc(UUID sellerClientId, Pageable pageable);

    List<VendorPayoutBatch> findAllBySellerClientIdOrderByPeriodEndDesc(UUID sellerClientId);

    /** The operator's queue. Optional filters are applied by the caller passing nulls - see the service. */
    @Query("SELECT b FROM VendorPayoutBatch b "
            + "WHERE (:sellerId IS NULL OR b.sellerClientId = :sellerId) "
            + "AND (:status IS NULL OR b.status = :status) "
            + "ORDER BY b.runAt DESC, b.batchNumber DESC")
    Page<VendorPayoutBatch> findForOperator(
            @Param("sellerId") UUID sellerId,
            @Param("status") VendorPayoutBatchStatus status,
            Pageable pageable);

    /**
     * The pre-check behind {@code uq_vendor_payout_batches_seller_period}. Present
     * so a repeated run reports "already run for this period" instead of surfacing a
     * constraint violation; the index remains the real guard for the race, which is
     * the house posture everywhere else in this codebase.
     */
    Optional<VendorPayoutBatch> findFirstBySellerClientIdAndPeriodEndAndStatusNot(
            UUID sellerClientId, OffsetDateTime periodEnd, VendorPayoutBatchStatus excludedStatus);

    /**
     * Every live batch already sitting at one cutoff - i.e. the vendors a run made now would
     * refuse to pay again.
     *
     * <p>The preview needs this because "already run an hour ago" and "earned nothing this
     * fortnight" are indistinguishable otherwise: a vendor whose entries have all been
     * claimed has no settleable rows left, so they simply vanish from a preview driven off
     * the ledger alone, and an operator staring at a shorter list has no way to tell which
     * of the two happened.
     */
    List<VendorPayoutBatch> findAllByPeriodEndAndStatusNot(
            OffsetDateTime periodEnd, VendorPayoutBatchStatus excludedStatus);
}
