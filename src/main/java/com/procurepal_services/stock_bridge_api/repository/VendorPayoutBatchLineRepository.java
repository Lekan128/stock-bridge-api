package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorPayoutBatchLine;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Membership rows, one per ledger entry a batch claimed. See
 * {@link VendorPayoutBatchLine} for why this is a table rather than a column on
 * the ledger.
 *
 * <p>{@link #deleteAllByPayoutBatchId} is the ONLY delete anywhere in the
 * settlement module, and it is legitimate for a specific reason: marking a batch
 * FAILED releases its claim so the lines go into the next run, and no money moved,
 * so no ledger row is touched. Deleting a claim is not deleting a financial
 * record.
 */
public interface VendorPayoutBatchLineRepository extends JpaRepository<VendorPayoutBatchLine, UUID> {

    List<VendorPayoutBatchLine> findAllByPayoutBatchId(UUID payoutBatchId);

    long countByPayoutBatchId(UUID payoutBatchId);

    void deleteAllByPayoutBatchId(UUID payoutBatchId);
}
