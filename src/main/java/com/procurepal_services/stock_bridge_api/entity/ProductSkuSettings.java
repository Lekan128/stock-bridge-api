package com.procurepal_services.stock_bridge_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A tenant's automatic SKU generation configuration and running counter.
 *
 * <p>Unlike {@link MarketplaceSettings} and {@link VendorSettlementSettings}, this is not a
 * global singleton - it is one row per tenant, and the absence of a row for a given {@link
 * #getClientId()} means auto-generation is off for that tenant (the correct default). Nothing
 * seeds a row per client; {@code ProductSkuSettingsService.update} creates one the first time a
 * tenant actually configures this feature.
 *
 * <p>Not a {@link com.procurepal_services.stock_bridge_api.tenant.TenantAwareEntity}: that base
 * class's Hibernate {@code @Filter}-based tenant scoping exists for entities read via generic
 * list/search queries. This table is always looked up by exactly one explicit {@code clientId} -
 * including from the bulk-import worker thread, which does not carry {@code TenantContext} - so a
 * plain {@code clientId} column with hand-written repository methods is simpler and avoids that
 * dependency.
 *
 * <p>{@link #getPattern()} is the single source of truth for both the guided "Simple" and raw
 * "Advanced" pattern editors in the UI; there is no separate stored mode. {@link
 * #getNextSequence()} and {@link #getCurrentPeriodKey()} are only ever written by {@code
 * SkuGenerationService} under a row lock - never by the settings-update path, so editing the
 * pattern or toggling {@link #isEnabled()} can never reset or rewind the counter.
 */
@Entity
@Table(name = "product_sku_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductSkuSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 100)
    private String pattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_cadence", nullable = false, length = 10)
    private SkuResetCadence resetCadence;

    /** Reserved under a row lock by {@code SkuGenerationService}; never written on settings update. */
    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;

    /** '2026' under YEARLY, '2026-09' under MONTHLY, null under NEVER. See {@link SkuResetCadence}. */
    @Column(name = "current_period_key", length = 10)
    private String currentPeriodKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
