-- Multi-vendor inventory: one Product row may now be supplied by several
-- CompanyVendor directory entries, each with its own cost, packaging and
-- running quantity - instead of the single products.company_vendor_id FK V11
-- added. Stock-out gains true per-shipment lot FIFO, traceable to the exact
-- delivery it was drawn from. See MULTI_VENDOR_INVENTORY_DESIGN.md sections
-- 5/6 for the full reasoning; this file states the SQL-level slice of it.
--
-- ============================================================================
-- WHY products.company_vendor_id HAD TO GO, NOT JUST GROW A SIBLING COLUMN
-- ============================================================================
-- A tenant that buys "Rice 50kg" from two suppliers has always had to create two
-- Product rows to record that - one vendor slot per inventory item, because
-- company_vendor_id is a single FK. That is the literal bottleneck this file
-- removes: product_vendors below is a join table, Odoo/NetSuite-style, so one
-- Product row can carry N supplier lines. The FK could not simply grow a second
-- nullable column next to it (company_vendor_id_2, ...) because the real
-- requirement is an unbounded list with its own per-line cost, packaging and
-- quantity-supplied - exactly what a child table is for and a fixed set of
-- columns is not.
--
-- ============================================================================
-- WHY THIS IS ONE FILE DOING FOUR THINGS, IN THIS ORDER
-- ============================================================================
-- Flyway migrations are immutable once applied, so the create-backfill-drop
-- sequence for products.company_vendor_id has to live together here rather than
-- across several once-this-lands-in-prod files:
--   1. CREATE product_vendors / product_vendor_price_tiers - the new join table
--      and its optional quantity-break child, so there is somewhere to backfill
--      INTO.
--   2. ALTER stock_movements - add company_vendor_id/packaging_unit/
--      packaging_size, and CREATE stock_movement_allocations - so an IN
--      movement can carry vendor+packaging context and be drawn against lot by
--      lot.
--   3. BACKFILL one product_vendors row per existing products.company_vendor_id,
--      marked preferred - every product that had exactly one vendor keeps
--      exactly one vendor, now expressed as a join row instead of a column.
--   4. DROP products.company_vendor_id, only now that nothing still depends on
--      it reading correctly.
--
-- Same out-of-order-safe convention V11 states in full: every statement here is
-- written to be correct whether Flyway runs this in a fresh V1..V19 sequence or
-- "out of order" behind an already-seeded local database - the backfill is
-- expressed as "whatever exists right now", not a fixed set of rows.

