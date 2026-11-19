-- Close the day (selling till Step 13; owner, 2026-11-18): how long after a close a drawer can be opened again from
-- what was counted. After it, the till asks for a fresh start count - a new day. The owner sets it; 4 hours to start.
ALTER TABLE clients ADD COLUMN cash_reopen_hours SMALLINT NOT NULL DEFAULT 4
    CONSTRAINT chk_clients_cash_reopen_hours CHECK (cash_reopen_hours BETWEEN 1 AND 24);
