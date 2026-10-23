-- Demo/local seed only - the other half of V8999. The demo seeds' movements and stocked products get
-- the branch V51 gives everything else, and stock_movements.branch_id is NOT NULL again.
INSERT INTO branches (client_id, name, is_default, is_active)
SELECT c.id, 'Head Office', TRUE, TRUE
FROM clients c
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.client_id = c.id)
ON CONFLICT (client_id, name) DO NOTHING;

UPDATE stock_movements m
SET branch_id = b.id
FROM branches b
WHERE b.client_id = m.client_id AND b.is_default AND m.branch_id IS NULL;

INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
SELECT p.client_id, b.id, p.id, p.quantity_on_hand
FROM products p
JOIN branches b ON b.client_id = p.client_id AND b.is_default
WHERE p.quantity_on_hand <> 0
ON CONFLICT (branch_id, product_id) DO NOTHING;

ALTER TABLE stock_movements ALTER COLUMN branch_id SET NOT NULL;
