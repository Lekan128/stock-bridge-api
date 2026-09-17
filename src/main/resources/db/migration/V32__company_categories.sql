-- A company's own product categories - Grains, Drinks, Cleaning - for grouping and filtering its
-- catalog (BULK_IMPORT_CX_PLAN.md task 1.6). Separate from product_categories, which is the
-- marketplace's platform-curated list for listed products.
CREATE TABLE company_categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    name        VARCHAR(80) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_company_categories_name_not_blank CHECK (btrim(name) <> '')
);

-- One "Grains" per company, whatever its capitalisation.
CREATE UNIQUE INDEX uq_company_categories_client_name ON company_categories (client_id, lower(name));

-- Deleting a category leaves its products uncategorised, never deletes them.
ALTER TABLE products
    ADD COLUMN company_category_id UUID REFERENCES company_categories (id) ON DELETE SET NULL;

CREATE INDEX idx_products_company_category_id ON products (company_category_id);