-- ============================================================================
-- product_vendors: one row per (product, supplier) - the Odoo pricelist line /
-- NetSuite item-vendor line this whole file exists to add.
-- ============================================================================
CREATE TABLE product_vendors (
    id                            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Tenant-scoped like every other row-level entity here (products,
    -- stock_movements, company_vendors). Safe to store directly rather than
    -- derive through product_id, the same reasoning products.company_vendor_id
    -- itself relied on: product_id and company_vendor_id below both belong to
    -- the same tenant by construction, so there is no cross-tenant join to get
    -- wrong.
    client_id                     UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- The inventory item this line supplies. CASCADE: unlike stock_movements
    -- (an append-only ledger that must outlive the product it describes),
    -- product_vendors is live configuration ABOUT a product - vendor cost,
    -- packaging default, preferred flag - and has no meaning once the product
    -- itself is gone. Products are expected to be deactivated rather than
    -- hard-deleted (see V1's stock_movements comment), so this is a defensive
    -- default rather than a path anything exercises today.
    product_id                    UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- The supplier, from the buyer's OWN directory (company_vendors). RESTRICT,
    -- matching company_vendors.platform_client_id's own reasoning: a vendor
    -- directory entry is soft-deleted (is_active), never hard-deleted, by
    -- CompanyVendorService - so this is a guard against a future bypass of that
    -- rule, not a path exercised today. Letting the delete cascade or null this
    -- out would silently erase per-vendor cost/quantity history that the
    -- deactivate flow explicitly promises to keep (see CompanyVendorService's
    -- class javadoc: "Products keep pointing at the row rather than being
    -- unlinked").
    company_vendor_id             UUID NOT NULL REFERENCES company_vendors (id) ON DELETE RESTRICT,
    -- That vendor's own code for this item, if the buyer has recorded one.
    -- Purely descriptive, shown on the Vendors tab.
    vendor_sku                    VARCHAR(100),
    -- Refreshed on every stock-in from this vendor - see StockManagementService.
    -- Nullable: a vendor line can exist (added by hand, or as the preferred slot
    -- on a brand-new product) before any stock has ever actually come in from
    -- them.
    last_cost_price               NUMERIC(14, 2),
    -- Prefills the stock-in form for this vendor; overridable per delivery via
    -- stock_movements.packaging_unit/packaging_size below. Same fixed-list
    -- validation as products.packaging_unit (product.unit.UnitOfMeasure,
    -- PACKAGING role) - enforced by the service layer, not a CHECK, for the
    -- same reason products.packaging_unit has never had one.
    default_packaging_unit        VARCHAR(50),
    default_packaging_size        NUMERIC(14, 2),
    -- NetSuite-style manual pin: which vendor defaults into the stock-in form
    -- when nothing else disambiguates. Exactly one TRUE per product_id, enforced
    -- below by a partial unique index rather than a CHECK (a CHECK cannot see
    -- other rows). Toggling it on for one vendor is a SWAP that atomically
    -- unflips whichever other row currently holds it - service-layer
    -- responsibility (ProductVendorService), because the swap has to happen
    -- inside the same transaction as the flip for the partial index to never
    -- see a violation. There is deliberately no way to unset it to "no
    -- preferred vendor" - see MULTI_VENDOR_INVENTORY_DESIGN.md section 7.4.
    is_preferred                  BOOLEAN NOT NULL DEFAULT FALSE,
    -- Cached DISPLAY ROLLUP only - "how much from this vendor" for the Vendors
    -- tab - not what stock-out draws down against. That is
    -- stock_movement_allocations below, which is the real ledger. Derived as
    -- SUM(this vendor's IN movements) - SUM(allocations against those
    -- movements), recomputable if it ever drifts. Same cached-not-authoritative
    -- status as products.quantity_on_hand.
    quantity_on_hand_from_vendor  INTEGER NOT NULL DEFAULT 0,
    -- Lifetime received from this vendor, for "how much have we ever gotten from
    -- them" without scanning the ledger. Derived as SUM(this vendor's IN
    -- movements) alone - unlike quantity_on_hand_from_vendor, never decremented.
    total_quantity_received       INTEGER NOT NULL DEFAULT 0,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One line per (product, vendor) - a second stock-in from the same supplier
    -- updates this row rather than creating a duplicate. This is what the
    -- find-or-create in StockManagementService/ProductVendorService relies on.
    CONSTRAINT uq_product_vendors_product_id_company_vendor_id UNIQUE (product_id, company_vendor_id)
);

CREATE INDEX idx_product_vendors_client_id ON product_vendors (client_id);
-- The Vendors tab's own read: every supplier line for one product, preferred
-- first. Matches the ORDER BY the repository issues.
CREATE INDEX idx_product_vendors_product_id ON product_vendors (product_id, is_preferred DESC, created_at ASC);
-- "What do we buy from this supplier" - VendorPurchaseService.suppliedProducts,
-- the replacement for the old products.company_vendor_id-keyed query.
CREATE INDEX idx_product_vendors_company_vendor_id ON product_vendors (company_vendor_id);

-- At most one preferred vendor per product. Partial, not a plain UNIQUE, for
-- the same reason V6/V11 use partial indexes elsewhere: the rule only applies
-- to rows where is_preferred is actually true, and Postgres's NULLS DISTINCT
-- default would make that true anyway for a nullable boolean - but is_preferred
-- is NOT NULL here, so a plain UNIQUE(product_id, is_preferred) would also
-- forbid two NON-preferred vendors on the same product, which is exactly the
-- common case (a product with several suppliers, only one pinned). The partial
-- form says precisely what is meant.
CREATE UNIQUE INDEX uq_product_vendors_one_preferred_per_product
    ON product_vendors (product_id)
    WHERE is_preferred = TRUE;

CREATE TRIGGER trg_product_vendors_set_updated_at
    BEFORE UPDATE ON product_vendors
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_vendors IS
    'One (product, supplier) line - the Odoo pricelist line / NetSuite item-vendor line. '
    'Replaces the single products.company_vendor_id FK dropped later in this file, so a '
    'product may now carry any number of suppliers instead of exactly zero or one. See '
    'MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1.';

COMMENT ON COLUMN product_vendors.vendor_sku IS
    'That vendor''s own code for this item, if recorded. Purely descriptive.';

COMMENT ON COLUMN product_vendors.last_cost_price IS
    'Refreshed on every stock-in from this vendor. Nullable: a vendor line can exist '
    '(added by hand, or as the sole vendor on a new product) before any stock has '
    'actually come in from them yet.';

COMMENT ON COLUMN product_vendors.default_packaging_unit IS
    'Prefills the stock-in form for this vendor; overridable per delivery via '
    'stock_movements.packaging_unit. Same fixed-list validation as '
    'products.packaging_unit (PACKAGING role), enforced by the service layer.';

COMMENT ON COLUMN product_vendors.default_packaging_size IS
    'Pairs with default_packaging_unit - how many of the product''s base unit_of_measure '
    'one default_packaging_unit holds for this vendor.';

COMMENT ON COLUMN product_vendors.is_preferred IS
    'NetSuite-style manual pin - which vendor defaults into the stock-in form. Exactly '
    'one TRUE per product_id (uq_product_vendors_one_preferred_per_product). Flipping it '
    'on is a SWAP, atomically unflipping whichever row held it before, done in one '
    'service-layer transaction so this index never sees a violation. There is no '
    'operation that clears it to "no preferred vendor" - see design doc section 7.4.';

COMMENT ON COLUMN product_vendors.quantity_on_hand_from_vendor IS
    'Cached DISPLAY ROLLUP only, for the Vendors tab. NOT what stock-out draws down '
    'against - that is stock_movement_allocations. Derived as SUM(this vendor''s IN '
    'movements) - SUM(allocations against those movements); recomputable from the '
    'ledger if it ever drifts, same cached-not-authoritative status as '
    'products.quantity_on_hand.';

COMMENT ON COLUMN product_vendors.total_quantity_received IS
    'Lifetime quantity received from this vendor. Derived as SUM(this vendor''s IN '
    'movements) alone - unlike quantity_on_hand_from_vendor, never decremented by a '
    'sale.';

-- ============================================================================
-- product_vendor_price_tiers: optional quantity-break pricing on a vendor line
-- - "under 10 bags at X, 10+ at Y". A vendor with zero rows here is the common
-- case and stays exactly as simple as before: one flat last_cost_price.
-- ============================================================================
CREATE TABLE product_vendor_price_tiers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE: a tier has no meaning once its vendor line is gone, and
    -- product_vendors rows are themselves never hard-deleted in the ordinary
    -- product/vendor lifecycle (see product_vendors.company_vendor_id above),
    -- so this is a consistency guard rather than an exercised path.
    product_vendor_id  UUID NOT NULL REFERENCES product_vendors (id) ON DELETE CASCADE,
    -- In the product's base unit_of_measure, so a tier compares consistently
    -- regardless of what unit a given purchase or sale is entered in - a
    -- purchase entered in bags is converted to base units before being checked
    -- against this column, not the other way round. INCLUSIVE: a tier applies
    -- AT exactly min_quantity and above, matching how a human reads "10+ bags".
    min_quantity       NUMERIC(14, 2) NOT NULL,
    unit_price         NUMERIC(14, 2) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_product_vendor_price_tiers_min_quantity_positive CHECK (min_quantity > 0),
    CONSTRAINT chk_product_vendor_price_tiers_unit_price_non_negative CHECK (unit_price >= 0),
    -- Two tiers at the same breakpoint on the same vendor line have no defined
    -- meaning - which price wins at that exact quantity?
    CONSTRAINT uq_product_vendor_price_tiers_product_vendor_id_min_quantity UNIQUE (product_vendor_id, min_quantity)

    -- No client_id: reached only through product_vendor_id, itself reached
    -- only through a tenant-scoped product_vendors row - the same
    -- non-tenant-scoped-child pattern order_items and stock_movement_allocations
    -- (below) already follow. A direct lookup by tier id with no join back to
    -- its product_vendors row would be a mistake regardless of tenancy.
);

