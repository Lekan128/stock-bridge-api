-- Multi-pack-per-vendor: a product_vendors line may now carry more than one
-- pack, instead of the single default_packaging_unit/default_packaging_size
-- pair V19 gave it. See MULTI_PACK_PER_VENDOR_DESIGN.md sections 4-5 for the
-- full reasoning; this file states the SQL-level slice of it.
--
-- ============================================================================
-- WHY THIS WAS A REAL GAP, NOT JUST A MISSING FEATURE
-- ============================================================================
-- A single default_packaging_unit/size pair meant a vendor selling the same
-- item in two bag sizes had nowhere to put the second one. Worse:
-- product.unit.UnitOptions deduplicates a product's derived unit set by
-- packaging CODE alone, so whenever a vendor's one pack shared a code with
-- the product's own pack but differed in SIZE, the vendor's pack silently
-- lost - UNIT_UX_REMEDIATION_PLAN.md section 11.1 logged this as an accepted
-- limitation. This migration is the data-model half of fixing it; the dedup
-- key itself is fixed in UnitOptions.java, not here.
--
-- ============================================================================
-- WHY A PACK ROW CAN HAVE NULL packaging_unit/packaging_size
-- ============================================================================
-- Not every vendor sells in a container at all - a vendor priced straight in
-- the product's stock unit (no bag, no carton) is the common case, and it
-- already has a lastCostPrice/vendorSku/price tiers today with
-- default_packaging_unit left null. Moving that vendor's cost onto this new
-- table needs a home for exactly that case, so a packaging_unit IS NULL row
-- means "this vendor's price/code for the bare stock unit" rather than a
-- half-configured pack. product.unit.UnitOptions simply skips such a row when
-- building a unit set - it carries no conversion factor because it names no
-- container - and reads it only for its cost/vendor-sku, the same way it
-- already reads product_vendors.last_cost_price today.
--
-- ============================================================================
-- WHY THIS IS ONE FILE DOING FOUR THINGS, IN THIS ORDER (same convention V19
-- states for itself, and for the same reason - Flyway migrations are
-- immutable once applied)
-- ============================================================================
--   1. CREATE product_vendor_packs - somewhere to backfill INTO.
--   2. BACKFILL one row per existing product_vendors line that has anything
--      to carry across (a vendor_sku, a last_cost_price, a default pack, or
--      existing price-tier rows), marked is_default.
--   3. RE-PARENT product_vendor_price_tiers from product_vendor_id to the
--      newly seeded product_vendor_pack_id, only once every tier has one.
--   4. DROP the four now-redundant columns from product_vendors.
--
-- Written out-of-order-safe like V19: every backfill statement reads "whatever
-- exists right now", not a fixed set of rows.

-- ============================================================================
-- product_vendor_packs: one row per pack (or per bare-stock-unit price) a
-- vendor line offers. NOT a client_id-bearing row - reached only through
-- product_vendor_id, itself already a tenant-scoped product_vendors row. Same
-- non-tenant-scoped-child pattern product_vendor_price_tiers already follows,
-- for the identical reason: a direct lookup by this row's id with no join
-- back to its product_vendors row would be a mistake regardless of tenancy.
-- ============================================================================
CREATE TABLE product_vendor_packs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE: a pack has no meaning once its vendor line is gone, matching
    -- product_vendor_price_tiers.product_vendor_id's own reasoning.
    product_vendor_id  UUID NOT NULL REFERENCES product_vendors (id) ON DELETE CASCADE,
    -- NULL together means "priced in the stock unit directly, no container" -
    -- see the file header. Set together means a real pack, e.g. BAG / 50.
    packaging_unit     VARCHAR(50),
    packaging_size     NUMERIC(14, 2),
    -- That vendor's own code for THIS pack specifically - vendors routinely
    -- barcode a 25 kg bag differently from a 50 kg bag of the same item, which
    -- is exactly why this moved off product_vendors rather than staying a
    -- single vendor-wide value.
    vendor_sku         VARCHAR(100),
    -- Refreshed on every stock-in against this specific pack - same "may not
    -- exist yet" nullability product_vendors.last_cost_price already has.
    last_cost_price    NUMERIC(14, 2),
    -- Which pack pre-fills the stock-in form and the Vendors tab's headline
    -- cost when this vendor is chosen and nothing else disambiguates. Exactly
    -- one TRUE per product_vendor_id, enforced below by a partial unique
    -- index - the same swap-not-set convention product_vendors.is_preferred
    -- already uses, for the same reason (a CHECK cannot see other rows).
    is_default         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The two halves of "packaging" are one idea (product.unit.UnitOptions'
    -- own javadoc) - a size with nothing named to hold it, or a container
    -- with no stated size, is not a half-valid pack, it is not a pack.
    CONSTRAINT chk_product_vendor_packs_packaging_pair
        CHECK ((packaging_unit IS NULL) = (packaging_size IS NULL)),
    CONSTRAINT chk_product_vendor_packs_packaging_size_positive
        CHECK (packaging_size IS NULL OR packaging_size > 0)
);

