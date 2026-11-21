-- Prices per branch (BRANCH_PRICES_PLAN.md, step 1; owner 2026-11-21). A company chooses one price at every branch
-- (today: the product's unit_price), or lets branches have their own. With that on, every branch has prices: a price
-- list of its own, or another branch's (two branches pointing at one list share it). No branch is special. A product
-- with no row in a branch's list has no price there yet. products.unit_price keeps a copy of the main branch's price
-- for what doesn't read branch prices yet; nobody types into it while this is on.

ALTER TABLE clients ADD COLUMN branch_prices BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE price_lists (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_price_lists_client ON price_lists (client_id);

-- Null only while the switch is off (or a branch added then).
ALTER TABLE branches ADD COLUMN price_list_id UUID REFERENCES price_lists (id);

-- The prices every branch used while the switch was off (chosen when turning it off), so prices changed meanwhile
-- land back in them when it comes on again.
ALTER TABLE clients ADD COLUMN branch_prices_off_list_id UUID REFERENCES price_lists (id) ON DELETE SET NULL;

CREATE TABLE price_list_prices (
    price_list_id           UUID           NOT NULL REFERENCES price_lists (id) ON DELETE CASCADE,
    product_id              UUID           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    client_id               UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- Per stock unit, what a sale multiplies; and as typed, with the unit it was typed per (as products, V47).
    unit_price              NUMERIC(14, 2) NOT NULL CHECK (unit_price >= 0),
    unit_price_entered      NUMERIC(14, 2),
    unit_price_entered_unit VARCHAR(32),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_by              UUID,
    PRIMARY KEY (price_list_id, product_id)
);
CREATE INDEX idx_price_list_prices_product ON price_list_prices (product_id);

-- Which prices a change was to: null for the one price (switch off).
ALTER TABLE product_price_changes ADD COLUMN price_list_id UUID REFERENCES price_lists (id) ON DELETE SET NULL;
