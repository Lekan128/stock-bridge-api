-- The first week (LANDING_PAGE_PLAN.md, step 5): from sign-up to a shop that counts its stock.

-- ----------------------------------------------------------------------------
-- "Send us your list": the files a shop sends so the team can load its products.
--
-- Kept in the database, not S3: they are private (a shop's price list, photos of its stock book)
-- where the product-image bucket is public, they are few (a founding shop sends a handful, once),
-- and they are capped (10 MB a file, 30 a shop; ProductListService). Deleted with the shop.
-- ----------------------------------------------------------------------------
CREATE TABLE product_list_files (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    setup_request_id UUID         REFERENCES setup_requests (id) ON DELETE SET NULL,
    file_name        VARCHAR(255) NOT NULL,
    content_type     VARCHAR(120) NOT NULL,
    size_bytes       INTEGER      NOT NULL,
    data             BYTEA        NOT NULL,
    uploaded_by      UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_list_files_client ON product_list_files (client_id, created_at);

-- ----------------------------------------------------------------------------
-- Procurepaddy support: the staff account the team uses inside a shop that asked for a setup,
-- to load its products with the shop's own import tool. Every change it makes is signed with
-- its name, like any staff member's, and the owner can switch it off on the Users page.
--
-- A system role (client_id NULL) that is NOT in TenantRoles.ALL, so no shop can list it or give
-- it to anyone (RoleCatalogService, UserManagementService.resolveRole). Products, stock and
-- suppliers only: no users, no roles, no company settings, no buying, no marketplace.
-- ----------------------------------------------------------------------------
INSERT INTO roles (name, description)
SELECT 'PROCUREPADDY_SUPPORT', 'Procurepaddy''s setup team, inside a shop that asked us to load its products. Products, stock and suppliers only.'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'PROCUREPADDY_SUPPORT' AND client_id IS NULL);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'VIEW_PRODUCTS', 'MANAGE_PRODUCTS', 'PRODUCT_SKU_OVERRIDE',
    'MANAGE_INVENTORY', 'STOCK_IN', 'STOCK_OUT',
    'VIEW_VENDORS', 'MANAGE_VENDORS',
    'VIEW_ANALYTICS')
WHERE r.name = 'PROCUREPADDY_SUPPORT' AND r.client_id IS NULL
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ----------------------------------------------------------------------------
-- The first-week messages the team has sent a shop on WhatsApp (plan §4: welcome, products
-- loaded, day 3, day 7). Sent by a person from the WhatsApp Business app, so this records that it
-- was sent, and by whom; one of each kind per shop.
-- ----------------------------------------------------------------------------
CREATE TABLE onboarding_messages (
    client_id  UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    kind       VARCHAR(20) NOT NULL CHECK (kind IN ('WELCOME', 'LOADED', 'DAY_3', 'DAY_7')),
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_by    UUID        REFERENCES super_admins (id) ON DELETE SET NULL,
    PRIMARY KEY (client_id, kind)
);
