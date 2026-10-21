-- The selling price as it was typed, and who changed it when
-- (SELLING_PRICE_PLAN.md section 4.5).
--
-- ============================================================================
-- unit_price_entered / unit_price_entered_unit
-- ============================================================================
-- unit_price stays what it has always been - the selling price per ONE stock
-- unit, which checkout multiplies by an order quantity. But a shop prices "5,000
-- a carton", and per piece of 24 that is 208.33, which is not 5,000 back again
-- (24 x 208.33 = 4,999.92). A sale pre-filled from 208.33 x 24 would charge the
-- customer the wrong amount by default. So the price is also kept exactly as
-- typed, with the unit it was typed per, and a sale in that unit pre-fills from
-- it directly.
--
-- Both null when the price was typed per stock unit - then unit_price IS what
-- was typed, and repeating it would invent a "the user chose a unit" fact (the
-- same rule as stock_movements.entered_unit, V21). Never read to compute a
-- balance, a cost or a checkout total: those stay on unit_price.
ALTER TABLE products
    ADD COLUMN unit_price_entered      NUMERIC(14, 2),
    ADD COLUMN unit_price_entered_unit VARCHAR(32),
    ADD CONSTRAINT chk_products_unit_price_entered_paired CHECK (
        (unit_price_entered IS NULL) = (unit_price_entered_unit IS NULL));

-- ============================================================================
-- product_price_changes
-- ============================================================================
-- One row per change to a product's selling price: old and new, who, when, and
-- through what (the product form, a sheet, or undoing a sheet). Owners worry
-- about staff quietly changing prices; this is the record that answers them,
-- shown in the product's history. Append-only - never updated.
--
-- Not a column on product_changes (V37): that table is the sync feed, one row
-- per "something about this product changed", and carries no values at all.
CREATE TABLE product_price_changes (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id              UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    product_id             UUID          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    old_unit_price         NUMERIC(14, 2),
    new_unit_price         NUMERIC(14, 2),
    old_entered_price      NUMERIC(14, 2),
    old_entered_unit       VARCHAR(32),
    new_entered_price      NUMERIC(14, 2),
    new_entered_unit       VARCHAR(32),
    source                 VARCHAR(20)   NOT NULL,
    changed_by             UUID          REFERENCES users (id) ON DELETE SET NULL,
    changed_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT chk_product_price_changes_source CHECK (source IN ('FORM', 'IMPORT', 'IMPORT_UNDO'))
);

CREATE INDEX idx_product_price_changes_product_id_changed_at
    ON product_price_changes (product_id, changed_at DESC);
CREATE INDEX idx_product_price_changes_client_id ON product_price_changes (client_id);
