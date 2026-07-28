package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.MarketplaceSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Single-row table (see MarketplaceSettings). The row is seeded by
 * V6__marketplace.sql, so callers can treat an empty Optional as a broken
 * database rather than a state to design around - but they should still fail
 * loudly rather than invent defaults, because guessing a delivery fee is worse
 * than an error.
 */
public interface MarketplaceSettingsRepository extends JpaRepository<MarketplaceSettings, UUID> {

    Optional<MarketplaceSettings> findBySingletonTrue();
}
