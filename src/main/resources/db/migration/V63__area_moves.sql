-- Areas, Part 2 (STOCK_AREAS_PART_2.md): moving stock between the areas of a branch.
--
-- A move is not a ledger movement (STOCK_AREAS_BUILD_PLAN.md D-E5): it changes no branch figure, no lot,
-- no cost, so stock_movements and every reader of movement_type stay as they are. A give to an area that
-- uses what it's given IS a stock-out (reason USED) at the sending area, and its line points at it.

CREATE TABLE area_moves (
    -- Made on the phone (D-E11): a move resent after a lost reply, or sent late from a phone that was
    -- offline, is recorded once.
    id               UUID PRIMARY KEY,
    client_id        UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id        UUID          NOT NULL REFERENCES branches (id),
    from_area_id     UUID          NOT NULL REFERENCES areas (id),
    to_area_id       UUID          NOT NULL REFERENCES areas (id),
    -- DONE and VOIDED from Part 2; WAITING, CONFIRMED_SHORT and CANCELLED are Part 4's confirming areas.
    status           VARCHAR(20)   NOT NULL,
    moved_by         UUID          REFERENCES users (id) ON DELETE SET NULL,
    -- When it happened, as recorded on the phone.
    moved_at         TIMESTAMPTZ   NOT NULL,
    -- Sent late from a phone that was offline: a later count may already have seen it (absorbed).
    recorded_offline BOOLEAN       NOT NULL DEFAULT FALSE,
    confirmed_by     UUID          REFERENCES users (id) ON DELETE SET NULL,
    confirmed_at     TIMESTAMPTZ,
    -- Part 5: the request it filled.
    request_id       UUID,
    note             VARCHAR(1000),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_area_moves_differ CHECK (from_area_id <> to_area_id),
    CONSTRAINT chk_area_moves_status CHECK (status IN ('DONE', 'WAITING', 'CONFIRMED_SHORT', 'CANCELLED', 'VOIDED'))
);

CREATE INDEX idx_area_moves_branch_time ON area_moves (branch_id, moved_at DESC);
CREATE INDEX idx_area_moves_client_time ON area_moves (client_id, moved_at DESC);
CREATE INDEX idx_area_moves_waiting ON area_moves (to_area_id) WHERE status = 'WAITING';

CREATE TABLE area_move_lines (
    move_id            UUID           NOT NULL REFERENCES area_moves (id) ON DELETE CASCADE,
    product_id         UUID           NOT NULL REFERENCES products (id),
    -- In the product's stock unit.
    quantity           INTEGER        NOT NULL CHECK (quantity > 0),
    -- Part 4.
    quantity_confirmed INTEGER,
    quantity_damaged   INTEGER        NOT NULL DEFAULT 0,
    -- As typed ("2 bags"), display only, like stock_movements.entered_* (V21).
    entered_unit       VARCHAR(32),
    entered_quantity   NUMERIC(14, 3),
    -- To an area that uses what it's given: the stock-out (USED) it wrote at the sending area.
    used_movement_id   UUID           REFERENCES stock_movements (id),
    PRIMARY KEY (move_id, product_id)
);

-- "What moved in or out of the Kitchen after this count": a count carries forward its area's later moves.
CREATE INDEX idx_area_move_lines_product ON area_move_lines (product_id);

-- A stock-out that gave stock to an area that uses what it's given: which area (D-E7).
ALTER TABLE stock_movements ADD COLUMN used_area_id UUID REFERENCES areas (id);
