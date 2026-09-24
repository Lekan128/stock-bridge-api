-- The product's printed size: "750 ml", "50 kg", "12 cm".
--
-- ============================================================================
-- WHY A DESCRIPTOR AND NOT A NUMBER
-- ============================================================================
-- PACK_ENTRY_REDESIGN.md section 5. A pack of bottled water was being entered
-- as stock unit Milliliter, pack Pack, units per pack 750 - "a pack holds
-- 750 ml" - when the truth is twelve 750 ml bottles. Every cell in that row is
-- individually legal, so nothing in the system could detect it, and the shelf
-- ended up wrong by a factor of twelve.
--
-- The cause is that the model had two slots (unit_of_measure, packaging_unit +
-- packaging_size) for three ideas the user holds: how big one bottle is, what
-- one bottle is, and how many bottles arrive together. The 750 had no home, so
-- it went into the nearest box that accepted a number.
--
-- This column is that home. It is TEXT and it is never parsed, never
-- multiplied, never converted to anything. A user who can see somewhere to put
-- "750 ml" stops putting it in packaging_size, which is the entire point - and
-- that matters more than it looks, because unit_of_measure is IMMUTABLE once a
-- product has any stock movement (see ProductManagementService: V19), and a
-- bulk import's opening stock creates one. A wrong stock unit is therefore
-- permanent, and this column is the cheapest way to keep the wrong value out
-- of it.
--
-- Any future temptation to parse this into a conversion factor is a
-- re-introduction of the rejected design in section 1 of that document and
-- should be refused: the whole reason it is safe is that it is inert.
--
-- ============================================================================
-- SHAPE
-- ============================================================================
-- Nullable, because it is optional on every surface and every existing row has
-- no value for it. varchar(32) is generous for "750 ml" while staying far too
-- short to be abused as a second description field.
-- ----------------------------------------------------------------------------

ALTER TABLE products
    ADD COLUMN size varchar(32);

COMMENT ON COLUMN products.size IS
    'Printed size of one stock unit, e.g. "750 ml". Descriptive only - never parsed or used in any calculation. See PACK_ENTRY_REDESIGN.md section 5.';
