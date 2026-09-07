-- Tracks whether a marketplace order line's buyer-side product row was freshly created by
-- IncomingStockService.materialize() (no source-product or SKU match found) rather than matched
-- to something the buyer already had. Default FALSE for existing rows: a historical order should
-- never suddenly prompt "is this the same as X?" on a delivery that's long since been received.
ALTER TABLE order_items ADD COLUMN buyer_product_newly_created BOOLEAN NOT NULL DEFAULT FALSE;
