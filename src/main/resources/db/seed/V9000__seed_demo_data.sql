-- Demo/local seed data - NOT a real schema migration.
--
-- This file lives in db/seed (not db/migration) and that location is only
-- added to spring.flyway.locations in the local and docker profiles - see
-- application-local.yml / application-docker.yml. application-prod.yml never
-- references db/seed, so this can never run against a production database
-- even by accident. Both locations still share one Flyway schema history
-- table, so the version prefix here (V9000) is deliberately far above the
-- real schema migrations (currently V1-V4) to guarantee it never collides
-- with a future one.
--
-- ============================================================================
-- Demo login credentials (also documented in the root APP_TOUR.md "Try it
-- out" section and stock-bridge-api/README.md):
--
--   Client identifier: demo
--   Username: admin    Password: Demo1234!   (role ADMIN)
--   Username: manager  Password: Demo1234!   (role MANAGER)
--   Username: staff    Password: Demo1234!   (role STAFF)
--
-- The password hash below is bcrypt("Demo1234!"), generated with the app's
-- own BCryptPasswordEncoder and verified with encoder.matches(...) before
-- being pasted in here - it isn't a hand-rolled or copy-pasted hash.
-- ============================================================================

INSERT INTO clients (name, slug, admin_contact_email) VALUES
    ('Demo Retail Co', 'demo', 'admin@demo.example');

INSERT INTO users (client_id, username, password_hash, role_id)
SELECT c.id, v.username, '$2a$10$kTBnKCWpk/bdB.G8xq5KK.MIhANye.rDwoXrlAQhF2sf9hrBjZqVm', r.id
FROM (VALUES
    ('admin',   'ADMIN'),
    ('manager', 'MANAGER'),
    ('staff',   'STAFF')
) AS v(username, role_name)
JOIN clients c ON c.slug = 'demo'
JOIN roles r ON r.name = v.role_name;

-- ============================================================================
-- Products: 10 items with varied stock levels. WC-006 and CB-003 are
-- deliberately seeded at/under their low_stock_threshold so the low-stock
-- banner/list has something to show immediately on first run.
-- ============================================================================
INSERT INTO products (client_id, name, sku, description, unit_price, cost_price, quantity_on_hand, low_stock_threshold)
SELECT c.id, v.name, v.sku, v.description, v.unit_price, v.cost_price, v.quantity_on_hand, v.low_stock_threshold
FROM (VALUES
    ('Wireless Mouse',       'WM-001', 'Ergonomic 2.4GHz wireless mouse',        19.99,  8.50, 120, 20),
    ('Mechanical Keyboard',  'KB-002', 'Hot-swappable mechanical keyboard',      79.99, 35.00,  45, 10),
    ('USB-C Cable 1m',       'CB-003', 'Braided USB-C to USB-C charge cable',     9.99,  2.50,   8, 25),
    ('27-inch Monitor',      'MN-004', '27" 1440p IPS monitor',                 249.99,150.00,  15,  5),
    ('Laptop Stand',         'LS-005', 'Adjustable aluminum laptop stand',       34.99, 14.00,  60, 15),
    ('Webcam 1080p',         'WC-006', '1080p USB webcam with privacy shutter',  49.99, 20.00,   3, 10),
    ('Desk Lamp LED',        'DL-007', 'Dimmable LED desk lamp',                 24.99,  9.00,  90, 20),
    ('Bluetooth Speaker',    'BS-008', 'Portable Bluetooth speaker',             59.99, 25.00,  32, 10),
    ('Office Chair',         'OC-009', 'Ergonomic mesh office chair',           189.99, 95.00,  12,  5),
    ('Notebook Pack (5)',    'NB-010', 'Pack of 5 ruled A5 notebooks',           12.99,  4.00, 200, 50)
) AS v(name, sku, description, unit_price, cost_price, quantity_on_hand, low_stock_threshold)
JOIN clients c ON c.slug = 'demo';

-- ============================================================================
-- Stock movements: an initial receipt per product, a sale/withdrawal, and a
-- couple of inventory-count adjustments, spread over the last ~45 days so the
-- analytics dashboard (movements-over-time, top products, in/out totals) has
-- real trend data - not just a single data point - on first run. Quantities
-- reconcile with each product's quantity_on_hand above (e.g. CB-003:
-- 40 in - 30 out - 2 adjustment = 8).
-- ============================================================================
INSERT INTO stock_movements (client_id, product_id, movement_type, quantity, unit_price_at_time, note, created_by, created_at)
SELECT c.id, p.id, v.movement_type, v.quantity, v.unit_price_at_time, v.note, u.id, now() - (v.days_ago || ' days')::interval
FROM (VALUES
    ('WM-001', 'IN',         150,   8.50, 'Initial stock receipt',      'admin',   45),
    ('WM-001', 'OUT',         30,  19.99, 'Bulk order - Acme Corp',     'staff',   20),
    ('KB-002', 'IN',           60, 35.00, 'Initial stock receipt',      'admin',   45),
    ('KB-002', 'OUT',          15, 79.99, 'Online order batch',         'staff',   18),
    ('CB-003', 'IN',           40,  2.50, 'Initial stock receipt',      'admin',   45),
    ('CB-003', 'OUT',          30,  9.99, 'Retail counter sales',       'staff',   16),
    ('CB-003', 'ADJUSTMENT',   -2,   NULL,'Cycle count correction',     'manager',  5),
    ('MN-004', 'IN',           20,150.00, 'Initial stock receipt',      'admin',   40),
    ('MN-004', 'OUT',           5,249.99, 'Office refresh order',       'staff',   12),
    ('LS-005', 'IN',           80, 14.00, 'Initial stock receipt',      'admin',   40),
    ('LS-005', 'OUT',          20, 34.99, 'Corporate bulk order',       'staff',   10),
    ('WC-006', 'IN',           25, 20.00, 'Initial stock receipt',      'admin',   38),
    ('WC-006', 'OUT',          20, 49.99, 'Remote work bundle sales',   'staff',    9),
    ('WC-006', 'ADJUSTMENT',   -2,   NULL,'Damaged units written off',  'manager',  2),
    ('DL-007', 'IN',          100,  9.00, 'Initial stock receipt',      'admin',   38),
    ('DL-007', 'OUT',          10, 24.99, 'Retail counter sales',       'staff',    7),
    ('BS-008', 'IN',           40, 25.00, 'Initial stock receipt',      'admin',   35),
    ('BS-008', 'OUT',           8, 59.99, 'Online order batch',         'staff',    6),
    ('OC-009', 'IN',           15, 95.00, 'Initial stock receipt',      'admin',   35),
    ('OC-009', 'IN',            5, 95.00, 'Restock - supplier delivery','admin',   13),
    ('OC-009', 'OUT',           8,189.99, 'Office refresh order',       'staff',    3),
    ('NB-010', 'IN',          220,  4.00, 'Initial stock receipt',      'admin',   35),
    ('NB-010', 'OUT',          20, 12.99, 'Retail counter sales',       'staff',    1)
) AS v(sku, movement_type, quantity, unit_price_at_time, note, username, days_ago)
JOIN clients c ON c.slug = 'demo'
JOIN products p ON p.client_id = c.id AND p.sku = v.sku
JOIN users u ON u.client_id = c.id AND u.username = v.username;