CREATE INDEX idx_product_vendor_packs_product_vendor_id ON product_vendor_packs (product_vendor_id);

-- At most one default pack per vendor line. Partial for the same reason
-- product_vendors.is_preferred's own index is partial - see that index's
-- comment in V19 for why a plain UNIQUE would be wrong here too.
CREATE UNIQUE INDEX uq_product_vendor_packs_one_default_per_vendor
    ON product_vendor_packs (product_vendor_id)
    WHERE is_default = TRUE;

-- At most one bare-stock-unit ("no container") row per vendor line. A plain
-- UNIQUE(product_vendor_id, packaging_unit, packaging_size) would NOT catch
-- two such rows on its own - Postgres treats every NULL as distinct from
-- every other NULL in a unique constraint, so two packaging_unit IS NULL rows
-- for the same vendor would both be allowed under a plain constraint.
CREATE UNIQUE INDEX uq_product_vendor_packs_one_bare_per_vendor
    ON product_vendor_packs (product_vendor_id)
    WHERE packaging_unit IS NULL;

-- No two real packs on the same vendor line may share a container AND a size
-- - this is what makes (packaging_unit, packaging_size) a safe, sufficient
-- way for a request to name an exact pack without an extra id on the wire
-- (see StockInRequest / StockManagementService.resolveEntry).
CREATE UNIQUE INDEX uq_product_vendor_packs_product_vendor_id_packaging
    ON product_vendor_packs (product_vendor_id, packaging_unit, packaging_size)
    WHERE packaging_unit IS NOT NULL;

CREATE TRIGGER trg_product_vendor_packs_set_updated_at
    BEFORE UPDATE ON product_vendor_packs
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE product_vendor_packs IS
    'One priced offering a vendor line makes for a product - a real pack (BAG/50) or, when '
    'packaging_unit is NULL, the bare stock unit with no container. Lets one product_vendors '
    'line carry more than one pack, which is the gap MULTI_PACK_PER_VENDOR_DESIGN.md exists '
    'to close. See sections 4-5.';

COMMENT ON COLUMN product_vendor_packs.packaging_unit IS
    'A PACKAGING-role product.unit.UnitOfMeasure code, or NULL meaning "priced in the stock '
    'unit directly, no container" - see the table comment and this file''s header.';

COMMENT ON COLUMN product_vendor_packs.vendor_sku IS
    'That vendor''s own code for THIS pack specifically - moved off product_vendors because '
    'the same vendor''s 25 kg and 50 kg bags of one item routinely carry different codes.';

COMMENT ON COLUMN product_vendor_packs.last_cost_price IS
    'Refreshed on every stock-in against this specific pack. Nullable: a pack can be added '
    'by hand before any stock has actually arrived against it yet.';

