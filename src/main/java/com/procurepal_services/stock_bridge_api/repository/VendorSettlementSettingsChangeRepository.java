package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorSettlementSettingsChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The escrow hold's change history, newest first.
 *
 * <h2>There are no write methods beyond save, and none should be added</h2>
 * The table is append-only and a trigger enforces it, so a {@code deleteBy...} or a
 * {@code @Modifying} update here would compile, pass review and fail at runtime -
 * the same warning {@link VendorLedgerEntryRepository} carries, for the same
 * table-level reason.
 *
 * <p>Not tenant-scoped, and nothing here takes a client id: this is platform
 * policy, changed by super admins, who have no tenant at all.
 */
public interface VendorSettlementSettingsChangeRepository
        extends JpaRepository<VendorSettlementSettingsChange, UUID> {

    /**
     * The audit screen's query. Paged rather than unbounded because this table only
     * grows, and an operator reading "what changed recently" never wants page 40.
     */
    List<VendorSettlementSettingsChange> findAllByOrderByChangedAtDesc(Pageable pageable);
}
