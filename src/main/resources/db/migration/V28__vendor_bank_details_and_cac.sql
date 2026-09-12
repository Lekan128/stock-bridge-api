-- Bank account details and CAC registration number on both kinds of vendor.
--
-- ============================================================================
-- WHY BOTH TABLES, AND WHY THE SAME FOUR COLUMNS TWICE
-- ============================================================================
-- "Vendor" names two unrelated rows in this schema and V11__vendors.sql spells
-- out the distinction at length:
--
--   clients (client_type = 'VENDOR')  a ProcurePaddy SELLER - an account, a
--                                     catalogue, an order queue, and money we
--                                     owe them through vendor_ledger_entries.
--   company_vendors                   one BUYER's private note about a
--                                     supplier, which may point at such a
--                                     seller (VERIFIED) or at somebody with no
--                                     account anywhere (EXTERNAL).
--
-- Both genuinely need these fields and they are NOT the same fact. A seller's
-- account is where ProcurePaddy sends a payout; a company_vendors row's account
-- is where that one buyer sends their own off-platform payment, and the buyer
-- may well hold different details from the ones we hold. Sharing one set of
-- columns through the platform_client_id pointer would mean a VERIFIED row
-- showing the buyer OUR banking relationship with the seller, which is not the
-- buyer's to see. Two independent sets, deliberately.
--
-- ============================================================================
-- WHY FOUR COLUMNS AND NOT ONE TEXT BLOB
-- ============================================================================
-- Split so an account number can be validated, searched and one day written
-- into a payout file without re-parsing prose. Nigerian NUBAN account numbers
-- are exactly 10 digits, but no CHECK enforces that here: these fields are
-- optional and partially-filled records are the normal state of a directory
-- somebody is still building, so a half-typed number must be storable. The
-- length caps below are the only row-local truth worth enforcing.
--
-- ============================================================================
-- WHY EVERYTHING IS NULLABLE, INCLUDING ON clients
-- ============================================================================
-- Both are additive and optional per the product decision. Every existing row
-- predates these columns and no backfill could invent a correct value, so a
-- NOT NULL with a default would only manufacture a blank string that reads as
-- "we have their details" when we do not. NULL means "not on file"; that is a
-- distinct and useful state and the UI renders it as an em dash.

-- ============================================================================
-- clients: the ProcurePaddy seller's own details.
-- ============================================================================
ALTER TABLE clients
    ADD COLUMN bank_name           VARCHAR(255),
    ADD COLUMN bank_account_number VARCHAR(50),
    ADD COLUMN bank_account_name   VARCHAR(255),
    -- Corporate Affairs Commission registration number ("RC 123456"). VARCHAR
    -- rather than a digits-only column: CAC numbers are written with and
    -- without the RC prefix, and rejecting a real one over formatting would be
    -- a self-inflicted wound - the same judgement every phone column here makes.
    ADD COLUMN cac_number          VARCHAR(50);

COMMENT ON COLUMN clients.bank_account_number IS
    'Where ProcurePaddy pays this seller out. Optional, unvalidated - see V28 header.';
COMMENT ON COLUMN clients.cac_number IS
    'Corporate Affairs Commission registration number. Optional - see V28 header.';

-- ============================================================================
-- company_vendors: one buyer's own record of how they pay this supplier.
-- ============================================================================
ALTER TABLE company_vendors
    ADD COLUMN bank_name           VARCHAR(255),
    ADD COLUMN bank_account_number VARCHAR(50),
    ADD COLUMN bank_account_name   VARCHAR(255),
    ADD COLUMN cac_number          VARCHAR(50);

COMMENT ON COLUMN company_vendors.bank_account_number IS
    'Where THIS BUYER pays this supplier. Never sourced from the seller''s own clients row - see V28 header.';
COMMENT ON COLUMN company_vendors.cac_number IS
    'Corporate Affairs Commission registration number, as the buyer holds it. Optional - see V28 header.';

-- ============================================================================
-- (client_id, occurred_at): the stock in/out report's access path
-- ============================================================================
-- Unrelated to the columns above and in this file only because it is the next
-- migration. The new report at GET /api/stock/movements answers "what did we
-- take in and give out between these two dates" for a whole tenant, and the
-- analytics summary behind the dashboard's Stock In/Out Value cards now asks
-- the same question over the same column - both were previously served by
-- idx_stock_movements_client_id alone, which narrows to the tenant and then
-- leaves Postgres to filter every movement that tenant has ever recorded.
--
-- WHY occurred_at AND NOT created_at: the range being asked about is a range of
-- real-world dates - when a delivery arrived, when a sale happened - not of
-- data-entry timestamps. They were the same thing until bulk stock-in made
-- backdating ordinary (BULK_IMPORT_DESIGN.md section 8.4), and last month's
-- deliveries keyed in today belong in last month's report. The FIFO read
-- already made this switch for the same reason; the reporting reads now match
-- it, which is also what lets the dashboard card and the report that drills
-- into it agree on a total.
--
-- Supersedes idx_stock_movements_client_id, whose single column is this index's
-- leading column - same reasoning V20 gives when it drops
-- idx_stock_movements_product_id in favour of a wider index starting with it.
CREATE INDEX idx_stock_movements_client_id_occurred_at
    ON stock_movements (client_id, occurred_at DESC);

DROP INDEX IF EXISTS idx_stock_movements_client_id;
