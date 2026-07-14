-- Stock Bridge initial schema.
-- Primary keys are UUID (via built-in gen_random_uuid(), PostgreSQL 13+) app-wide for consistency.
-- updated_at is maintained by a DB trigger since no JPA auditing is wired up yet.

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- clients: tenants. slug is the human-readable identifier tenants use to log in
-- (distinct from the surrogate id used for FKs).
-- ============================================================================
CREATE TABLE clients (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                 VARCHAR(255) NOT NULL,
    slug                 VARCHAR(100) NOT NULL,
    admin_contact_email  VARCHAR(255) NOT NULL,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_clients_slug UNIQUE (slug)
);

CREATE TRIGGER trg_clients_set_updated_at
    BEFORE UPDATE ON clients
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- permissions: fine-grained capabilities. Fixed roles map to these below,
-- but authorization checks should always go through permissions, not role
-- names, so custom roles can be introduced later without a schema rewrite.
-- ============================================================================
CREATE TABLE permissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(100) NOT NULL,
    description  VARCHAR(255),
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

-- ============================================================================
-- roles: fixed system-defined roles for V1 (ADMIN, MANAGER, STAFF).
-- Not tied to a client_id - global and not client-editable.
-- ============================================================================
CREATE TABLE roles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(50) NOT NULL,
    description  VARCHAR(255),
    CONSTRAINT uq_roles_name UNIQUE (name)
);

-- ============================================================================
-- role_permissions: join table, seeded in V2.
-- ============================================================================
CREATE TABLE role_permissions (
    role_id        UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE INDEX idx_role_permissions_permission_id ON role_permissions(permission_id);

-- ============================================================================
-- users: tenant users. Deleting a client cascades to its users. Usernames are
-- only unique per tenant, not globally.
-- ============================================================================
CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id      UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    username       VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role_id        UUID NOT NULL REFERENCES roles(id),
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_client_id_username UNIQUE (client_id, username)
);

CREATE INDEX idx_users_client_id ON users(client_id);
CREATE INDEX idx_users_role_id ON users(role_id);

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- products: tenant-scoped catalog/inventory items. SKU unique per tenant.
-- ============================================================================
CREATE TABLE products (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id             UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    name                  VARCHAR(255) NOT NULL,
    sku                   VARCHAR(100) NOT NULL,
    description           TEXT,
    unit_price            NUMERIC(14,2) NOT NULL,
    cost_price            NUMERIC(14,2),
    quantity_on_hand      INTEGER NOT NULL DEFAULT 0,
    low_stock_threshold   INTEGER,
    image_url             TEXT,
    is_active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_client_id_sku UNIQUE (client_id, sku)
);

CREATE INDEX idx_products_client_id ON products(client_id);

CREATE TRIGGER trg_products_set_updated_at
    BEFORE UPDATE ON products
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- stock_movements: append-only audit log of inventory changes. No updated_at -
-- movements are never edited, only recorded. product_id is RESTRICT (not
-- CASCADE) so the audit trail can't be silently wiped by a product deletion;
-- products are expected to be deactivated (is_active), not hard-deleted.
-- created_by is nullable/SET NULL to support bulk imports and to survive
-- user deletion without losing the movement record.
-- ============================================================================
CREATE TABLE stock_movements (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id            UUID NOT NULL REFERENCES clients(id) ON DELETE CASCADE,
    product_id           UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    movement_type        VARCHAR(20) NOT NULL,
    quantity             INTEGER NOT NULL,
    unit_price_at_time   NUMERIC(14,2),
    note                 VARCHAR(1000),
    created_by           UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_stock_movements_movement_type CHECK (movement_type IN ('IN', 'OUT', 'ADJUSTMENT')),
    CONSTRAINT chk_stock_movements_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_stock_movements_client_id ON stock_movements(client_id);
CREATE INDEX idx_stock_movements_product_id ON stock_movements(product_id);
CREATE INDEX idx_stock_movements_created_by ON stock_movements(created_by);

-- ============================================================================
-- super_admins: platform operators. Entirely separate from tenant users -
-- no client_id, no role_id, not part of any tenant.
-- ============================================================================
CREATE TABLE super_admins (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_super_admins_username UNIQUE (username)
);

CREATE TRIGGER trg_super_admins_set_updated_at
    BEFORE UPDATE ON super_admins
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();
