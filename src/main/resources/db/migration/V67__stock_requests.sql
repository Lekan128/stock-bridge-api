-- Areas, Part 5 (STOCK_AREAS_PART_5.md): asking another area for stock (D-A7, D-A11).
--
-- The Kitchen asks the Store; the Store's people move it (a move that names the request) or say there's none
-- in the store. A request changes no figure itself: only the moves that fill it do.

CREATE TABLE stock_requests (
    -- Made on the phone (D-E11): a request resent after a lost reply, or late from a phone that was offline,
    -- is recorded once.
    id            UUID PRIMARY KEY,
    client_id     UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id     UUID          NOT NULL REFERENCES branches (id),
    -- Asked: the area that has it (the Store).
    from_area_id  UUID          NOT NULL REFERENCES areas (id),
    -- For: the area asking (the Kitchen).
    to_area_id    UUID          NOT NULL REFERENCES areas (id),
    -- OPEN until every line is moved or none in the store (DONE), or the asker takes it back (CANCELLED).
    status        VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    asked_by      UUID          REFERENCES users (id) ON DELETE SET NULL,
    -- When it was asked, as recorded on the phone.
    asked_at      TIMESTAMPTZ   NOT NULL,
    cancelled_by  UUID          REFERENCES users (id) ON DELETE SET NULL,
    cancelled_at  TIMESTAMPTZ,
    note          VARCHAR(1000),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_stock_requests_differ CHECK (from_area_id <> to_area_id),
    CONSTRAINT chk_stock_requests_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED'))
);

CREATE INDEX idx_stock_requests_branch_time ON stock_requests (branch_id, asked_at DESC);
CREATE INDEX idx_stock_requests_open ON stock_requests (from_area_id) WHERE status = 'OPEN';

CREATE TABLE stock_request_lines (
    request_id       UUID           NOT NULL REFERENCES stock_requests (id) ON DELETE CASCADE,
    product_id       UUID           NOT NULL REFERENCES products (id),
    -- In the product's stock unit.
    quantity         INTEGER        NOT NULL CHECK (quantity > 0),
    -- As typed ("2 bags"), display only, like area_move_lines.
    entered_unit     VARCHAR(32),
    entered_quantity NUMERIC(14, 3),
    -- Recounted from the moves that name the request and weren't undone or taken back.
    quantity_moved   INTEGER        NOT NULL DEFAULT 0,
    -- The asked area's people said it has none: the buyer sees it on the Low stock page.
    none_in_store    BOOLEAN        NOT NULL DEFAULT FALSE,
    PRIMARY KEY (request_id, product_id)
);

-- The request a move filled (V63 left room for it).
ALTER TABLE area_moves
    ADD CONSTRAINT fk_area_moves_request FOREIGN KEY (request_id) REFERENCES stock_requests (id) ON DELETE SET NULL;
CREATE INDEX idx_area_moves_request ON area_moves (request_id) WHERE request_id IS NOT NULL;

-- To the asked area's people, and back to the asker when it's answered.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT', 'STOCK_REQUEST', 'STOCK_REQUEST_ANSWERED'));
