-- Multiple branches, Phase 0 (MULTI_BRANCH_BUILD_PLAN.md section 3.1 and 8.1): the stock ledger
-- learns which branch each change happened at. Nothing a person sees changes: every company still
-- has exactly one branch, and every write lands there.
--
-- <h2>The two modes, and why a one-branch company's figure is still products.quantity_on_hand</h2>
-- While a company has ONE branch, products.quantity_on_hand stays the truth, exactly as before, and
-- branch_stock only mirrors it (BranchStockService re-copies it on every write, so a figure set
-- outside the ledger - an old import, a fix by hand - cannot leave the mirror behind). When a
-- company gets its SECOND branch (Phase 1), one explicit step copies every product's figure into
-- the main branch's row; from then on branch_stock is the truth and quantity_on_hand is its sum.
-- That keeps a single shop's behaviour identical by construction rather than by testing alone.

-- Every company already has a default branch (V6, ClientProvisioning) - except rows created by hand
-- without one. Same closing of the gap V9001 does for the seed, so the backfills below never skip
-- a company.
INSERT INTO branches (client_id, name, is_default, is_active)
SELECT c.id, 'Head Office', TRUE, TRUE
FROM clients c
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.client_id = c.id)
ON CONFLICT (client_id, name) DO NOTHING;

-- How many of a product a branch holds. Sparse: a missing row means none.
--
-- branch_id is NO ACTION, not RESTRICT: deleting a client cascades to its branches and to this
-- table in one statement, and NO ACTION checks at the end of that statement, after both are gone.
-- A branch on its own is closed, never deleted (plan section 5.11), so nothing else deletes one.
CREATE TABLE branch_stock (
    client_id           UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id           UUID        NOT NULL REFERENCES branches (id),
    product_id          UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity_on_hand    INTEGER     NOT NULL DEFAULT 0,
    -- NULL = use the product's own level. Written from Phase 1.
    low_stock_threshold INTEGER,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, product_id)
);

CREATE INDEX idx_branch_stock_product_id ON branch_stock (product_id);
CREATE INDEX idx_branch_stock_client_branch ON branch_stock (client_id, branch_id);

CREATE TRIGGER trg_branch_stock_set_updated_at
    BEFORE UPDATE ON branch_stock
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
SELECT p.client_id, b.id, p.id, p.quantity_on_hand
FROM products p
JOIN branches b ON b.client_id = p.client_id AND b.is_default
WHERE p.quantity_on_hand <> 0;

-- Which branch each ledger row happened at. A raw column rather than a mapped association on the
-- entity, like import_batch_id: the ledger is read in bulk and wants no association to wander into.
-- NO ACTION for the same reason as branch_stock.branch_id.
ALTER TABLE stock_movements ADD COLUMN branch_id UUID REFERENCES branches (id);

-- stock_movements is append-only by convention, not by trigger (V20), so this backfill is allowed.
-- It is the last time anything edits these rows.
UPDATE stock_movements m
SET branch_id = b.id
FROM branches b
WHERE b.client_id = m.client_id AND b.is_default AND m.branch_id IS NULL;

ALTER TABLE stock_movements ALTER COLUMN branch_id SET NOT NULL;

-- FIFO and counts read one product's lots at one branch, oldest first
-- (StockMovementRepository.findInMovementsForUpdate). idx_stock_movements_product_id_type_occurred_at
-- stays until Phase 1's reads are measured against this one.
CREATE INDEX idx_stock_movements_lots
    ON stock_movements (product_id, branch_id, movement_type, occurred_at, created_at);
-- The foreign key's own check when a client (and so its branches) is deleted.
CREATE INDEX idx_stock_movements_branch_id ON stock_movements (branch_id);
