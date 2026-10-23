-- Missing stock that turns up (MULTI_BRANCH_UX_PLAN.md section 5.7: "Found in the van"). It goes on the
-- shelf of the branch where it was found, as a TRANSFER_IN lot carrying the cost and supplier of the
-- delivery it came from, and leaves the line's missing figure. A line is accounted for when received +
-- missing + damaged + found reaches what was sent.
ALTER TABLE stock_transfer_lines ADD COLUMN quantity_found INTEGER NOT NULL DEFAULT 0 CHECK (quantity_found >= 0);

ALTER TABLE stock_transfer_lines DROP CONSTRAINT chk_stock_transfer_lines_accounted;
ALTER TABLE stock_transfer_lines ADD CONSTRAINT chk_stock_transfer_lines_accounted
    CHECK (quantity_received + quantity_missing + quantity_damaged + quantity_found <= quantity_sent);
