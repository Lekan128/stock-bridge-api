-- ProcurePal marketplace demo/local seed - NOT a real schema migration.
--
-- ============================================================================
-- Why this is a new file instead of extra statements in V9000
-- ============================================================================
-- V9000 has already been applied on every existing local database (it is the
-- oldest seed and, being versioned V9000, it ran before V5 and V6 arrived - see
-- spring.flyway.out-of-order in application-local.yml). Appending to it would do
-- two bad things at once:
--   1. change its checksum, so every already-seeded local database refuses to
--      start until someone runs a Flyway repair, and
--   2. still not deliver any of the data, because Flyway never re-runs an
--      applied migration. The marketplace catalog would exist only on databases
--      created from scratch after the edit.
-- A sibling file at the next version solves both: it applies cleanly to a fresh
-- database (V1..V6, then V9000, then this) and to an existing one (V6 arrives
-- out of order, then this), and V9000's checksum is untouched.
--
-- Same prod-safety guarantee as V9000: db/seed is only on spring.flyway.locations
-- in the local and docker profiles. application-prod.yml never references it, so
-- nothing here can reach a production database.
--
-- Every statement is written to be re-runnable and to tolerate a database where
-- some of this already exists, so a partially-hand-seeded local database does not
-- brick the migration.
--
-- ============================================================================
-- Demo login credentials added by this file (see the root APP_TOUR.md):
--
--   Client identifier: procurepal   <- THE MARKETPLACE OPERATOR
--   Username: admin    Password: Demo1234!   (role OWNER, the root user)
--
-- The existing 'demo' tenant (admin/manager/staff/stock/finance, all Demo1234!)
-- is left exactly as it was and is the BUYER in the marketplace story.
--
-- The password hash below is the same bcrypt("Demo1234!") V9000 uses, so the
-- whole demo has one password.
-- ============================================================================

-- ============================================================================
-- Head Office branches for any client that does not have one yet.
--
-- V6 backfills branches for every client that exists WHEN IT RUNS. On a fresh
-- database V6 runs before this seed creates the demo tenants, so those tenants
-- would otherwise have no branch at all. This closes that ordering gap for both
-- orderings, and is a no-op where V6 (or ClientSignupService) already did it.
-- ============================================================================
INSERT INTO branches (client_id, name, is_default, is_active)
SELECT c.id, 'Head Office', TRUE, TRUE
FROM clients c
WHERE NOT EXISTS (SELECT 1 FROM branches b WHERE b.client_id = c.id)
ON CONFLICT (client_id, name) DO NOTHING;

-- ============================================================================
-- ProcurePal: the platform owner. Modelled as an ordinary tenant with the flag
-- set, so it also uses the normal inventory/analytics screens for its own stock.
--
-- Guarded by "no platform owner exists yet" rather than ON CONFLICT: the partial
-- unique index uq_clients_single_platform_owner would reject a second one
-- outright, and failing a seed with a constraint violation is a worse experience
-- than doing nothing.
-- ============================================================================
INSERT INTO clients (name, slug, admin_contact_email, phone, is_platform_owner, payment_terms, is_active)
SELECT 'ProcurePal', 'procurepal', 'ops@procurepal.ng', '+234 800 000 0000', TRUE, 'PREPAID', TRUE
WHERE NOT EXISTS (SELECT 1 FROM clients WHERE is_platform_owner)
  AND NOT EXISTS (SELECT 1 FROM clients WHERE slug = 'procurepal');

INSERT INTO branches (client_id, name, is_default, is_active)
SELECT c.id, 'Head Office', TRUE, TRUE
FROM clients c
WHERE c.slug = 'procurepal'
ON CONFLICT (client_id, name) DO NOTHING;

