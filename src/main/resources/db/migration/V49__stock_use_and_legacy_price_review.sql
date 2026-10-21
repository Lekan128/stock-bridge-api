-- What a company does with its stock, and the review of its old prices
-- (SELLING_PRICE_PLAN.md sections 4.6.3, 5.1).
--
-- stock_use: the answer to the first-week question "What happens to your
-- stock?" - USE (make or serve things with it), SELL (sell it as it is) or BOTH.
-- Kept so the question is asked once, on any phone, rather than once per
-- device; and because who uses and who sells is the most useful thing the
-- product can know about a customer. SELL and BOTH also turn sells_stock on
-- (the service does that and logs it with source FIRST_WEEK); USE changes
-- nothing else. Null until answered.
ALTER TABLE clients
    ADD COLUMN stock_use VARCHAR(10),
    ADD CONSTRAINT chk_clients_stock_use CHECK (stock_use IS NULL OR stock_use IN ('USE', 'SELL', 'BOTH'));

-- When the owner said "not now" to reviewing the old prices V44 set aside. The
-- prices themselves stay in legacy_unit_price - nothing is deleted by a
-- dismissal - this only stops the offer coming back.
ALTER TABLE clients
    ADD COLUMN legacy_prices_dismissed_at TIMESTAMPTZ;

-- An old price adopted as a selling price is a price change like any other,
-- and is logged as one.
ALTER TABLE product_price_changes DROP CONSTRAINT chk_product_price_changes_source;
ALTER TABLE product_price_changes ADD CONSTRAINT chk_product_price_changes_source
    CHECK (source IN ('FORM', 'IMPORT', 'IMPORT_UNDO', 'LEGACY_REVIEW'));
