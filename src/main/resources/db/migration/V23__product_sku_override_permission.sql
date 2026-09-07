-- One permission: unlock and hand-edit a single product's auto-generated SKU.
--
-- When product_sku_settings.enabled is true, ProductManagementService.update
-- refuses a changed sku on PUT /api/products/{id} unless the caller holds
-- this. Without it, the only way to correct a bad generated SKU, or type in a
-- legacy code exactly during a migration, would be disabling auto-generation
-- tenant-wide - which stops every OTHER product from generating too, to fix
-- one row. This permission is the narrow escape hatch instead.
--
-- Deliberately its own code, not folded into MANAGE_PRODUCTS: every MANAGE_
-- PRODUCTS holder can already create/edit/deactivate products freely when
-- auto-generation is off, but overriding a SKU the tenant configured a
-- pattern specifically to enforce is a stronger, separately-grantable action
-- - the same reasoning V7 gives for not folding MANAGE_COMPANY_PROFILE into
-- a broader grant.
--
-- Narrow additive insert, matching V7's reasoning exactly: this adds one
-- grant, not a rewrite of the matrix. See V7's header for why additive is
-- preferred over restating role_permissions wholesale.

INSERT INTO permissions (code, description) VALUES
    ('PRODUCT_SKU_OVERRIDE', 'Unlock and manually edit a single product''s auto-generated SKU')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- Granted to OWNER and PROCUREMENT_MANAGER.
--
-- Both already hold MANAGE_PRODUCTS (V5, V6) - the only two roles that do.
-- PROCUREMENT_MANAGER is the role V5 itself describes as owning the product
-- catalog ("Owns the product catalog, moves stock, and views analytics",
-- quoted in V7); a generated SKU colliding with a supplier's own labeling, or
-- a legacy code that must be typed in exactly during a migration, is squarely
-- catalog-maintenance work this role already does. OWNER gets it as the
-- account holder, the same standing V7 grants MANAGE_COMPANY_PROFILE on.
--
-- Considered and rejected, for the record:
--   INVENTORY_OFFICER - V5 states its remit in so many words: "Runs stock
--     levels and reporting. Cannot edit the catalog." It holds VIEW_PRODUCTS
--     and MANAGE_INVENTORY, not MANAGE_PRODUCTS - granting it a catalog-
--     identity override would contradict the role's own stated design, not
--     just extend it.
--   FINANCE_OFFICER - explicitly read-only by design (V5: "changes nothing"),
--     same reasoning V7 gives for excluding it from MANAGE_COMPANY_PROFILE.
--   STOREKEEPER - floor/stock-count work, no catalog-identity concern.
--
-- No dynamic role/permission-assignment UI exists in this codebase (every
-- grant is migration-only) - widening this later is a two-line additive
-- migration, per V7's closing note.
-- ============================================================================
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('OWNER',               'PRODUCT_SKU_OVERRIDE'),
    ('PROCUREMENT_MANAGER', 'PRODUCT_SKU_OVERRIDE')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
