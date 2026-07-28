package com.procurepal_services.stock_bridge_api.user;

import java.util.Set;

/**
 * The role codes seeded by the Flyway migrations. Kept as a small allow-list so
 * a typo'd role name fails as a clean 400 rather than a "role not seeded"
 * IllegalStateException, and so OWNER - the one role with rules attached to it
 * (see UserManagementService's last-active-owner guard) - has one name in code.
 *
 * Note this is NOT what /api/roles serves: that reads the roles table, because
 * the product intends to support tenant-defined roles later and the frontend
 * must render whatever exists rather than a compiled-in list.
 */
public final class TenantRoles {

    public static final String OWNER = "OWNER";
    public static final String PROCUREMENT_MANAGER = "PROCUREMENT_MANAGER";
    public static final String INVENTORY_OFFICER = "INVENTORY_OFFICER";
    public static final String FINANCE_OFFICER = "FINANCE_OFFICER";
    public static final String STOREKEEPER = "STOREKEEPER";

    public static final Set<String> ALL =
            Set.of(OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER, STOREKEEPER);

    private TenantRoles() {
    }
}