-- 'admin' is seeded as the root user for the same reason V9000 does it for the
-- demo tenant: it stands in for the account creator ClientSignupService would
-- normally flag.
INSERT INTO users (client_id, username, password_hash, role_id, is_root, first_name, last_name, email, job_title)
SELECT c.id, v.username, '$2a$10$kTBnKCWpk/bdB.G8xq5KK.MIhANye.rDwoXrlAQhF2sf9hrBjZqVm', r.id,
       v.is_root, v.first_name, v.last_name, v.email, v.job_title
FROM (VALUES
    ('admin',      'OWNER',               TRUE,  'Tunde',  'Balogun', 'ops@procurepal.ng',        'Operations Lead'),
    ('fulfilment', 'PROCUREMENT_MANAGER', FALSE, 'Ngozi',  'Eze',     'fulfilment@procurepal.ng', 'Fulfilment Manager')
) AS v(username, role_name, is_root, first_name, last_name, email, job_title)
JOIN clients c ON c.slug = 'procurepal'
JOIN roles r ON r.name = v.role_name
ON CONFLICT (client_id, username) DO NOTHING;

-- ============================================================================
-- Catalog categories. Six broad buckets covering what a Nigerian restaurant,
-- hotel, school or office actually reorders week to week - deliberately shaped
-- around buying habits rather than around a product taxonomy, so the storefront's
-- category chips are useful on the first visit.
-- ============================================================================
INSERT INTO product_categories (name, slug, sort_order, is_active) VALUES
    ('Grains & Staples',        'grains-staples',        1, TRUE),
    ('Cooking Oils & Fats',     'cooking-oils',          2, TRUE),
    ('Beverages',               'beverages',             3, TRUE),
    ('Cleaning & Hygiene',      'cleaning-hygiene',      4, TRUE),
    ('Packaging & Disposables', 'packaging-disposables', 5, TRUE),
    ('Kitchen Equipment',       'kitchen-equipment',     6, TRUE)
ON CONFLICT (slug) DO NOTHING;

-- ============================================================================
-- The marketplace catalog: 34 listed products at realistic 2026 NGN wholesale
-- prices, in the units these goods are genuinely traded in (a 50kg bag of rice,
-- a 25L keg of oil, a carton of 24). Minimum order quantities are set where the
-- trade actually imposes one - nobody sells a single 75cl bottle of table water
-- to a hotel, hence the MOQ of 10 cartons.
--
-- Two deliberate stock states so the storefront's edge cases are visible on
-- first run without anyone having to construct them:
--   PP-KE-002 is at zero  -> "out of stock, shown but not purchasable"
--   PP-CO-003 is under its low-stock threshold -> ProcurePal's own low-stock banner
-- ============================================================================
INSERT INTO products (
    client_id, category_id, name, sku, slug, description, brand, unit_of_measure,
    unit_price, cost_price, quantity_on_hand, low_stock_threshold,
    min_order_quantity, is_marketplace_listed, is_active)
SELECT c.id, cat.id, v.name, v.sku, v.slug, v.description, v.brand, v.unit_of_measure,
       v.unit_price, v.cost_price, v.quantity_on_hand, v.low_stock_threshold,
       v.min_order_quantity, TRUE, TRUE
