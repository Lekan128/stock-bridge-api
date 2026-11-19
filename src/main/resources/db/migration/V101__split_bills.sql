-- Selling till Stage C, Group B (SELLING_TILL_PLAN.md Step 11; owner, 2026-11-19): paying a bill in parts.
--
-- A bill paid more than one way is a SPLIT sale: its parts are in bill_payments (each into its own drawer or account),
-- and Sales shares its food and drinks across the ways it was paid, in proportion to each part.
ALTER TABLE sales DROP CONSTRAINT IF EXISTS sales_payment_method_check;
ALTER TABLE sales ADD CONSTRAINT sales_payment_method_check
    CHECK (payment_method IN ('CASH', 'TRANSFER', 'CARD', 'SPLIT'));
