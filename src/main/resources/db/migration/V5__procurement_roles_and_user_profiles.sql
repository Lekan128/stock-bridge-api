-- Replaces the placeholder V1 role set (ADMIN/MANAGER/STAFF) with the roles the
-- procurement/inventory product actually ships with, and gives users a profile
-- plus a "root" flag.
--
-- The three roles that map cleanly are RENAMED IN PLACE rather than
-- inserted-and-remapped: roles.id is what users.role_id points at, so renaming
-- keeps every existing user on the right role without rewriting a single user
-- row (and without a window where users.role_id is dangling).
--
-- Every statement here is written to be safe against a database that already
-- has the demo seed applied (db/seed/V9000, which runs before this migration on
-- an existing local database - see spring.flyway.out-of-order in
-- application-local.yml), hence the ON CONFLICT DO NOTHING guards and the
-- delete-then-insert rebuild of role_permissions.

-- ============================================================================
-- Permissions: VIEW_PRODUCTS is new. It exists so a role can see the catalog
-- without being able to change it, which is the whole distinction between
-- PROCUREMENT_MANAGER (owns the catalog) and everyone else.
-- ============================================================================
INSERT INTO permissions (code, description) VALUES
    ('VIEW_PRODUCTS', 'View the product catalog without editing it')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- Roles: rename the three that carry over, insert the two that are genuinely
-- new, and restate every description in the new vocabulary.
-- ============================================================================
UPDATE roles SET name = 'OWNER', description = 'The account holder. Full access to every tenant feature and setting.'
WHERE name = 'ADMIN';

UPDATE roles SET name = 'PROCUREMENT_MANAGER', description = 'Owns the product catalog, moves stock, and views analytics.'
WHERE name = 'MANAGER';

UPDATE roles SET name = 'STOREKEEPER', description = 'Records stock movements on the floor and views the catalog.'
WHERE name = 'STAFF';

INSERT INTO roles (name, description) VALUES
    ('INVENTORY_OFFICER', 'Runs stock levels and reporting. Cannot edit the catalog.'),
    ('FINANCE_OFFICER',   'Read-only. Sees the catalog and analytics, changes nothing.')
ON CONFLICT (name) DO NOTHING;

-- ============================================================================
-- role_permissions: rebuilt from scratch for these five roles rather than
-- patched, so the resulting matrix is readable in one place and identical
-- whether this runs on a fresh database or one seeded under the old role set.
-- ============================================================================
DELETE FROM role_permissions
WHERE role_id IN (
    SELECT id FROM roles
    WHERE name IN ('OWNER', 'PROCUREMENT_MANAGER', 'INVENTORY_OFFICER', 'FINANCE_OFFICER', 'STOREKEEPER')
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    ('OWNER',               'MANAGE_USERS'),
    ('OWNER',               'MANAGE_ROLES'),
    ('OWNER',               'MANAGE_PRODUCTS'),
    ('OWNER',               'VIEW_PRODUCTS'),
    ('OWNER',               'MANAGE_INVENTORY'),
    ('OWNER',               'VIEW_ANALYTICS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_PRODUCTS'),
    ('PROCUREMENT_MANAGER', 'VIEW_PRODUCTS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_INVENTORY'),
    ('PROCUREMENT_MANAGER', 'VIEW_ANALYTICS'),
    ('INVENTORY_OFFICER',   'VIEW_PRODUCTS'),
    ('INVENTORY_OFFICER',   'MANAGE_INVENTORY'),
    ('INVENTORY_OFFICER',   'VIEW_ANALYTICS'),
    ('FINANCE_OFFICER',     'VIEW_PRODUCTS'),
    ('FINANCE_OFFICER',     'VIEW_ANALYTICS'),
    ('STOREKEEPER',         'VIEW_PRODUCTS'),
    ('STOREKEEPER',         'MANAGE_INVENTORY')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ============================================================================
-- users: profile columns (all nullable - a sub-user created by an admin may
-- legitimately have nothing but a username at first) and the root flag.
--
-- is_root marks the tenant's account creator. It is not a role and not a
-- permission: it is an ownership fact used to protect that one account from
-- being locked out or hijacked by another admin (see UserManagementService).
-- ============================================================================
ALTER TABLE users
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN last_name  VARCHAR(100),
    ADD COLUMN email      VARCHAR(255),
    ADD COLUMN phone      VARCHAR(50),
    ADD COLUMN job_title  VARCHAR(100),
    ADD COLUMN is_root    BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: per client, the earliest-created OWNER is the root. Falling back to
-- the earliest-created user of any role covers tenants created before the OWNER
-- role existed in this shape - a tenant with users must always have exactly one
-- root, or root protection silently protects nobody.
WITH ranked AS (
    SELECT u.id,
           ROW_NUMBER() OVER (
               PARTITION BY u.client_id
               ORDER BY (r.name = 'OWNER') DESC, u.created_at, u.id
           ) AS rank_in_client
    FROM users u
    JOIN roles r ON r.id = u.role_id
)
UPDATE users
SET is_root = TRUE
WHERE id IN (SELECT id FROM ranked WHERE rank_in_client = 1);

-- Enforces "at most one root per client" in the database, not just in service
-- code - a partial unique index so the FALSE rows (everyone else) are unconstrained.
CREATE UNIQUE INDEX uq_users_one_root_per_client ON users (client_id) WHERE is_root;
