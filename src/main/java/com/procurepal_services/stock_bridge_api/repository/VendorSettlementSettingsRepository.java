package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.VendorSettlementSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Single-row table (see {@link VendorSettlementSettings}). The row is seeded by
 * {@code V15__escrow_maturity_and_settlement_settings.sql}, so an empty Optional
 * means a broken database rather than a state to design around - exactly as
 * {@link MarketplaceSettingsRepository} says of its own row.
 *
 * <p>Where the two part company is what a caller should DO about it, and the
 * difference is deliberate. {@code MarketplaceSettings} is read on the checkout
 * path, where inventing a delivery fee would charge a buyer a number nobody
 * agreed, so its callers fail loudly. This row is read on the ACCRUAL path, which
 * runs inside a buyer confirming receipt - failing there would refuse a delivery
 * confirmation over a settings row the buyer cannot see and cannot fix.
 * {@code EscrowHoldPolicy} therefore falls back to the same default this migration
 * seeds, loudly in the log, and says why at length.
 */
public interface VendorSettlementSettingsRepository extends JpaRepository<VendorSettlementSettings, UUID> {

    Optional<VendorSettlementSettings> findBySingletonTrue();
}
