-- Selling till Stage C, Group B (SELLING_TILL_PLAN.md Step 10; owner, 2026-11-19): charges and taxes.
--
-- One list per branch, VAT there to start with. Each charge is a % of the food and drinks or a fixed amount per sale,
-- either already in the prices (the receipt says "Includes VAT ₦75") or added on top, and on every sale or on table
-- bills only. Each is worked out on the food and drinks, never on another charge, to the naira. Switched off, never
-- deleted, so an old receipt keeps it.
CREATE TABLE branch_charges (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id  UUID           NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    name       VARCHAR(40)    NOT NULL CONSTRAINT chk_branch_charges_name CHECK (btrim(name) <> ''),
    kind       VARCHAR(8)     NOT NULL CHECK (kind IN ('PERCENT', 'AMOUNT')),
    value      NUMERIC(12, 2) NOT NULL CHECK (value > 0),
    included   BOOLEAN        NOT NULL DEFAULT FALSE,
    applies    VARCHAR(12)    NOT NULL DEFAULT 'EVERY_SALE' CHECK (applies IN ('EVERY_SALE', 'TABLE_BILLS')),
    is_active  BOOLEAN        NOT NULL DEFAULT FALSE,
    sort_order INT            NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_branch_charges_percent CHECK (kind <> 'PERCENT' OR value < 100)
);
CREATE UNIQUE INDEX uq_branch_charges_name ON branch_charges (branch_id, lower(name));

-- A sale's charges as they stood when it was made: a later change to VAT never rewrites an old receipt. The amount
-- follows the sale's lines (a line cancelled, a price added), worked out again whenever they change.
CREATE TABLE sale_charges (
    sale_id   UUID           NOT NULL,
    charge_id UUID           NOT NULL REFERENCES branch_charges (id),
    client_id UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    name      VARCHAR(40)    NOT NULL,
    kind      VARCHAR(8)     NOT NULL,
    value     NUMERIC(12, 2) NOT NULL,
    included  BOOLEAN        NOT NULL,
    amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    position  INT            NOT NULL DEFAULT 0,
    PRIMARY KEY (sale_id, charge_id)
);
CREATE INDEX idx_sale_charges_client ON sale_charges (client_id);
