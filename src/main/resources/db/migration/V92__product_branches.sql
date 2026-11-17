-- Which branches a product is for (owner, 2026-11-17): "from now a product should have the branch it should show
-- up on", and a product not for a branch does not show there at all - not in Inventory, the till, counts,
-- deliveries or orders. New products default to the branch they were added at; "All branches" is one tap away.

ALTER TABLE products
    -- For every branch, now and any opened later.
    ADD COLUMN for_all_branches BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE product_branches (
    product_id UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    branch_id  UUID NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, branch_id)
);
CREATE INDEX idx_product_branches_branch ON product_branches (client_id, branch_id);

-- Existing products: the branches where they have had stock or anything recorded...
INSERT INTO product_branches (product_id, branch_id, client_id)
SELECT DISTINCT bs.product_id, bs.branch_id, bs.client_id FROM branch_stock bs
ON CONFLICT DO NOTHING;
INSERT INTO product_branches (product_id, branch_id, client_id)
SELECT DISTINCT m.product_id, m.branch_id, m.client_id FROM stock_movements m WHERE m.branch_id IS NOT NULL
ON CONFLICT DO NOTHING;
-- ...and a product that has never had any: the company's main branch (or its only one).
INSERT INTO product_branches (product_id, branch_id, client_id)
SELECT p.id,
       (SELECT b.id FROM branches b WHERE b.client_id = p.client_id ORDER BY b.is_default DESC, b.created_at LIMIT 1),
       p.client_id
FROM products p
WHERE NOT EXISTS (SELECT 1 FROM product_branches pb WHERE pb.product_id = p.id)
  AND EXISTS (SELECT 1 FROM branches b WHERE b.client_id = p.client_id)
ON CONFLICT DO NOTHING;

-- A change of branches is a change to the product, for the phones' copies (V37's feed).
CREATE OR REPLACE FUNCTION product_branches_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM log_product_change(OLD.client_id, OLD.product_id);
        RETURN OLD;
    END IF;
    PERFORM log_product_change(NEW.client_id, NEW.product_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_branches_log_change
    AFTER INSERT OR DELETE ON product_branches
    FOR EACH ROW EXECUTE FUNCTION product_branches_log_change();
