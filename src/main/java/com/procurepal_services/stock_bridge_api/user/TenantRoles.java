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

    /**
     * A marketplace seller's single account (seeded by V11__vendors.sql).
     *
     * <h2>Deliberately NOT in {@link #ALL}</h2>
     * ALL is the allow-list of roles a caller may ASK for - both
     * UserManagementService and SuperAdminUserService validate a requested role
     * name against it and reject anything else as a clean 400. A vendor has
     * exactly one user account and cannot create staff, so VENDOR must never be
     * assignable through the ordinary user-management paths: putting it in ALL
     * would let any company's OWNER create a VENDOR-role user inside their own
     * tenant, which is precisely the rule this feature is built on. It is granted
     * only by the super-admin vendor-creation path, which looks the role up by
     * this constant rather than through the allow-list.
     *
     * <p>Same reason it is filtered out of GET /api/roles: that endpoint is a
     * picker of assignable roles, and offering an option that the very next
     * request rejects is worse than not offering it.
     */
    public static final String VENDOR = "VENDOR";

    /**
     * The roles a tenant user may be given. VENDOR is excluded on purpose - see
     * the constant. If a role is added here it becomes assignable by every OWNER
     * in the product, so the omission is load-bearing rather than an oversight.
     */
    public static final Set<String> ALL =
            Set.of(OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER, STOREKEEPER);

    private TenantRoles() {
    }
}
