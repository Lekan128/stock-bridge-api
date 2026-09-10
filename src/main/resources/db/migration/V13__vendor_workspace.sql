-- The vendor's own workspace: the two invariants it needs the DATABASE to hold.
--
-- Everything else this module ships (own-sales analytics, the pickup-address
-- screens, the vendor dashboard and catalogue) is application code over columns
-- that already exist. Only two things belong down here, and both are here for
-- the same reason: they are rules that must survive a route nobody thought of.
--
--   1. delivery_addresses.address_purpose - a vendor's addresses are where goods
--      are COLLECTED FROM, not delivered to, and the two must not be served to
--      each other's screens.
--   2. A vendor client may hold at most ONE user row, enforced by trigger rather
--      than by the absence of a permission.


-- ============================================================================
-- 1. address_purpose: DELIVERY vs PICKUP
-- ============================================================================
-- V11 said, in the clients section, that vendors would reuse delivery_addresses
-- "as-is" for their pickup addresses on the grounds that a vendor is a clients
-- row and the table is already tenant-scoped to them. The first half of that
-- holds and is why there is no new table here. The second half does not survive
-- contact with the actual seller population, and this column is the correction.
--
-- The case that breaks it is ProcurePal, not a vendor. ProcurePal is a seller
-- (Client.canSell()) AND an ordinary COMPANY with MANAGE_DELIVERY_ADDRESSES and
-- its own /app/addresses screen. With one undifferentiated table, its buyer
-- address book and its pickup locations are the same rows: the pickup screen
-- lists places nothing is ever collected from, the checkout picker offers depots
-- goods are never delivered to, and "one default per client" makes those two
-- meanings fight over a single flag. Tenant scoping cannot separate them because
-- they are the SAME tenant. A discriminator can, and it is one column.
--
-- Not a separate table, for the reason V11 gave and which still stands: the
-- shape is identical column for column (Nigerian state, contact name and phone,
-- soft delete, one default), the same validator and the same frontend controls
-- serve both, and orders.delivery_address_id already points here. A second table
-- would duplicate all of that so it could diverge.
--
-- Default 'DELIVERY': every existing row was created by a buying company through
-- /api/delivery-addresses, so the backfill is the default and no UPDATE is
-- needed. Fail-safe in the direction that matters, too - a row whose purpose was
-- somehow never set reads as a buyer address, which is the harmless answer on a
-- pickup screen and the correct one at checkout.
ALTER TABLE delivery_addresses
    ADD COLUMN address_purpose VARCHAR(20) NOT NULL DEFAULT 'DELIVERY',
    ADD CONSTRAINT chk_delivery_addresses_purpose
        CHECK (address_purpose IN ('DELIVERY', 'PICKUP'));

CREATE INDEX idx_delivery_addresses_client_id_purpose
    ON delivery_addresses (client_id, address_purpose);

-- "Exactly one default" is per purpose, not per client. V6's index made the two
-- meanings compete for one flag; a tenant that both buys and sells needs a
-- default delivery address AND a default pickup point, and neither should be
-- able to demote the other.
--
-- Dropped and recreated rather than left alongside a second index: keeping V6's
-- would silently keep enforcing the old, wrong rule, and the failure would show
-- up as "I cannot make this pickup address the default" with nothing on screen
-- to explain it.
DROP INDEX IF EXISTS uq_delivery_addresses_one_default_per_client;

CREATE UNIQUE INDEX uq_delivery_addresses_one_default_per_client_purpose
    ON delivery_addresses (client_id, address_purpose) WHERE is_default AND is_active;


-- ============================================================================
-- 2. A VENDOR client holds at most one user
-- ============================================================================
-- The stakeholder's rule is that a vendor has exactly ONE user account and
-- cannot create staff. Until now the only thing implementing it was an absence:
-- the VENDOR role does not hold MANAGE_USERS, so POST /api/users 403s and the
-- Users nav item does not render. That hides the capability; it does not make
-- the rule true. A second grant, a new admin path, a data fix or a future
-- surface that creates users for a client id it was handed would each undo it
-- silently, and the damage is not a bad screen - it is a second login to a
-- business's entire presence on the marketplace, with the account-holder rules
-- (root cannot be demoted or deactivated) suddenly applying to only one of them.
--
-- So the rule lives here, where every route passes whether it knows it or not.
--
-- WHY A TRIGGER AND NOT A CONSTRAINT
-- A CHECK cannot do it: the condition spans two tables (users.client_id against
-- clients.client_type) and counts sibling rows. A partial unique index cannot
-- either, for the same reason - the discriminator is not on this table. A
-- trigger is the only mechanism Postgres offers for a cross-table cardinality
-- rule, and this one is BEFORE INSERT so the row never lands.
--
-- WHY THE APPLICATION STILL CHECKS
-- This is the backstop, deliberately not the primary path. A trigger raises a
-- DataIntegrityViolationException, which reaches a caller as a 409 with a
-- database-shaped message - the wrong answer for somebody who simply pressed
-- "Add user". VendorSingleAccountRule runs first on every user-creating service
-- and produces a clean, explained 4xx. If the two ever disagree, this one wins,
-- which is the point of having it.
--
-- WHY UPDATE OF client_id IS COVERED TOO
-- Moving an existing user into a vendor tenant is the same act as creating one
-- there. Nothing in the application does this today; the trigger costs nothing
-- and closes the route rather than trusting that nobody adds it.
CREATE OR REPLACE FUNCTION enforce_vendor_single_account() RETURNS TRIGGER AS $$
DECLARE
    owner_is_vendor BOOLEAN;
    sibling_count   BIGINT;
BEGIN
    SELECT c.client_type = 'VENDOR'
      INTO owner_is_vendor
      FROM clients c
     WHERE c.id = NEW.client_id;

    -- No client row yet, or an ordinary company / the platform owner. Not this
    -- trigger's business; the foreign key and the rest of the schema deal with
    -- those.
    IF NOT COALESCE(owner_is_vendor, FALSE) THEN
        RETURN NEW;
    END IF;

    -- id <> NEW.id so an UPDATE that does not move the row cannot trip over
    -- itself. Counts every user, active or not: deactivating the first account
    -- and adding a second is still two logins for a business that may have one.
    SELECT COUNT(*)
      INTO sibling_count
      FROM users u
     WHERE u.client_id = NEW.client_id
       AND u.id <> NEW.id;

    IF sibling_count > 0 THEN
        RAISE EXCEPTION
            'vendor client % already has a user account; vendors have exactly one', NEW.client_id
            USING ERRCODE = 'unique_violation',
                  CONSTRAINT = 'uq_users_one_account_per_vendor';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_vendor_single_account
    BEFORE INSERT OR UPDATE OF client_id ON users
    FOR EACH ROW
    EXECUTE FUNCTION enforce_vendor_single_account();