FROM (VALUES
    -- Grains & Staples
    ('PP-GS-001', 'Dangote Parboiled Rice 50kg', 'dangote-parboiled-rice-50kg',
     'Long-grain parboiled rice, double-sifted and stone-free. The default choice for canteens and event caterers.',
     'Dangote', 'bag (50kg)', 92000, 78000, 240, 40, 1, 'grains-staples'),
    ('PP-GS-002', 'Mama Gold Parboiled Rice 50kg', 'mama-gold-parboiled-rice-50kg',
     'Premium parboiled long grain rice, uniform grain, low broken content.',
     'Mama Gold', 'bag (50kg)', 88500, 75500, 180, 40, 1, 'grains-staples'),
    ('PP-GS-003', 'Royal Stallion Parboiled Rice 50kg', 'royal-stallion-parboiled-rice-50kg',
     'Well-milled parboiled rice with consistent grain length. Cooks up separate, popular for jollof in volume.',
     'Royal Stallion', 'bag (50kg)', 86000, 73000, 150, 30, 1, 'grains-staples'),
    ('PP-GS-004', 'Honeywell Semolina 10kg', 'honeywell-semolina-10kg',
     'Fine-textured semolina flour, turns smooth without lumps. 10kg refill sack.',
     'Honeywell', 'bag (10kg)', 18900, 15800, 120, 24, 2, 'grains-staples'),
    ('PP-GS-005', 'Golden Penny Semovita 10kg', 'golden-penny-semovita-10kg',
     'Vitamin-enriched semovita, 10kg sack. Steady seller for staff canteens.',
     'Golden Penny', 'bag (10kg)', 17900, 15000, 140, 24, 2, 'grains-staples'),
    ('PP-GS-006', 'Oloyin Brown Beans 50kg', 'oloyin-brown-beans-50kg',
     'Honey beans, hand-sorted and weevil-treated. Sweet finish and a short cook time.',
     NULL, 'bag (50kg)', 108000, 92000, 60, 12, 1, 'grains-staples'),
    ('PP-GS-007', 'Ijebu Garri (White) 50kg', 'ijebu-garri-white-50kg',
     'Sour Ijebu garri, finely sieved and low moisture. Soaks without going soggy.',
     NULL, 'bag (50kg)', 48500, 40000, 90, 20, 1, 'grains-staples'),
    ('PP-GS-008', 'Golden Penny Spaghetti 500g', 'golden-penny-spaghetti-500g',
     'Durum wheat spaghetti, 20 x 500g packs per carton.',
     'Golden Penny', 'carton (20)', 12400, 10400, 200, 40, 2, 'grains-staples'),
    ('PP-GS-009', 'Dangote Refined Sugar 50kg', 'dangote-refined-sugar-50kg',
     'Granulated refined white sugar, food grade, 50kg sack.',
     'Dangote', 'bag (50kg)', 79500, 68000, 110, 20, 1, 'grains-staples'),

    -- Cooking Oils & Fats
    ('PP-CO-001', 'Kings Vegetable Oil 25L', 'kings-vegetable-oil-25l',
     'Cholesterol-free refined vegetable oil in a factory-sealed 25 litre keg.',
     'Kings', 'keg (25L)', 73500, 63000, 90, 15, 1, 'cooking-oils'),
    ('PP-CO-002', 'Devon King''s Cooking Oil 25L', 'devon-kings-cooking-oil-25l',
     'Fortified vegetable cooking oil, neutral taste and a high smoke point.',
     'Devon King''s', 'keg (25L)', 71000, 61000, 75, 15, 1, 'cooking-oils'),
    ('PP-CO-003', 'Refined Palm Oil 25L', 'refined-palm-oil-25l',
     'Bleached and deodorised red palm oil, sediment-free, 25 litre keg.',
     NULL, 'keg (25L)', 59500, 50000, 9, 12, 1, 'cooking-oils'),
    ('PP-CO-004', 'Simas Margarine 2.5kg', 'simas-margarine-2-5kg',
     'Baking margarine for pastry and bread, 4 x 2.5kg tubs per carton.',
     'Simas', 'carton (4)', 43500, 37000, 50, 10, 1, 'cooking-oils'),

    -- Beverages
    ('PP-BV-001', 'Coca-Cola 50cl PET', 'coca-cola-50cl-pet',
     'Chilled-ready 50cl PET bottles, 12 per crate.',
     'Coca-Cola', 'crate (12)', 5200, 4300, 300, 60, 5, 'beverages'),
    ('PP-BV-002', 'Eva Table Water 75cl', 'eva-table-water-75cl',
     'NAFDAC-registered bottled table water, 12 x 75cl per carton.',
     'Eva', 'carton (12)', 2800, 2200, 500, 100, 10, 'beverages'),
    ('PP-BV-003', 'Nescafe Classic 50g', 'nescafe-classic-50g',
     'Instant coffee, 48 x 50g tins per carton. Office pantry staple.',
     'Nescafe', 'carton (48)', 61000, 52000, 40, 8, 1, 'beverages'),
    ('PP-BV-004', 'Milo Refill Pack 400g', 'milo-refill-pack-400g',
     'Chocolate malt drink refill packs, 12 x 400g per carton.',
     'Milo', 'carton (12)', 48500, 41000, 70, 15, 1, 'beverages'),
    ('PP-BV-005', 'Peak Milk Powder Refill 400g', 'peak-milk-powder-refill-400g',
     'Full cream instant milk powder, 12 x 400g refill packs per carton.',
     'Peak', 'carton (12)', 65000, 56000, 55, 12, 1, 'beverages'),
    ('PP-BV-006', 'Lipton Yellow Label Tea 52s', 'lipton-yellow-label-tea-52s',
     'Black tea bags, 24 x 52-bag packs per carton.',
     'Lipton', 'carton (24)', 41000, 34500, 35, 8, 1, 'beverages'),
    ('PP-BV-007', 'Five Alive Citrus Burst 1L', 'five-alive-citrus-burst-1l',
     'Mixed citrus juice, 12 x 1 litre packs per carton.',
     'Five Alive', 'carton (12)', 19800, 16500, 60, 12, 2, 'beverages'),

    -- Cleaning & Hygiene
    ('PP-CH-001', 'Morning Fresh Dishwashing Liquid 1L', 'morning-fresh-dishwashing-liquid-1l',
     'Concentrated washing-up liquid, cuts palm oil grease. 12 x 1L per carton.',
     'Morning Fresh', 'carton (12)', 29500, 24500, 80, 16, 1, 'cleaning-hygiene'),
    ('PP-CH-002', 'Hypo Bleach 1L', 'hypo-bleach-1l',
     'Sodium hypochlorite bleach for surfaces and whites, 12 x 1L per carton.',
     'Hypo', 'carton (12)', 13200, 10800, 100, 20, 1, 'cleaning-hygiene'),
    ('PP-CH-003', 'Dettol Antiseptic Liquid 500ml', 'dettol-antiseptic-liquid-500ml',
     'Antiseptic disinfectant for surfaces and hands, 12 x 500ml per carton.',
     'Dettol', 'carton (12)', 48000, 41000, 45, 10, 1, 'cleaning-hygiene'),
    ('PP-CH-004', 'Premier Bar Soap 250g', 'premier-bar-soap-250g',
     'Multipurpose laundry bar soap, 36 bars per carton.',
     'Premier', 'carton (36)', 32500, 27000, 65, 12, 1, 'cleaning-hygiene'),
    ('PP-CH-005', 'Industrial Mop and Bucket Set', 'industrial-mop-and-bucket-set',
     'Heavy-duty flat mop with wringer bucket, sized for commercial kitchen floors.',
     NULL, 'set', 19500, 15500, 25, 5, 1, 'cleaning-hygiene'),

    -- Packaging & Disposables
    ('PP-PK-001', 'Rectangular Takeaway Pack 750ml', 'rectangular-takeaway-pack-750ml',
     'Microwave-safe polypropylene takeaway packs with lids, 500 sets per carton.',
     NULL, 'carton (500)', 35500, 29000, 70, 12, 1, 'packaging-disposables'),
    ('PP-PK-002', 'Nylon Carrier Bags (Medium)', 'nylon-carrier-bags-medium',
     'Medium high-density carrier bags, 1000 pieces per bale.',
     NULL, 'bale (1000)', 17500, 14000, 90, 20, 2, 'packaging-disposables'),
    ('PP-PK-003', 'Disposable Paper Cups 8oz', 'disposable-paper-cups-8oz',
     'Single-wall 8oz paper cups, 1000 pieces per carton.',
     NULL, 'carton (1000)', 23500, 19000, 55, 10, 1, 'packaging-disposables'),
    ('PP-PK-004', 'Aluminium Foil Roll 300m', 'aluminium-foil-roll-300m',
     'Catering-grade aluminium foil, 6 x 300m rolls per carton.',
     NULL, 'carton (6)', 28000, 23000, 30, 6, 1, 'packaging-disposables'),
    ('PP-PK-005', 'Cling Film Roll 500m', 'cling-film-roll-500m',
     'Food-safe cling film, 6 x 500m rolls per carton.',
     NULL, 'carton (6)', 20500, 16500, 35, 6, 1, 'packaging-disposables'),

    -- Kitchen Equipment
    ('PP-KE-001', 'Industrial Double Gas Burner', 'industrial-double-gas-burner',
     'Cast-iron double-ring burner with brass jets, rated for 100 litre pots.',
     NULL, 'unit', 189000, 155000, 14, 3, 1, 'kitchen-equipment'),
    ('PP-KE-002', 'Stainless Steel Prep Table 6ft', 'stainless-steel-prep-table-6ft',
     '304-grade stainless steel work table with undershelf, 6ft.',
     NULL, 'unit', 245000, 205000, 0, 2, 1, 'kitchen-equipment'),
    ('PP-KE-003', 'Commercial Blender 2L', 'commercial-blender-2l',
     '1500W heavy-duty blender with a polycarbonate 2 litre jar.',
     'Waring', 'unit', 98000, 82000, 12, 3, 1, 'kitchen-equipment'),
    ('PP-KE-004', 'Chest Freezer 300L', 'chest-freezer-300l',
     'Fast-freeze chest freezer with lock and drain, 300 litres.',
     'Haier Thermocool', 'unit', 425000, 365000, 6, 2, 1, 'kitchen-equipment'),
    ('PP-KE-005', 'Aluminium Cooking Pot Set (5pc)', 'aluminium-cooking-pot-set-5pc',
     'Heavy-gauge aluminium pot set, 20cm to 40cm, with lids.',
     NULL, 'set', 76500, 63000, 18, 4, 1, 'kitchen-equipment')
) AS v(sku, name, slug, description, brand, unit_of_measure,
       unit_price, cost_price, quantity_on_hand, low_stock_threshold,
       min_order_quantity, category_slug)
