-- The barcode on the box (BULK_IMPORT_CX_PLAN.md task 3.3). Scanning is only worth anything if a
-- scan lands on exactly one product, so the column comes with the constraint that makes that true.
ALTER TABLE products
    ADD COLUMN barcode VARCHAR(64);

-- One product per barcode within a company, but only where there is one: the partial index lets
-- any number of products carry no barcode at all, which is the normal state of most catalogs.
-- Not global: two companies stocking the same tin of milk each have their own product row, and a
-- shared uniqueness rule would make the second one to type it lose.
CREATE UNIQUE INDEX uq_products_client_barcode
    ON products (client_id, barcode)
    WHERE barcode IS NOT NULL;
