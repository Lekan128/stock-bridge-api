-- Multiple branches, Phase 4 (MULTI_BRANCH_BUILD_PLAN.md section 9): Avatar C at scale, for timing.
-- 26 branches x 20,000 products, 60% stocked at each branch (~312k branch_stock rows), ~2M movements
-- over a year. Run against a company made by signup (its main branch already exists):
--   psql -v client_id="'<uuid>'" -f scripts/seed-chain-scale.sql
-- Local databases only: it writes a lot and is not undone.
\set ON_ERROR_STOP on
\timing on

INSERT INTO branches (client_id, name, is_default, is_active)
SELECT :client_id, 'Lekki Phase ' || g || ' Shopping Complex, Branch ' || g, FALSE, TRUE
FROM generate_series(2, 26) g;

INSERT INTO products (client_id, name, sku, quantity_on_hand, low_stock_threshold, unit_of_measure, is_active,
                      cost_price, unit_price)
SELECT :client_id,
       (array['Rice','Beans','Garri','Palm oil','Sugar','Salt','Flour','Milk','Soap','Noodles'])[1 + g % 10]
           || ' ' || lpad(g::text, 5, '0'),
       'CH-' || lpad(g::text, 5, '0'), 0, 20, 'PIECE', TRUE,
       100 + (g % 50) * 10, 150 + (g % 50) * 15
FROM generate_series(1, 20000) g;

-- 60% of products stocked at each branch; a few below their level, a few out.
INSERT INTO branch_stock (client_id, branch_id, product_id, quantity_on_hand)
SELECT :client_id, b.id, p.id,
       CASE WHEN abs(hashtext(p.id::text || b.id::text)) % 10 = 0 THEN 0
            ELSE 5 + abs(hashtext(b.id::text || p.id::text)) % 200 END
FROM branches b
JOIN products p ON p.client_id = b.client_id
WHERE b.client_id = :client_id AND abs(hashtext(p.id::text || b.id::text)) % 100 < 60;

UPDATE products p SET quantity_on_hand = s.total
FROM (SELECT product_id, SUM(quantity_on_hand) AS total FROM branch_stock WHERE client_id = :client_id GROUP BY product_id) s
WHERE p.id = s.product_id;

-- ~2M movements: about 6.4 per stocked branch figure, deliveries and sales over the last year.
INSERT INTO stock_movements (client_id, product_id, branch_id, movement_type, quantity, unit_price_at_time,
                             occurred_at, created_at)
SELECT bs.client_id, bs.product_id, bs.branch_id,
       CASE WHEN k % 3 = 0 THEN 'IN' ELSE 'OUT' END,
       1 + (k * 7) % 20,
       100 + (k % 50) * 10,
       now() - ((k * 37 + abs(hashtext(bs.product_id::text)) % 360) % 365) * interval '1 day',
       now()
FROM branch_stock bs
CROSS JOIN generate_series(1, 6) k
WHERE bs.client_id = :client_id;

ANALYZE products;
ANALYZE branch_stock;
ANALYZE stock_movements;
