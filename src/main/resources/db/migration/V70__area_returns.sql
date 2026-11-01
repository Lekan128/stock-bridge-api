-- Areas, Part 6 (STOCK_AREAS_PART_6.md): give back and report spoilt from an area that uses stock up as soon as it
-- arrives (D-A6), and saying what happened when less arrives than was moved (owner, 2026-11-01).

-- Confirmed short: what happened to each short line, and the confirmer's note. BACK (still where it was moved
-- from), DAMAGED, EXPIRED, LOST, OTHER; null on a line that arrived whole, and on lines confirmed before this
-- (read from quantity_damaged: damaged or back).
ALTER TABLE area_moves      ADD COLUMN confirm_note VARCHAR(500);
ALTER TABLE area_move_lines ADD COLUMN rest_reason  VARCHAR(20)
    CHECK (rest_reason IN ('BACK', 'DAMAGED', 'EXPIRED', 'LOST', 'OTHER'));

-- What an area that uses stock up did with part of a give: gave it back (an adjustment up at the area it came
-- from, reversing that much of the Used stock-out - movement_id) or reported it spoilt, expired, damaged
-- (no figure changes: it was used; this says how). The id is made on the phone: a resend is recorded once.
CREATE TABLE area_move_returns (
    id          UUID PRIMARY KEY,
    client_id   UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id   UUID         NOT NULL REFERENCES branches (id),
    move_id     UUID         NOT NULL REFERENCES area_moves (id),
    product_id  UUID         NOT NULL REFERENCES products (id),
    kind        VARCHAR(20)  NOT NULL CHECK (kind IN ('GIVE_BACK', 'REPORT')),
    -- In the product's stock unit.
    quantity    INTEGER      NOT NULL CHECK (quantity > 0),
    reason      VARCHAR(20)  CHECK (reason IN ('SPOILT', 'EXPIRED', 'DAMAGED', 'OTHER')),
    note        VARCHAR(500),
    movement_id UUID         REFERENCES stock_movements (id),
    created_by  UUID         REFERENCES users (id) ON DELETE SET NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_area_move_returns_reason CHECK ((kind = 'REPORT') = (reason IS NOT NULL))
);

CREATE INDEX idx_area_move_returns_move ON area_move_returns (move_id, product_id);

-- Given back or reported: to the area it came from and the owner.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED', 'TRANSFER_ON_THE_WAY', 'TRANSFER_RECEIVED_SHORT', 'AREA_MOVE_WAITING',
    'AREA_MOVE_SHORT', 'STOCK_REQUEST', 'STOCK_REQUEST_ANSWERED', 'AREA_RETURN'));