CREATE INDEX idx_product_vendor_price_tiers_product_vendor_id ON product_vendor_price_tiers (product_vendor_id);

CREATE TRIGGER trg_product_vendor_price_tiers_set_updated_at
    BEFORE UPDATE ON product_vendor_price_tiers
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_vendor_price_tiers IS
    'Optional quantity-break pricing for one product_vendors line. Purely additive - a '
    'vendor with no rows here is the common case and stays exactly as simple as one flat '
    'last_cost_price. See MULTI_VENDOR_INVENTORY_DESIGN.md section 5.1a.';

COMMENT ON COLUMN product_vendor_price_tiers.min_quantity IS
    'In the product''s base unit_of_measure, so tiers compare consistently regardless of '
    'what unit a purchase is entered in. INCLUSIVE - applies at exactly this quantity and '
    'above, matching how "10+ bags" reads in ordinary speech.';

COMMENT ON COLUMN product_vendor_price_tiers.unit_price IS
    'The per-base-unit price once min_quantity is reached or exceeded for this vendor.';

-- ============================================================================
-- stock_movements: three new columns. IN movements only carry a vendor - see
-- the column comment below for why OUT/ADJUSTMENT deliberately never do.
-- ============================================================================
ALTER TABLE stock_movements
    ADD COLUMN company_vendor_id UUID REFERENCES company_vendors (id) ON DELETE RESTRICT,
    ADD COLUMN packaging_unit    VARCHAR(50),
    ADD COLUMN packaging_size    NUMERIC(14, 2);

