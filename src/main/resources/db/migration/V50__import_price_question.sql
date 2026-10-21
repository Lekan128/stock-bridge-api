-- The one question a product sheet can raise about its price column
-- (SELLING_PRICE_PLAN.md sections 5.3, 6.8).
--
-- PRICE_MEANING: a company's sheet has a bare "Price" / "Unit price" / "Rate"
-- column, which could be what they pay or what they sell for. It no longer maps
-- by itself; the review asks, and the file cannot be imported until it is
-- answered.
-- KEEP_SELLING_PRICES: a company that does not record sales uploads a sheet with
-- a selling-price column. The owner is asked whether to keep the prices (which
-- turns selling on); anyone else is told the column will be ignored, and why.
--
-- The header the question is about is kept beside it. Both are cleared when the
-- question is answered.
ALTER TABLE import_sessions
    ADD COLUMN price_question_kind VARCHAR(30),
    ADD COLUMN price_question_header VARCHAR(255),
    ADD CONSTRAINT chk_import_sessions_price_question_kind
        CHECK (price_question_kind IS NULL OR price_question_kind IN ('PRICE_MEANING', 'KEEP_SELLING_PRICES'));
