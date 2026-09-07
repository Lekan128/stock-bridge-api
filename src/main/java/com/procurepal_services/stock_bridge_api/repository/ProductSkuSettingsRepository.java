package com.procurepal_services.stock_bridge_api.repository;

import com.procurepal_services.stock_bridge_api.entity.ProductSkuSettings;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link ProductSkuSettings} is not {@code TenantAwareEntity} (see its class javadoc), so this is
 * a plain {@link JpaRepository} rather than a {@code TenantScopedRepository} - every finder here
 * takes {@code clientId} explicitly instead of relying on the Hibernate tenant filter.
 */
public interface ProductSkuSettingsRepository extends JpaRepository<ProductSkuSettings, UUID> {

    /** Unlocked read - settings display, and {@code SkuGenerationService.preview}'s non-committing peek. */
    Optional<ProductSkuSettings> findByClientId(UUID clientId);

    /**
     * Locked read for a real reservation. Held for the duration of {@code
     * SkuGenerationService}'s collision-retry loop so no two callers can ever be handed the same
     * rendered SKU, the same way {@code ImportSessionRepository.findByIdAndClientIdForUpdate}
     * serializes concurrent commit attempts on one import session.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ProductSkuSettings s WHERE s.clientId = :clientId")
    Optional<ProductSkuSettings> findByClientIdForUpdate(@Param("clientId") UUID clientId);
}