CREATE INDEX idx_stock_movements_company_vendor_id ON stock_movements (company_vendor_id);

COMMENT ON COLUMN stock_movements.company_vendor_id IS
    'Which supplier this delivery came from - IN movements only. Required by service-layer '
    'validation (StockManagementService) once the product already has any product_vendors '
    'row; a CHECK cannot express that, it needs a join. Deliberately left NULL on OUT/'
    'ADJUSTMENT: once lot allocation (stock_movement_allocations below) exists, a single '
    'OUT can legitimately span more than one vendor, so a column here would either pick '
    'one arbitrarily or be redundant with the true multi-vendor breakdown, which is always '
    'read through stock_movement_allocations instead. RESTRICT, matching '
    'product_vendors.company_vendor_id - a vendor directory entry is soft-deleted, never '
    'hard-deleted, so this only guards against a future bypass of that rule.';

COMMENT ON COLUMN stock_movements.packaging_unit IS
    'Snapshot of what was actually delivered on THIS movement - same "freeze what '
    'happened at the time" reasoning unit_price_at_time already uses. A product_vendors '
    'row''s default_packaging_unit can change later without rewriting history. Nullable, '
    'and only ever meaningful on an IN movement.';

COMMENT ON COLUMN stock_movements.packaging_size IS
    'Pairs with packaging_unit - how many of the product''s base unit_of_measure this '
    'delivery''s packaging held, frozen at the time of receipt.';

-- ============================================================================
-- stock_movement_allocations: which OUT movement consumed how much of which
-- specific IN movement (lot). This is what turns "vendor-level FIFO" into true
-- per-shipment FIFO - every IN movement is already an immutable, timestamped,
-- vendor-and-price-tagged record once the columns above exist, so this table
-- only has to record CONSUMPTION against it.
-- ============================================================================
CREATE TABLE stock_movement_allocations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RESTRICT on both FKs, matching stock_movements.product_id's own reasoning
    -- (V1): this is an audit trail, and stock_movements rows are never deleted
    -- in the first place (append-only), so RESTRICT is a guard against that
    -- invariant ever being broken rather than a path exercised today.
    out_movement_id  UUID NOT NULL REFERENCES stock_movements (id) ON DELETE RESTRICT,
    in_movement_id   UUID NOT NULL REFERENCES stock_movements (id) ON DELETE RESTRICT,
    -- Integer, matching stock_movements.quantity - every quantity in this
    -- ledger is a whole count of the product's base unit_of_measure, and an
    -- allocation can never consume a fractional unit of a lot that was itself
    -- received as a whole number.
    quantity         INTEGER NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_stock_movement_allocations_quantity_positive CHECK (quantity > 0),
    -- A movement cannot allocate against itself - always a data error, cheap to
    -- rule out here rather than discover in a reconciliation report.
    CONSTRAINT chk_stock_movement_allocations_distinct_movements CHECK (out_movement_id <> in_movement_id)

    -- No client_id, no updated_at: reached only through out_movement_id/
    -- in_movement_id, both already tenant-scoped stock_movements rows (same
    -- non-tenant-scoped-child pattern as product_vendor_price_tiers above), and
    -- append-only exactly like stock_movements itself - an allocation, once
    -- written, is never edited, only possibly superseded by a later one.
);

