-- Selling till Step 13 (owner, 2026-11-18): close the day. Cash only - transfers and POS land in the company's
-- accounts. A switch the owner turns on ("Count cash at open and close"), off by default. A day belongs to a drawer:
-- the cash at one area of a branch. It starts with the cash counted into the drawer, takes money out with a reason,
-- and closes with a blind count; over or short is for whoever can see sales profit.
ALTER TABLE clients ADD COLUMN counts_cash BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE cash_days (
    id             UUID PRIMARY KEY,
    client_id      UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id      UUID           NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    -- The drawer's area; null at a branch with one place.
    area_id        UUID           REFERENCES areas (id) ON DELETE CASCADE,
    opened_by      UUID           REFERENCES users (id) ON DELETE SET NULL,
    opened_at      TIMESTAMPTZ    NOT NULL,
    float_amount   NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (float_amount >= 0),
    -- "No cash yet": started at nothing, said so at close.
    float_skipped  BOOLEAN        NOT NULL DEFAULT FALSE,
    -- A sale after a close reopens the drawer from what was counted.
    reopened_from  UUID           REFERENCES cash_days (id) ON DELETE SET NULL,
    closed_by      UUID           REFERENCES users (id) ON DELETE SET NULL,
    closed_at      TIMESTAMPTZ,
    counted        NUMERIC(14, 2) CHECK (counted IS NULL OR counted >= 0),
    -- Worked out at close: float + cash sales - money out.
    cash_sales     NUMERIC(14, 2),
    money_out      NUMERIC(14, 2),
    expected       NUMERIC(14, 2),
    recounted_at   TIMESTAMPTZ,
    -- The owner's one email, sent a while after the close so a recount goes in it.
    email_sent_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_cash_days_open_drawer
    ON cash_days (branch_id, COALESCE(area_id, '00000000-0000-0000-0000-000000000000'::uuid)) WHERE closed_at IS NULL;
CREATE INDEX idx_cash_days_client ON cash_days (client_id, opened_at DESC);
CREATE INDEX idx_cash_days_email ON cash_days (closed_at) WHERE email_sent_at IS NULL AND closed_at IS NOT NULL;

CREATE TABLE cash_money_out (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    cash_day_id UUID           NOT NULL REFERENCES cash_days (id) ON DELETE CASCADE,
    amount      NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    -- A cost (diesel, ice), to the bank, to the owner, or other.
    kind        VARCHAR(10)    NOT NULL CHECK (kind IN ('COST', 'BANK', 'OWNER', 'OTHER')),
    note        VARCHAR(300),
    created_by  UUID           REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_cash_money_out_day ON cash_money_out (cash_day_id);
