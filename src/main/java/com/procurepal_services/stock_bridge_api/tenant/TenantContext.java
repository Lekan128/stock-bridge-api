package com.procurepal_services.stock_bridge_api.tenant;

import java.util.UUID;

/**
 * Holds the current request's tenant (client) id, one value per thread.
 * Set by TenantResolutionFilter from the authenticated principal; read by
 * TenantAwareEntity and TenantScopedRepository. Never trust a value here
 * that didn't come from that filter.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID clientId) {
        CURRENT_TENANT_ID.set(clientId);
    }

    public static UUID get() {
        return CURRENT_TENANT_ID.get();
    }

    public static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}
