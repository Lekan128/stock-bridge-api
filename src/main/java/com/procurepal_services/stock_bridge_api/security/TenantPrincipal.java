package com.procurepal_services.stock_bridge_api.security;

import java.util.UUID;

/**
 * Implemented by any authenticated principal that belongs to a tenant.
 * TenantResolutionFilter checks for this interface to decide whether to
 * populate TenantContext for the request. A principal that doesn't implement
 * it - e.g. a future super-admin principal, which is not part of any tenant -
 * is correctly treated as having no tenant.
 */
public interface TenantPrincipal {

    UUID getClientId();
}