JOIN clients c ON c.slug = 'procurepal'
JOIN product_categories cat ON cat.slug = v.category_slug
ON CONFLICT (client_id, sku) DO NOTHING;

-- ============================================================================
-- ProcurePal's own stock ledger. Without this the marketplace operator's
-- inventory dashboard and analytics are empty on first run, which is a poor
-- first impression of the half of the app that already existed.
--
-- The OUT movements are inserted first so the opening IN can be computed as
-- "what is on hand now, plus everything that has left since" - that way
-- quantity_on_hand reconciles exactly against the ledger, which is the property
-- the analytics screens assume. PP-KE-002 sells out completely here, which is
-- what puts it at zero on the storefront.
-- ============================================================================
INSERT INTO stock_movements (client_id, product_id, movement_type, quantity, unit_price_at_time, note, created_by, created_at)
SELECT c.id, p.id, 'OUT', v.quantity, p.unit_price, v.note, u.id, now() - (v.days_ago || ' days')::interval
FROM (VALUES
    ('PP-GS-001', 60, 'Marketplace fulfilment - restaurant group',  22),
    ('PP-GS-001', 35, 'Marketplace fulfilment - school kitchen',     9),
    ('PP-GS-002', 40, 'Marketplace fulfilment - hotel restock',     18),
    ('PP-GS-004', 24, 'Marketplace fulfilment - canteen',           14),
    ('PP-CO-001', 30, 'Marketplace fulfilment - fast food chain',   16),
    ('PP-CO-003', 26, 'Marketplace fulfilment - buka cluster',       6),
    ('PP-BV-002', 120,'Marketplace fulfilment - conference order',  11),
    ('PP-BV-004', 18, 'Marketplace fulfilment - office pantry',      8),
    ('PP-CH-001', 22, 'Marketplace fulfilment - cleaning contract',  5),
    ('PP-PK-001', 15, 'Marketplace fulfilment - takeaway chain',     4),
    ('PP-KE-002', 6,  'Marketplace fulfilment - kitchen fit-out',   12),
    ('PP-KE-004', 3,  'Marketplace fulfilment - cold room upgrade',  7)
) AS v(sku, quantity, note, days_ago)
JOIN clients c ON c.slug = 'procurepal'
JOIN products p ON p.client_id = c.id AND p.sku = v.sku
JOIN users u ON u.client_id = c.id AND u.username = 'fulfilment'
WHERE NOT EXISTS (
    SELECT 1 FROM stock_movements sm WHERE sm.client_id = c.id
);