-- The two hot reads: "what did this OUT draw from" (breakdown shown right after
-- a sale) and "what did this IN go on to fund" (GET .../allocations, the
-- recall/dispute trace). Both, plus the remaining-balance-per-lot computation
-- in StockManagementService, filter on exactly one of these two columns.
CREATE INDEX idx_stock_movement_allocations_out_movement_id ON stock_movement_allocations (out_movement_id);
CREATE INDEX idx_stock_movement_allocations_in_movement_id ON stock_movement_allocations (in_movement_id);

COMMENT ON TABLE stock_movement_allocations IS
    'Append-only record of which OUT movement consumed how much of which specific IN '
    'movement (lot). A single OUT of 20 bags may write two rows if it spans two lots. '
    'Remaining quantity in a lot = that IN movement''s quantity - SUM(allocations '
    'against it) - purely derived, never stored, so it cannot drift. See '
    'MULTI_VENDOR_INVENTORY_DESIGN.md section 5.2a, including its "Concurrency and '
    'oversell" paragraph: the service layer must SELECT ... FOR UPDATE the candidate IN '
    'movements before inserting allocation rows against them, in the same transaction, '
    'or two concurrent stock-outs can both read the same "remaining" balance and both '
    'try to spend it.';

COMMENT ON COLUMN stock_movement_allocations.quantity IS
    'How much of the in_movement lot this out_movement consumed. Integer, matching '
    'stock_movements.quantity - see the column-level reasoning above.';

-- ============================================================================
-- BACKFILL: one product_vendors row per existing products.company_vendor_id,
-- marked preferred. Every product that had exactly one vendor keeps exactly
-- one vendor - now expressed as a join row instead of a column.
--
-- quantity_on_hand_from_vendor and total_quantity_received are both seeded
-- from products.quantity_on_hand. That is exact for quantity_on_hand_from_vendor
-- (this was the product's only vendor, so all its on-hand stock came from
-- here). It is only an approximation for total_quantity_received: the old
-- schema never tracked "lifetime received" separately from "currently on
-- hand", so any stock already sold before this migration ran is not
-- reflected. That undercount is accepted rather than reconstructed from the
-- stock_movements ledger here, because a product's IN movements before this
-- migration carry no company_vendor_id to attribute them to - reconstructing
-- "received from THIS vendor" would mean assuming every historical IN
-- movement came from the one vendor the product happened to have, which is
-- already exactly what quantity_on_hand_from_vendor's seed value asserts, so
-- summing the ledger instead would not produce a different, more correct
-- number - it would produce the same approximation through more work.
-- ============================================================================
INSERT INTO product_vendors (
    id, client_id, product_id, company_vendor_id, is_preferred,
    quantity_on_hand_from_vendor, total_quantity_received, created_at, updated_at
)
SELECT gen_random_uuid(), p.client_id, p.id, p.company_vendor_id, TRUE,
       p.quantity_on_hand, p.quantity_on_hand, now(), now()
FROM products p
WHERE p.company_vendor_id IS NOT NULL;

-- ============================================================================
-- products.company_vendor_id: DROP, now that every row it named has a
-- product_vendors row saying the same thing. This is the literal bottleneck
-- MULTI_VENDOR_INVENTORY_DESIGN.md section 4 names - one vendor slot per
-- product - and removing it is what lets a product carry more than one.
-- Product.getPreferredVendor() (entity/Product.java) is the read-side
-- replacement, computed from the product_vendors collection rather than a
-- duplicated FK - see its javadoc.
-- ============================================================================
DROP INDEX IF EXISTS idx_products_company_vendor_id;

ALTER TABLE products
    DROP COLUMN company_vendor_id;
