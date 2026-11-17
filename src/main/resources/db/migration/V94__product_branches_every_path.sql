-- Product branches (V92) for every way a product or its stock comes in - a bulk import, a marketplace delivery, a
-- demo reset - not only the product form.

-- Stock arriving at a branch makes the product for that branch: a waybill, a delivery, an import's opening stock.
CREATE OR REPLACE FUNCTION branch_stock_adds_product_branch() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO product_branches (product_id, branch_id, client_id)
    SELECT NEW.product_id, NEW.branch_id, NEW.client_id
    FROM products p WHERE p.id = NEW.product_id AND NOT p.for_all_branches
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_branch_stock_adds_product_branch
    AFTER INSERT ON branch_stock
    FOR EACH ROW EXECUTE FUNCTION branch_stock_adds_product_branch();

-- A product saved without saying where it's for: at commit, the branch being worked at isn't known here, so the
-- company's main branch (the only one, at a one-branch company). Never leaves a product showing nowhere.
CREATE OR REPLACE FUNCTION product_gets_a_branch() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM products p WHERE p.id = NEW.id) THEN
        RETURN NULL; -- deleted in the same transaction
    END IF;
    IF NOT (SELECT for_all_branches FROM products WHERE id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM product_branches WHERE product_id = NEW.id) THEN
        INSERT INTO product_branches (product_id, branch_id, client_id)
        SELECT NEW.id, b.id, NEW.client_id FROM branches b
        WHERE b.client_id = NEW.client_id
        ORDER BY b.is_default DESC, b.created_at
        LIMIT 1
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_product_gets_a_branch
    AFTER INSERT ON products
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION product_gets_a_branch();