INSERT INTO stock_movements (client_id, product_id, movement_type, quantity, unit_price_at_time, note, created_by, created_at)
SELECT c.id, p.id, 'IN',
       p.quantity_on_hand + COALESCE(out_totals.total, 0),
       p.cost_price,
       'Opening stock take',
       u.id,
       now() - interval '35 days'
FROM clients c
JOIN products p ON p.client_id = c.id
JOIN users u ON u.client_id = c.id AND u.username = 'admin'
LEFT JOIN LATERAL (
    SELECT SUM(sm.quantity) AS total
    FROM stock_movements sm
    WHERE sm.product_id = p.id AND sm.movement_type = 'OUT'
) AS out_totals ON TRUE
WHERE c.slug = 'procurepal'
  AND p.quantity_on_hand + COALESCE(out_totals.total, 0) > 0
  AND NOT EXISTS (
      SELECT 1 FROM stock_movements sm
      WHERE sm.product_id = p.id AND sm.movement_type = 'IN'
  );

-- ============================================================================
-- The demo tenant as a marketplace BUYER.
--
-- Given pay-on-delivery terms so the non-Monnify checkout path is demoable
-- without a sandbox card, and two delivery addresses so the checkout address
-- picker has a real choice to make (a one-address picker hides half the flow).
-- ============================================================================
UPDATE clients
SET payment_terms = 'PAY_ON_DELIVERY_ALLOWED',
    phone = COALESCE(phone, '+234 801 234 5678')
