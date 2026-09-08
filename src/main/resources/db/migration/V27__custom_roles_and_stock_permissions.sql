-- Two independent changes, bundled because both are prerequisites for the same feature
-- (the Roles & Privileges screen):
--
--   1. STOCK_IN / STOCK_OUT: split out of MANAGE_INVENTORY so a role can be granted the
--      ability to receive stock, issue stock, both, or neither, independently. Granted
--      below to every role that already holds MANAGE_INVENTORY today, so no existing
--      tenant loses a capability it already has - this migration only subdivides an
--      existing grant, it does not widen or narrow who can do what.
--
--   2. roles.client_id: turns the fixed/global role table into system roles (client_id
--      NULL - OWNER, PROCUREMENT_MANAGER, INVENTORY_OFFICER, FINANCE_OFFICER, STOREKEEPER,
--      VENDOR, unchanged and not editable through the new screen) plus, going forward,
--      each tenant's own custom roles (client_id set). See RoleManagementService.
--
-- ============================================================================
-- STOCK_IN / STOCK_OUT
-- ============================================================================
INSERT INTO permissions (code, description) VALUES
    ('STOCK_IN',  'Record stock received into inventory'),
    ('STOCK_OUT', 'Record stock issued out of inventory')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('OWNER',               'STOCK_IN'),  ('OWNER',               'STOCK_OUT'),
    ('PROCUREMENT_MANAGER', 'STOCK_IN'),  ('PROCUREMENT_MANAGER', 'STOCK_OUT'),
    ('INVENTORY_OFFICER',   'STOCK_IN'),  ('INVENTORY_OFFICER',   'STOCK_OUT'),
    ('STOREKEEPER',         'STOCK_IN'),  ('STOREKEEPER',         'STOCK_OUT'),
    ('VENDOR',              'STOCK_IN'),  ('VENDOR',              'STOCK_OUT')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ============================================================================
-- roles.client_id
-- ============================================================================
ALTER TABLE roles ADD COLUMN client_id UUID REFERENCES clients(id) ON DELETE CASCADE;

CREATE INDEX idx_roles_client_id ON roles(client_id);

-- Every existing row is a system role - explicit no-op, stated for the record rather than
-- relying on the column default (NULL is already the default for a new nullable column).
UPDATE roles SET client_id = NULL WHERE client_id IS NULL;

-- Replace the single global name constraint with two partial ones: system role names stay
-- globally unique among themselves, tenant role names are unique only within that tenant -
-- two different companies can both call a custom role "Warehouse Clerk".
ALTER TABLE roles DROP CONSTRAINT uq_roles_name;

CREATE UNIQUE INDEX uq_roles_system_name ON roles(name) WHERE client_id IS NULL;
CREATE UNIQUE INDEX uq_roles_tenant_name ON roles(client_id, name) WHERE client_id IS NOT NULL;
