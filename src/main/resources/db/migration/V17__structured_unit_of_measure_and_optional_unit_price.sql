-- Two schema changes that together let a BUYING COMPANY add a product without a
-- selling price, and let EVERY product (company or vendor) carry a structured
-- unit size instead of only a free-text label.
--
-- ============================================================================
-- WHY unit_price BECOMES NULLABLE
-- ============================================================================
-- unit_price is the SELLING price. It is required and meaningful for a product
-- that belongs to a tenant who may sell on the marketplace (ClientType.VENDOR,
-- or the platform owner acting as a seller) - that part is UNCHANGED here and
-- nothing in this file touches it.
--
-- It was never meaningful for a buying company's own inventory row: a company
-- adding stock it bought (not stock it sells) has no selling price to give, and
-- the column being NOT NULL forced one anyway. Relaxing the constraint is what
-- lets a company leave it blank; the rule that a SELLER'S product must still
-- have one moves to the service layer (this migration does not and cannot know
-- which client a given row belongs to without a join, the same reason
-- is_marketplace_listed's seller-only rule already lives outside a CHECK - see
-- Product.marketplaceListed's Javadoc).
--
-- No existing value is touched. Every row that already has a unit_price keeps
-- it exactly as it is; this only removes the requirement for FUTURE rows.
ALTER TABLE products
    ALTER COLUMN unit_price DROP NOT NULL;

-- ============================================================================
-- WHY unit_count, AND WHY unit_of_measure ITSELF IS LEFT ALONE
-- ============================================================================
-- unit_of_measure today is free text ('bag (50kg)', 'carton (24)') set only
-- through the vendor marketplace-details route. Going forward, EVERY product
-- (company or vendor) is meant to record a validated unit of measure from a
-- fixed list (see product.unit.UnitOfMeasure) plus a NUMBER saying how much one
-- unit actually is - e.g. unitOfMeasure='Bag', unitCount=50 means "a 50kg bag";
-- unitOfMeasure='Litre', unitCount=0.5 means "half a litre".
--
-- unit_of_measure's column is left exactly as it is - still a plain VARCHAR(50),
-- no CHECK constraint added. Two reasons, not one:
--   1. Existing vendor rows already hold free text like '50kg bag' that does not
--      match any fixed-list code, and a CHECK would break on the very rows this
--      migration must not touch.
--   2. The fixed list is enforced by the application (UnitOfMeasure.fromCode),
--      not the schema, because the product also needs a path for a tenant to
--      REQUEST a unit that is not on the list yet (a separate module) - a
--      database CHECK would have to be relaxed for every such request, which
--      defeats the point of a request workflow.
--
-- unit_count is new: a DECIMAL, not an INTEGER, because Nigerian trade units
-- routinely aren't whole numbers - half bags, litres, fractional weights. Same
-- precision/scale as unit_price and cost_price (14,2) for the same reason those
-- two share it: scale 2 already resolves 0.5 and 25.5 exactly, and matching the
-- neighbouring money-ish columns keeps the table's numeric columns uniform
-- rather than inventing a second convention next to them.
--
-- Nullable, no default: a product with no unit_of_measure has nothing for
-- unit_count to quantify either, and this migration does not backfill or
-- reparse any existing unit_of_measure text into it - that would mean guessing
-- a number out of strings like 'bag (50kg)', which is exactly the ambiguity the
-- structured field is being introduced to remove.
ALTER TABLE products
    ADD COLUMN unit_count NUMERIC(14, 2);

COMMENT ON COLUMN products.unit_price IS
    'Selling price. Required only for a product belonging to a SELLING tenant '
    '(vendor, or the platform owner acting as seller) - enforced by the service '
    'layer, not this column. Nullable since V17 so a buying company can add a '
    'product with no selling price at all.';

COMMENT ON COLUMN products.unit_count IS
    'Pairs with unit_of_measure to say how much one unit is, e.g. '
    'unit_of_measure=Bag, unit_count=50 -> a 50kg bag. Decimal to support '
    'fractional trade units (half-bags, litres). Added by V17 alongside the '
    'fixed unit-of-measure list in product.unit.UnitOfMeasure; unit_of_measure '
    'itself is untouched by this migration and stays a free VARCHAR(50) at the '
    'schema level, validated against that list by the application.';
