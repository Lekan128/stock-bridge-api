-- Splits V17's single unit_of_measure/unit_count pair into the three-field model the product
-- owner asked for after reviewing V17: unit_of_measure (what it is MEASURED in), packaging_unit
-- (how it is PACKAGED/sold, if at all), and packaging_size (how many of unit_of_measure one
-- packaging_unit holds).
--
-- ============================================================================
-- WHY V17's SINGLE unit_count WAS NOT ENOUGH
-- ============================================================================
-- V17 let a product carry ONE unit code (unit_of_measure) plus ONE number (unit_count), and
-- called the pair "how much one unit is". That conflates two different questions a B2B
-- product needs answered separately - what it is measured in, and how it is packaged/sold -
-- with no way to express both together. "A 50kg bag" needs BOTH "it's packaged as a Bag" AND
-- "each bag holds 50 kg"; under V17's single axis a caller could only pick one:
-- unit_of_measure='BAG' with unit_count=50 read as "50 of them, no unit", while
-- unit_of_measure='KG' with unit_count=50 read as "50 kg, no bag". Neither is what the
-- business means, and there was no way to say both.
--
-- Modelled on Odoo (the closest B2B/inventory analog researched for this decision): a base
-- Unit of Measure (kg, L, m, or a generic "piece" for uncounted goods) separate from a named
-- Packaging that holds a quantity expressed in that base unit. See
-- product.unit.UnitOfMeasureRole for the BASE/PACKAGING split this migration's new column
-- pairs with at the application layer.
--
-- ============================================================================
-- packaging_unit - NEW COLUMN
-- ============================================================================
-- Nullable VARCHAR(50), same shape as unit_of_measure and for the same reason: the
-- application validates it against the fixed list in product.unit.UnitOfMeasure
-- (role-checked for PACKAGING), not a schema CHECK, so the list can evolve and a
-- request-a-new-unit workflow is not blocked by this column. No default - most existing rows
-- (all of them, pre-launch) have no packaging concept recorded yet, and nothing here
-- backfills one by guessing from free text.
ALTER TABLE products
    ADD COLUMN packaging_unit VARCHAR(50);

-- ============================================================================
-- unit_count -> packaging_size - RENAME, NOT A NEW COLUMN
-- ============================================================================
-- Same NUMERIC(14, 2) column V17 added, only renamed. Its MEANING narrowed from "how much one
-- unit_of_measure is" to specifically "how much one packaging_unit is" now that
-- unit_of_measure and packaging_unit are separate axes - the old name no longer describes
-- what the field holds. No data migration: nothing is in production yet, so a straight rename
-- loses nothing and there is no existing value to reinterpret.
ALTER TABLE products
    RENAME COLUMN unit_count TO packaging_size;

COMMENT ON COLUMN products.unit_of_measure IS
    'What the product is fundamentally MEASURED in - weight/volume/length, or the generic '
    '"piece" for uncounted goods. Must resolve to a BASE-role code in '
    'product.unit.UnitOfMeasure. May be set alone (sold loose, e.g. LITER with no packaging) '
    'or alongside packaging_unit/packaging_size (e.g. KG + BAG + 50 = "a 50kg bag").';

COMMENT ON COLUMN products.packaging_unit IS
    'How the product is packaged/sold, if at all - Bag, Carton, Box and similar. Must resolve '
    'to a PACKAGING-role code in product.unit.UnitOfMeasure. Added by V18. Pairs both-or-'
    'neither with packaging_size, and requires unit_of_measure to also be set (packaging_size '
    'is a count of unit_of_measure, meaningless without it) - enforced by the service layer, '
    'not a CHECK, for the same reason unit_of_measure has never had one.';

COMMENT ON COLUMN products.packaging_size IS
    'How many of unit_of_measure one packaging_unit holds, e.g. unit_of_measure=KG, '
    'packaging_unit=BAG, packaging_size=50 -> a 50kg bag. Decimal to support fractional trade '
    'units (half-bags, litres). Renamed from unit_count by V18 (same column, same NUMERIC(14,2) '
    'precision, no data migration) once packaging_unit gave the number a name that actually '
    'matches what it counts.';