COMMENT ON COLUMN product_vendor_packs.is_default IS
    'Which pack pre-fills the stock-in form when this vendor is chosen and nothing else '
    'disambiguates. Exactly one TRUE per product_vendor_id '
    '(uq_product_vendor_packs_one_default_per_vendor). A swap, not an independent boolean - '
    'same convention as product_vendors.is_preferred, service-layer enforced.';

-- ============================================================================
-- BACKFILL: one product_vendor_packs row per existing product_vendors line
-- that has anything to carry across - a vendor_sku, a last_cost_price, a
-- configured default pack, or existing price-tier rows. A vendor line with
-- none of those (a bare preferred-flag-only row, or a freshly added vendor
-- nobody has priced yet) gets no pack row, unchanged from today's "nothing to
-- show" state.
-- ============================================================================
INSERT INTO product_vendor_packs (
    id, product_vendor_id, packaging_unit, packaging_size, vendor_sku, last_cost_price,
    is_default, created_at, updated_at
)
SELECT gen_random_uuid(), pv.id, pv.default_packaging_unit, pv.default_packaging_size,
       pv.vendor_sku, pv.last_cost_price, TRUE, now(), now()
FROM product_vendors pv
WHERE pv.vendor_sku IS NOT NULL
   OR pv.last_cost_price IS NOT NULL
   OR pv.default_packaging_unit IS NOT NULL
   OR EXISTS (SELECT 1 FROM product_vendor_price_tiers t WHERE t.product_vendor_id = pv.id);

-- ============================================================================
-- product_vendor_price_tiers: re-parent from product_vendor_id to
-- product_vendor_pack_id. A tier is a property of a specific priced offering,
-- and a pack now IS that offering - "10+ bags of 50 kg" and "10+ bags of
-- 25 kg" are different breaks a vendor might set independently. Every tier's
-- vendor line is guaranteed a pack row by the backfill above (a vendor with
-- any tier is one of the OR EXISTS cases), so the new column can be made
-- NOT NULL once backfilled, with nothing left unmapped.
-- ============================================================================
ALTER TABLE product_vendor_price_tiers
    ADD COLUMN product_vendor_pack_id UUID REFERENCES product_vendor_packs (id) ON DELETE CASCADE;

UPDATE product_vendor_price_tiers t
SET product_vendor_pack_id = pvp.id
FROM product_vendor_packs pvp
WHERE pvp.product_vendor_id = t.product_vendor_id
  AND pvp.is_default = TRUE;

ALTER TABLE product_vendor_price_tiers
    ALTER COLUMN product_vendor_pack_id SET NOT NULL;

DROP INDEX IF EXISTS idx_product_vendor_price_tiers_product_vendor_id;

ALTER TABLE product_vendor_price_tiers
    DROP CONSTRAINT uq_product_vendor_price_tiers_product_vendor_id_min_quantity,
    DROP COLUMN product_vendor_id,
    ADD CONSTRAINT uq_product_vendor_price_tiers_product_vendor_pack_id_min_quantity
        UNIQUE (product_vendor_pack_id, min_quantity);

CREATE INDEX idx_product_vendor_price_tiers_product_vendor_pack_id
    ON product_vendor_price_tiers (product_vendor_pack_id);

-- ============================================================================
-- product_vendors: drop the four columns that moved onto product_vendor_packs.
-- Nothing still depends on them reading correctly - every value they held is
-- either backfilled into a pack row above, or was null and had nothing to
-- carry. is_preferred and the two cached rollups stay exactly where they are;
-- MULTI_PACK_PER_VENDOR_DESIGN.md section 4.2 explains why - stock is
-- fungible once received regardless of which pack it arrived in, so "how
-- much from this vendor" stays a vendor-level question, not a per-pack one.
-- ============================================================================
ALTER TABLE product_vendors
    DROP COLUMN vendor_sku,
    DROP COLUMN last_cost_price,
    DROP COLUMN default_packaging_unit,
    DROP COLUMN default_packaging_size;
