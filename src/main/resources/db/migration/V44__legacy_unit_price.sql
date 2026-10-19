-- Clears products.unit_price on buying companies' products, keeping every value
-- in a new column instead (SELLING_PRICE_PLAN.md section 4.6, Phase 0).
--
-- ============================================================================
-- WHY
-- ============================================================================
-- unit_price is the SELLING price. A company is about to be able to give its
-- products one (a shop that sells some of its stock), and the moment it can,
-- every value already in this column on a company's product is shown, used to
-- pre-fill a sale and counted in profit AS A SELLING PRICE. None of the values
-- there today is one the company chose as such:
--
--   1. A product created by receiving a marketplace order was given
--      unit_price = what the buyer PAID (IncomingStockService, fixed alongside
--      this migration). That is a cost. Shown as a selling price it is a
--      product that sells for exactly what it cost: zero profit on every sale.
--   2. Before V17 the column was NOT NULL, so every company product created
--      then carries a "unit price" typed on a form that never said whether it
--      meant cost or selling price. Nobody can tell the two apart afterwards.
--   3. Since V17 the product service has discarded a company's unit_price, so
--      nothing else should be there - but 1. and 2. are enough.
--
-- ============================================================================
-- WHAT IT DOES, AND WHAT IT LEAVES ALONE
-- ============================================================================
-- Moves, never deletes: each such value is copied into legacy_unit_price
-- before unit_price is cleared, so the owner can review them and adopt the
-- ones that really were selling prices (plan section 4.6.3). Nothing is lost.
--
-- Only buying companies: client_type = 'COMPANY' AND NOT is_platform_owner.
-- Vendors and ProcurePal list on the marketplace; their unit_price is the
-- price a buyer pays at checkout and is exactly right as it is.
--
-- Each updated product is written to product_changes by its existing trigger
-- (V37), so phones holding the on-device catalogue receive the cleared price
-- on their next sync rather than keeping the old one.
--
-- updated_at moves for those rows (V1's trigger). That is the truth - the row
-- did change - and nothing orders or filters company products by it.
ALTER TABLE products
    ADD COLUMN legacy_unit_price NUMERIC(14, 2);

UPDATE products p
SET legacy_unit_price = p.unit_price,
    unit_price        = NULL
FROM clients c
WHERE p.client_id = c.id
  AND c.client_type = 'COMPANY'
  AND NOT c.is_platform_owner
  AND p.unit_price IS NOT NULL;

COMMENT ON COLUMN products.legacy_unit_price IS
    'A buying company''s unit_price from before it could have a selling price '
    '(V44): either the purchase price copied in by a marketplace receipt or a '
    'pre-V17 "unit price" of unknown meaning. Kept for the owner to review and '
    'adopt as selling prices; never read as one. Null for everything else.';
