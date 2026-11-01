-- Areas inside a branch, Part 0 (STOCK_AREAS_BUILD_PLAN.md section 3, STOCK_AREAS_PART_0.md): the
-- places a branch's stock is kept in - Store, Kitchen, Bar. A level BELOW the branch, never a kind of
-- branch: branch_stock stays the branch's figure, and becomes the sum of its areas once it has two.
-- Invisible until then; every branch starts with one area, its Store.

CREATE TABLE areas (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- NO ACTION, like branch_stock's (V51): deleting a company cascades to its branches and its areas
    -- in one statement, and NO ACTION checks at the end of it.
    branch_id   UUID         NOT NULL REFERENCES branches (id),
    name        VARCHAR(80)  NOT NULL,
    -- False: "what goes there is used" (D-A4). Such an area never holds a figure.
    keeps_stock BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Moves into it wait for somebody there to confirm them (D-A2, Part 4).
    confirms    BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Where deliveries arrive, and where stock leaving for another branch is taken from (D-E10).
    is_delivery BOOLEAN      NOT NULL DEFAULT FALSE,
    -- The area that holds the branch's history from before it had areas: a stock_movements row with no
    -- area_id belongs to it (D-E3), so the ledger needs no backfill.
    is_original BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    closed_at   TIMESTAMPTZ,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_areas_delivery_keeps_stock CHECK (NOT is_delivery OR keeps_stock),
    CONSTRAINT chk_areas_original_keeps_stock CHECK (NOT is_original OR keeps_stock)
);

CREATE UNIQUE INDEX uq_areas_branch_name ON areas (branch_id, lower(name));
CREATE UNIQUE INDEX uq_areas_branch_delivery ON areas (branch_id) WHERE is_delivery;
CREATE UNIQUE INDEX uq_areas_branch_original ON areas (branch_id) WHERE is_original;
CREATE INDEX idx_areas_client ON areas (client_id);

CREATE TRIGGER trg_areas_set_updated_at
    BEFORE UPDATE ON areas
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Every branch there is gets its Store.
INSERT INTO areas (client_id, branch_id, name, is_delivery, is_original)
SELECT client_id, id, 'Store', TRUE, TRUE FROM branches;

-- And every branch made from now on, whichever of the paths that create one made it - signup, Add a
-- branch, the platform bootstrap, the repair in BranchStockService, the seeds, the tests. A branch
-- without an area would have nowhere to record anything.
CREATE FUNCTION create_first_area() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO areas (client_id, branch_id, name, is_delivery, is_original)
    VALUES (NEW.client_id, NEW.id, 'Store', TRUE, TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_branches_first_area
    AFTER INSERT ON branches
    FOR EACH ROW
    EXECUTE FUNCTION create_first_area();

-- How many of each product each area holds - only for a branch that has ever had two areas, where these
-- rows are the truth and branch_stock their sum (D-E2). Empty until Part 1 lets a branch add one.
CREATE TABLE area_stock (
    client_id        UUID    NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    area_id          UUID    NOT NULL REFERENCES areas (id),
    -- Denormalised from the area, for "every area of this branch" reads.
    branch_id        UUID    NOT NULL REFERENCES branches (id),
    product_id       UUID    NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity_on_hand INTEGER NOT NULL DEFAULT 0,
    -- "Kitchen keeps 10 bags" (Part 8). NULL = none.
    usual_level      INTEGER,
    last_counted_at  TIMESTAMPTZ,
    PRIMARY KEY (area_id, product_id)
);

CREATE INDEX idx_area_stock_branch_product ON area_stock (branch_id, product_id);
CREATE INDEX idx_area_stock_product ON area_stock (product_id);

-- The area a movement happened in. Nullable and not backfilled (D-E3): the table is the biggest there
-- is, and a row without one belongs to its branch's original area.
ALTER TABLE stock_movements ADD COLUMN area_id UUID REFERENCES areas (id);
