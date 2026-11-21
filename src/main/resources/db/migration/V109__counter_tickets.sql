-- Counter sales send kitchen tickets too (owner, 2026-11-21: "some counter orders can go to the kitchen while others
-- already have it portioned out"). With "Kitchen and bar tickets" on, a counter sale sends a ticket for each item made
-- in another area than the till's (a plate made in the Kitchen); what the till's own area holds or makes (portioned at
-- the front desk) is handed over and sends nothing. Sides go with a meal on the counter as on a bill.

-- A counter sale's line as the kitchen sees it: its key, the meal a side goes with, a note. Bill lines have their own.
CREATE TABLE counter_lines (
    movement_id UUID PRIMARY KEY REFERENCES stock_movements (id) ON DELETE CASCADE,
    sale_id     UUID         NOT NULL,
    client_id   UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    line_key    UUID         NOT NULL,
    with_key    UUID,
    note        VARCHAR(200),
    sent_by     UUID REFERENCES users (id) ON DELETE SET NULL,
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_counter_lines_sale ON counter_lines (sale_id);

-- A ticket is from a bill or from a counter sale.
ALTER TABLE tickets ALTER COLUMN bill_id DROP NOT NULL;
ALTER TABLE tickets ADD COLUMN sale_id UUID;
ALTER TABLE tickets ADD CONSTRAINT chk_tickets_from CHECK (bill_id IS NOT NULL OR sale_id IS NOT NULL);
CREATE UNIQUE INDEX uq_tickets_counter_send ON tickets (sale_id, area_id, send_key) WHERE bill_id IS NULL;

-- Every line a ticket can carry, from a bill or the counter, read the same way by the Tickets page. owner_id is the
-- bill or the counter sale; only bill lines can be taken off (a counter sale is cancelled instead).
CREATE VIEW ticketed_lines AS
SELECT movement_id, bill_id AS owner_id, line_key, with_key, note, sent_at, taken_off, taken_off_by, taken_off_at
FROM bill_lines
UNION ALL
SELECT movement_id, sale_id, line_key, with_key, note, sent_at, NULL::VARCHAR(10), NULL::UUID, NULL::TIMESTAMPTZ
FROM counter_lines;