WHERE slug = 'demo';

INSERT INTO delivery_addresses (
    client_id, branch_id, label, contact_name, contact_phone,
    address_line1, address_line2, city, state, landmark, delivery_notes, is_default, is_active)
SELECT c.id, b.id, v.label, v.contact_name, v.contact_phone,
       v.address_line1, v.address_line2, v.city, v.state, v.landmark, v.delivery_notes,
       v.is_default, TRUE
FROM (VALUES
    ('Main Kitchen', 'Ada Okafor', '+234 801 234 5678',
     '14 Adeola Odeku Street', 'Second floor, rear entrance', 'Victoria Island', 'Lagos',
     'Opposite Eko Hotel roundabout', 'Deliveries accepted 8am - 4pm on weekdays. Ask for Ada at reception.', TRUE),
    ('Ikeja Warehouse', 'Chidi Nwosu', '+234 802 987 6543',
     'Plot 7B Kudirat Abiola Way', NULL, 'Oregun, Ikeja', 'Lagos',
     'Beside the Oando filling station', 'Trucks should use the side gate. Forklift available.', FALSE)
) AS v(label, contact_name, contact_phone, address_line1, address_line2,
       city, state, landmark, delivery_notes, is_default)
JOIN clients c ON c.slug = 'demo'
LEFT JOIN branches b ON b.client_id = c.id AND b.is_default
WHERE NOT EXISTS (
    SELECT 1 FROM delivery_addresses da WHERE da.client_id = c.id AND da.label = v.label
);
