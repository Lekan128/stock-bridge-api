-- Purchase orders (PURCHASE_ORDER_PLAN.md, phase 1).
--
-- A purchase order is an expected delivery that has a number and a frozen copy of what the
-- supplier was shown. It is the SAME row, not a second table: one list of "what we ordered", one
-- "coming" figure, one receive path. Rows recorded the old way (typed from a phone call, never
-- sent) leave all four columns empty and carry on exactly as before.
ALTER TABLE expected_deliveries
    -- "PO-0007". Per company; null for an expectation that was only written down.
    ADD COLUMN po_number   VARCHAR(20),
    -- The 7 in PO-0007, kept as a number so the next one is MAX+1 and never parsed out of text.
    ADD COLUMN po_sequence INTEGER,
    -- Starts at 1. Changing a sent order makes revision 2 (plan 4.4); the number does not change.
    ADD COLUMN revision    INTEGER NOT NULL DEFAULT 1,
    -- Everything the PDF and the public page show, as JSON, written once when the order is made
    -- (and rewritten for a revision). The PDF is generated from this on the fly and never stored,
    -- so reprinting next month gives the same page even if the product was renamed meanwhile.
    ADD COLUMN snapshot    TEXT,
    ADD CONSTRAINT chk_expected_deliveries_po_coherent
        CHECK ((po_number IS NULL AND po_sequence IS NULL AND snapshot IS NULL)
            OR (po_number IS NOT NULL AND po_sequence IS NOT NULL AND snapshot IS NOT NULL)),
    ADD CONSTRAINT chk_expected_deliveries_revision_positive CHECK (revision >= 1);

-- Two orders of one company can never share a number, whatever races the allocator.
CREATE UNIQUE INDEX uq_expected_deliveries_po_sequence
    ON expected_deliveries (client_id, po_sequence)
    WHERE po_sequence IS NOT NULL;
