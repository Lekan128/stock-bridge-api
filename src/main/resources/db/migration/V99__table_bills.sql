-- Selling till Stage C, Group A (SELLING_TILL_PLAN.md Steps 7 and 9; owner, 2026-11-19): tables and bills.
--
-- A bill is a sale kept open for a table (or a name) and paid later. Its id IS the sale id its lines carry: "Send"
-- records each item as an ordinary sale line (stock leaves then, from the area that makes or keeps it), with no
-- payment yet; "Pay" settles it. Until then the sale stays out of Sales and the drawers.

-- A branch's tables: named freely ("4" renamed "Window", "VIP"), in the owner's order, switched off - never deleted,
-- so an old bill keeps its table.
CREATE TABLE branch_tables (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id  UUID        NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    name       VARCHAR(40) NOT NULL CONSTRAINT chk_branch_tables_name CHECK (btrim(name) <> ''),
    sort_order INT         NOT NULL DEFAULT 0,
    is_active  BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_branch_tables_name ON branch_tables (branch_id, lower(name));

CREATE TABLE bills (
    -- Also the sale id of every line sent to it.
    id        UUID PRIMARY KEY,
    client_id UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id UUID        NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    table_id  UUID REFERENCES branch_tables (id),
    -- A bill for a name instead of a table: a bar tab, a takeaway.
    name      VARCHAR(60),
    status    VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'CANCELLED')),
    opened_by UUID REFERENCES users (id) ON DELETE SET NULL,
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by UUID REFERENCES users (id) ON DELETE SET NULL,
    closed_at TIMESTAMPTZ,
    CONSTRAINT chk_bills_for CHECK (table_id IS NOT NULL OR btrim(coalesce(name, '')) <> '')
);
-- One open bill per table.
CREATE UNIQUE INDEX uq_bills_open_table ON bills (table_id) WHERE status = 'OPEN';
CREATE INDEX idx_bills_open ON bills (branch_id) WHERE status = 'OPEN';

-- What a sent line is on its bill: the phone's key for it, the meal a side goes with, its note for the kitchen, and
-- whether it was taken off (NOT_MADE: reversed, stock back; SENT_BACK: stock stays used, not charged).
CREATE TABLE bill_lines (
    movement_id    UUID PRIMARY KEY REFERENCES stock_movements (id) ON DELETE CASCADE,
    bill_id        UUID        NOT NULL REFERENCES bills (id) ON DELETE CASCADE,
    client_id      UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    line_key       UUID        NOT NULL,
    with_key       UUID,
    note           VARCHAR(200),
    sent_by        UUID REFERENCES users (id) ON DELETE SET NULL,
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    taken_off      VARCHAR(10) CHECK (taken_off IN ('NOT_MADE', 'SENT_BACK')),
    taken_off_note VARCHAR(200),
    taken_off_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    taken_off_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_bill_lines_key ON bill_lines (bill_id, line_key);
CREATE INDEX idx_bill_lines_bill ON bill_lines (bill_id);

-- How a bill was paid, into which drawer (the area of the till that took it). One row for a whole bill; several
-- once a bill can be split (Step 11).
CREATE TABLE bill_payments (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id          UUID          NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    bill_id            UUID          NOT NULL REFERENCES bills (id) ON DELETE CASCADE,
    branch_id          UUID          NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    area_id            UUID REFERENCES areas (id),
    amount             NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    method             VARCHAR(16)   NOT NULL CHECK (method IN ('CASH', 'TRANSFER', 'CARD')),
    payment_account_id UUID REFERENCES payment_accounts (id),
    paid_by            UUID REFERENCES users (id) ON DELETE SET NULL,
    paid_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_bill_payments_branch ON bill_payments (branch_id, paid_at);

-- Sides (Step 9): a category the owner marks "These go with a meal" - the till asks which meal each one is for.
ALTER TABLE company_categories ADD COLUMN goes_with_meal BOOLEAN NOT NULL DEFAULT FALSE;
