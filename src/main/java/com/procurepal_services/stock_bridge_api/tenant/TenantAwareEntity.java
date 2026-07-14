package com.procurepal_services.stock_bridge_api.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Base class for entities scoped to a single tenant (client) - e.g. Product,
 * StockMovement in a later step, and User today. client_id is never settable
 * by a caller: @PrePersist always overwrites it from TenantContext, which is
 * itself only ever populated by TenantResolutionFilter from a trusted,
 * already-authenticated principal.
 *
 * This is layer 1 of tenant isolation (see TenantResolutionFilter for how the
 * filter below gets enabled, and why). Layer 2 is TenantScopedRepository's
 * explicit client_id predicates, which hold even if this filter is ever left
 * disabled.
 */
@MappedSuperclass
@FilterDef(name = TenantAwareEntity.TENANT_FILTER_NAME, parameters = @ParamDef(name = TenantAwareEntity.TENANT_FILTER_PARAM, type = UUID.class))
@Filter(name = TenantAwareEntity.TENANT_FILTER_NAME, condition = "client_id = :" + TenantAwareEntity.TENANT_FILTER_PARAM)
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class TenantAwareEntity {

    public static final String TENANT_FILTER_NAME = "tenantFilter";
    public static final String TENANT_FILTER_PARAM = "tenantId";

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @PrePersist
    protected void assignTenantId() {
        UUID currentTenantId = TenantContext.get();
        if (currentTenantId == null) {
            throw new IllegalStateException(
                    "No tenant context is set; refusing to persist a " + getClass().getSimpleName()
                            + " without a resolved client_id");
        }
        this.clientId = currentTenantId;
    }
}
