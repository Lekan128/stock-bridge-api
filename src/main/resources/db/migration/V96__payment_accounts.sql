-- Selling till Step 12 (owner, 2026-11-18): the accounts each branch's customers pay into by transfer. The cashier
-- taps how they were paid once the money is in; with two or more accounts, which one it came into. Receipts never
-- show an account number (an old receipt must not send money to a closed account). Switched off, never deleted.
CREATE TABLE payment_accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id      UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id      UUID         NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    bank_name      VARCHAR(80)  NOT NULL,
    account_name   VARCHAR(120),
    account_number VARCHAR(20)  NOT NULL,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_payment_accounts_bank CHECK (btrim(bank_name) <> ''),
    CONSTRAINT chk_payment_accounts_number CHECK (btrim(account_number) <> '')
);
CREATE INDEX idx_payment_accounts_branch ON payment_accounts (branch_id);
CREATE UNIQUE INDEX uq_payment_accounts_branch_number ON payment_accounts (branch_id, bank_name, account_number);

-- Which account a transfer sale came into.
ALTER TABLE sales ADD COLUMN payment_account_id UUID REFERENCES payment_accounts (id);
