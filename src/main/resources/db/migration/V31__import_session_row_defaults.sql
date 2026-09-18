-- One delivery has one date, one invoice and, usually, one supplier. The stock sheet used to ask for
-- them on every row; the upload screen now asks once (BULK_IMPORT_CX_PLAN.md task 1.5), and the
-- answers are kept here, keyed by import field ("received_date", "waybill_or_invoice_no",
-- "vendor_name"), to fill any row that leaves that cell blank. Null for an import with none.
ALTER TABLE import_sessions ADD COLUMN row_defaults JSONB;
