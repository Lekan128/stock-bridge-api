-- Multi-vendor: opening selling up beyond ProcurePal, and giving every buying
-- company a vendor directory of its own.
--
-- ============================================================================
-- TWO THINGS ARE CALLED "VENDOR" AND THEY ARE NOT THE SAME THING
-- ============================================================================
-- The word is overloaded in the product, so it is disambiguated here once and
-- every name below follows it. Getting these two confused is the single most
-- likely way to corrupt this feature, so they never share a table, a column
-- prefix, or a Java type.
--
--   PLATFORM VENDOR (a seller)
--     An account that sells on the marketplace. It is a clients row, exactly
--     like ProcurePal is, distinguished by clients.client_type = 'VENDOR'.
--     It has products, an order queue, sales analytics and pickup addresses.
--     Created only by a super admin - from a vendor_waitlist_applications row
--     or directly. Referred to in SQL as a "platform vendor" or a "seller",
--     and pointed at by orders.seller_client_id.
--
--   COMPANY VENDOR (a directory entry)
--     A row in ONE buying company's own list of "who we buy from", in the new
--     company_vendors table. Tenant-scoped to the buyer. Either VERIFIED (the
--     company really did buy from that platform vendor, so the row points at
--     the seller's clients id and the company may not edit it) or EXTERNAL
--     (somebody the company deals with off-platform entirely, typed in by
--     hand). This is bookkeeping about a supplier relationship; it grants
--     nobody the ability to sell anything.
--
-- ProcurePal keeps clients.is_platform_owner = TRUE and is now one seller among
-- several rather than the only one. is_platform_owner and client_type stay
-- ORTHOGONAL on purpose - see the clients section for why.
--
-- ============================================================================
-- THE CONSTRAINT THIS SCHEMA IS BUILT AROUND: ONE ORDER PER SELLER
-- ============================================================================
-- A cart may hold products from several sellers. At checkout it SPLITS into one
-- orders row per seller, because an order is a fulfilment contract with one
-- counterparty: one dispatcher, one stock deduction, one status timeline, one
-- payout. An order that spans two sellers would have to be half-dispatched, and
-- every status transition in OrderStatus would need qualifying by seller.
-- That is why seller_client_id lives on orders and NOT on order_items - see
-- the orders section.
--
-- ============================================================================
-- CONVENTIONS AND BACKWARD COMPATIBILITY
-- ============================================================================
-- Carried over from V1/V6: UUID PKs via gen_random_uuid(), TIMESTAMPTZ
-- everywhere, the set_updated_at() trigger on every table with updated_at,
-- explicit indexes on foreign keys, and CHECK constraints for the enum-ish text
-- columns (the app maps them to Java enums; the CHECK is what stops a bad
-- migration or a psql session putting an unmappable value in a column).
--
-- Like V5, V6 and V7, every statement here is written to be safe on a database
-- that has already had the demo seed applied. On a fresh database Flyway runs
-- V1..V11 and then db/seed/V9000+; on a local database seeded before this file
-- existed the seed has already run and this arrives "out of order" behind it
-- (spring.flyway.out-of-order in application-local.yml). Both orderings have to
-- produce the same schema and the same rows, which is why every new column has
-- a default that suits the rows already there, the one backfill is expressed as
-- "whatever exists right now", and every insert is guarded.
--
-- Role/permission grants are ADDITIVE (V7's form), not V5/V6's
-- delete-and-restate. V7 states the reasoning in full and it applies with more
-- force here: restating the matrix to add three rows makes the correctness of
-- forty existing grants depend on transcribing them perfectly, and a dropped
-- line silently revokes a permission with no error and no failing migration.

-- ============================================================================
-- clients: what kind of account this is, plus the profile a platform vendor
-- shows on the storefront.
--
-- WHY client_type AND NOT is_vendor
-- A second boolean beside is_platform_owner invites states that cannot mean
-- anything: is_vendor = TRUE together with "can place orders", or two booleans
-- both FALSE, or a future third kind needing a third boolean and 2^3 states of
-- which five are nonsense. The restrictions this feature introduces are
-- type-driven and mutually exclusive by nature - a VENDOR sells and cannot buy
-- or create staff, a COMPANY buys and can do both - so the column that decides
-- them should be able to hold exactly one answer at a time.
--
-- WHY is_platform_owner STAYS, AND STAYS ORTHOGONAL
-- It is tempting to fold ProcurePal in as client_type = 'PLATFORM_OWNER'. That
-- is wrong: is_platform_owner does not describe what the account may DO, it
-- names the single account that owns the platform - it is who runs the
-- marketplace, sets marketplace_settings, holds the super-admin-adjacent
-- surfaces and, incidentally, also sells. ProcurePal is therefore
-- client_type = 'COMPANY' (it buys, it has staff, it has users) AND
-- is_platform_owner = TRUE. Merging them would either strip ProcurePal of the
-- ordinary-tenant behaviour it relies on, or hand every future vendor the
-- platform-owner surfaces. The partial unique index from V6 still guarantees at
-- most one platform owner, and it is untouched here.
--
-- The default of 'COMPANY' is what makes this backward compatible: every row
-- that exists right now - ProcurePal, the demo tenant, every real signup - is a
-- buying company and keeps behaving exactly as it did.
-- ============================================================================
ALTER TABLE clients
    ADD COLUMN client_type VARCHAR(20) NOT NULL DEFAULT 'COMPANY',
    -- The vendor's profile picture / logo. TEXT, matching products.image_url:
    -- these are S3 URLs whose length is not ours to bound. Nullable - a vendor
    -- with no logo renders a placeholder, it does not fail to render.
    --
    -- This is one of only TWO fields a browsing company sees about a vendor
    -- (the other is clients.name), plus their products. Everything else on this
    -- row - phone, email, address, payment terms - is operator-facing.
    ADD COLUMN logo_url    TEXT,
    -- The vendor's registered/business address.
    --
    -- Structured rather than one free-text blob, and named to match
    -- delivery_addresses column for column, so the same frontend address
    -- controls and the same Nigerian-state list serve both. No country column,
    -- for the reason V6 gives at delivery_addresses: this is Nigeria-only, and
    -- state is one of the 36 states + FCT.
    --
    -- NOT a foreign key to delivery_addresses, which was considered first and
    -- rejected. delivery_addresses answers "where do we ship goods to", carries
    -- NOT NULL label/contact_name/contact_phone that a registered address does
    -- not have, and is soft-deletable; a business's address of record is
    -- profile data that must exist from the moment the account does, before any
    -- delivery address has been created. Vendors DO get delivery_addresses too,
    -- reused as-is for their PICKUP addresses - a vendor is a clients row, so
    -- that table is already tenant-scoped to them and needs no new table. The
    -- two are different facts and stay in different places.
    --
    -- All nullable: a super admin adding a vendor directly may not have the
    -- address to hand, and no existing client has one at all.
    ADD COLUMN address_line1 VARCHAR(255),
    ADD COLUMN address_line2 VARCHAR(255),
    ADD COLUMN city          VARCHAR(100),
    ADD COLUMN state         VARCHAR(100),
    ADD CONSTRAINT chk_clients_client_type CHECK (client_type IN ('COMPANY', 'VENDOR'));

-- The vendor's contact number is clients.phone, added by V6. Deliberately NOT
-- duplicated as a vendor_phone: it is the same fact (how to reach this account)
-- and a second column would immediately be able to disagree with the first.

-- ----------------------------------------------------------------------------
-- admin_contact_email becomes nullable, for VENDOR rows only.
--
-- The product rule is that a vendor's email is REQUIRED when they came through
-- the waitlist (it is the address we replied to and the only identifier we had
-- for them) and OPTIONAL when a super admin adds them directly (typically
-- somebody signed up in person, over the phone, or through a trade contact).
-- V1 declared this column NOT NULL because until now every clients row was a
-- self-signup whose owner typed an email in.
--
-- Relaxing a NOT NULL is backward compatible - every existing row keeps its
-- value and nothing that reads them changes - and the CHECK below preserves the
-- old invariant exactly where it still holds: a COMPANY must still have a
-- contact of record. That address is where account correspondence, order
-- receipts and payment mail go (see EmailRecipients.forClient), so a company
-- without one would be unreachable.
--
-- The alternative - keeping NOT NULL and writing a synthetic placeholder for
-- vendors with no email - was rejected outright: it would put an address we
-- invented into the same column the mailer reads, and we would send real mail
-- to it.
-- ----------------------------------------------------------------------------
ALTER TABLE clients
    ALTER COLUMN admin_contact_email DROP NOT NULL,
    ADD CONSTRAINT chk_clients_company_has_contact_email
        CHECK (client_type <> 'COMPANY' OR admin_contact_email IS NOT NULL);

-- Vendors are listed on the storefront and enumerated by the super admin, both
-- of which are "give me the vendors" rather than "give me this one client".
-- Partial, like V6's marketplace index: only the vendor rows are ever in it, so
-- it stays small however many buying companies sign up.
CREATE INDEX idx_clients_client_type ON clients (client_type) WHERE client_type = 'VENDOR';

-- ============================================================================
-- orders.seller_client_id: WHO SOLD THIS.
--
-- Until now the answer was implicit - the platform owner was the only seller,
-- so the Order entity's javadoc said outright that the seller "is not stored".
-- That comment is now false and is corrected in Order.java as part of this
-- change; a stale comment contradicting the schema is how the next person
-- writes a bug.
--
-- ON DELETE RESTRICT, matching orders.client_id (the buyer) for the same
-- reason: an order is a financial record. Deleting a vendor must not erase what
-- they sold, what was paid for it, or who signed for it.
--
-- WHY NOT ON order_items TOO
-- Because of the split-at-checkout rule at the top of this file: a cart holding
-- two sellers' products produces two orders, so every line of any given order
-- shares that order's seller BY CONSTRUCTION. A seller_client_id on order_items
-- would be a copy of a value one join away that can drift from it, and a row
-- where the line's seller disagrees with the order's seller has no defined
-- meaning - is it dispatched by the seller on the header or the one on the
-- line? Postgres cannot CHECK the agreement (it needs a join), so the only
-- thing preventing the contradiction would be the same service code that would
-- have had to write the column correctly in the first place. The column is
-- therefore deliberately absent, and "which seller is this line from" is
-- answered by order_items -> orders -> seller_client_id.
--
-- If per-line sellers ever become real - a consolidated invoice across sellers,
-- say - that is a change to what an ORDER is, not a column bolted onto the
-- lines, and it starts by revisiting the split-at-checkout decision.
-- ============================================================================
ALTER TABLE orders
    ADD COLUMN seller_client_id UUID REFERENCES clients (id) ON DELETE RESTRICT;

-- Backfill: every order that exists today was sold by ProcurePal, because
-- ProcurePal was the only seller there has ever been (V6's orders comment and
-- the Order javadoc both state it). On a fresh database this matches zero rows
-- and is a no-op; on a seeded local or a production database it fills in every
-- historical row.
UPDATE orders
SET seller_client_id = (SELECT id FROM clients WHERE is_platform_owner LIMIT 1)
WHERE seller_client_id IS NULL;

-- NOT NULL only after the backfill, so the column is honest from here on: every
-- order has a seller and no query needs a NULL branch.
--
-- If this statement ever fails, it means a database contains orders while
-- containing no platform owner - a state that should be impossible, since
-- orders could only ever have been placed against the platform owner's catalog.
-- Failing loudly is correct: silently leaving the column nullable would hide a
-- corrupt database and push the NULL handling into every seller query forever.
ALTER TABLE orders
    ALTER COLUMN seller_client_id SET NOT NULL;

-- A vendor's own order queue is "my orders, newest first, optionally filtered by
-- status" - the same shape ProcurePal's fulfilment queue already has, but
-- narrowed to one seller. Two indexes for the two hot reads, mirroring the
-- buyer-side pair V6 created on client_id.
CREATE INDEX idx_orders_seller_client_id ON orders (seller_client_id);
CREATE INDEX idx_orders_seller_client_id_created_at ON orders (seller_client_id, created_at DESC);

-- ============================================================================
-- vendor_waitlist_applications: businesses asking to sell on the marketplace.
--
-- NOT TENANT-SCOPED, and that is the requirement rather than an oversight. An
-- applicant is by definition not a tenant yet - there is no clients row to scope
-- them to, and the form is submitted from a public page with no TenantContext at
-- all, where a tenant-aware entity's @PrePersist would refuse to persist. It is
-- read by super admins, who are not tenant principals either. Same reasoning
-- V6 gives for payments/payment_webhook_events and V10 for email_suppressions.
--
-- Approval creates the clients row (client_type = 'VENDOR') and approved_client_id
-- points at it, which is what keeps "where did this vendor come from" answerable
-- afterwards. That linkage is also why email is NOT NULL here while it is
-- nullable on clients: an applicant reached us through a form and the address
-- they left is the only way to reply to them, whereas a super admin adding a
-- vendor directly may genuinely have no email for them.
--
-- No unique constraint on email or business_name: a rejected applicant may
-- legitimately reapply with better information, and two unrelated businesses can
-- share a shopfront email. De-duplication is a reviewer's judgement, not a
-- constraint that would silently swallow the second application.
-- ============================================================================
CREATE TABLE vendor_waitlist_applications (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name VARCHAR(255) NOT NULL,
    -- Required, unlike clients.admin_contact_email - see above.
    email         VARCHAR(255) NOT NULL,
    contact_phone VARCHAR(50)  NOT NULL,
    -- Same four columns, same names and widths, as clients and company_vendors.
    -- Nullable: an applicant who leaves the address blank should still reach the
    -- reviewer, who can ask for it. Nigeria-only, as everywhere else.
    address_line1 VARCHAR(255),
    address_line2 VARCHAR(255),
    city          VARCHAR(100),
    state         VARCHAR(100),
    -- What the applicant told us about themselves. Free text, deliberately not
    -- parsed into anything.
    notes         VARCHAR(1000),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    -- The reviewer's note, shown in the admin UI and quoted in the reply mail.
    -- Kept for APPROVED as well as REJECTED: "approved, but only for packaging"
    -- is a real outcome someone will need to read back later.
    review_note   VARCHAR(500),
    -- The super admin who decided. A super_admins row, NOT a users row: this is
    -- a platform-operator action and super admins are an entirely separate
    -- identity from tenant users (see V1). SET NULL so an operator leaving the
    -- company does not delete the decision they made.
    reviewed_by   UUID REFERENCES super_admins (id) ON DELETE SET NULL,
    reviewed_at   TIMESTAMPTZ,
    -- The clients row created on approval. Nullable because it does not exist
    -- until then. SET NULL rather than CASCADE: if a vendor account is ever
    -- deleted, the record that somebody applied and was approved must survive.
    approved_client_id UUID REFERENCES clients (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_vendor_waitlist_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    -- An approved application MUST name the account it created. Without this,
    -- "approved" could mean either "we made them a vendor" or "we meant to and
    -- never did", and nothing downstream could tell the two apart.
    CONSTRAINT chk_vendor_waitlist_approved_has_client
        CHECK (status <> 'APPROVED' OR approved_client_id IS NOT NULL),
    -- Conversely, a decision that has not been made cannot carry the trappings
    -- of one. This is what stops a half-written update leaving a PENDING row
    -- that looks reviewed.
    CONSTRAINT chk_vendor_waitlist_pending_is_unreviewed
        CHECK (status <> 'PENDING'
            OR (reviewed_by IS NULL AND reviewed_at IS NULL AND approved_client_id IS NULL))
);

-- The review queue: "PENDING first, oldest first" is the only list a super admin
-- ever wants, and the status filter is the only filter.
CREATE INDEX idx_vendor_waitlist_status_created_at ON vendor_waitlist_applications (status, created_at);
CREATE INDEX idx_vendor_waitlist_reviewed_by ON vendor_waitlist_applications (reviewed_by);
CREATE INDEX idx_vendor_waitlist_approved_client_id ON vendor_waitlist_applications (approved_client_id);

CREATE TRIGGER trg_vendor_waitlist_applications_set_updated_at
    BEFORE UPDATE ON vendor_waitlist_applications
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- company_vendors: ONE BUYING COMPANY'S OWN LIST OF WHO IT BUYS FROM.
--
-- Tenant-scoped (client_id = the owning buyer), because this is that company's
-- private bookkeeping. Two companies buying from the same platform vendor get
-- two rows, and neither can see the other's - which is right, because the row
-- also carries that company's own purchase relationship with the vendor.
--
-- WHY ONE TABLE WITH A KIND, AND NOT TWO TABLES
-- The two kinds share every user-facing behaviour: they appear in one list, are
-- sorted and searched together, are linked from products the same way, and both
-- answer "what did we last pay this supplier". Splitting them would mean a
-- UNION in every read and two nullable FKs on products. The differences are
-- entirely about where the row's DATA comes from and who may edit it, and those
-- are exactly what the CHECK constraints below pin down.
--
--   VERIFIED - the company has actually bought from this platform vendor.
--              Created automatically at purchase, never by hand. Points at the
--              seller's clients row. The company may not edit it, because the
--              row asserts a fact about the platform ("you traded with them")
--              rather than a note the company wrote.
--   EXTERNAL - somebody the company deals with off-platform: the local miller,
--              the diesel supplier. Typed in and owned entirely by the company.
--              Has no clients row anywhere and must not pretend to.
--
-- WHY name IS A PLAIN NOT NULL COLUMN FOR BOTH KINDS
-- A VERIFIED row could have left name NULL and read it through
-- platform_client_id, since clients is not tenant-scoped and the join is cheap.
-- It does not, because the directory list is sorted, searched and paged by name,
-- and a nullable column plus a join means every one of those becomes a
-- COALESCE over an OUTER JOIN that no index can serve. One authoritative name
-- column keeps the list one indexable predicate.
--
-- The cost is real and is stated here so nobody discovers it by surprise: if a
-- platform vendor renames itself, every VERIFIED row naming it goes stale until
-- something refreshes it. Keeping them in step is the job of whatever handles a
-- vendor profile update (a later module) - it must re-write company_vendors.name
-- for rows pointing at that vendor. It is NOT the buyer's job: the company
-- cannot edit a VERIFIED row at all.
-- ============================================================================
CREATE TABLE company_vendors (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The BUYER who owns this directory entry. CASCADE, like every other
    -- tenant-scoped table: a company's private supplier list has no meaning
    -- once the company is gone.
    client_id          UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    vendor_kind        VARCHAR(20) NOT NULL,
    -- The platform vendor (or ProcurePal) this entry refers to, for VERIFIED
    -- rows. NULL for EXTERNAL, and the CHECKs below make that mandatory rather
    -- than merely allowed.
    --
    -- RESTRICT, not CASCADE or SET NULL. CASCADE would delete a buyer's record
    -- of a supplier relationship - including its purchase history - as a side
    -- effect of an operator removing a vendor account. SET NULL would leave a
    -- VERIFIED row with no target, which chk_company_vendors_verified_shape
    -- forbids, so the delete would fail anyway - with a confusing CHECK
    -- violation instead of a clear FK one.
    platform_client_id UUID REFERENCES clients (id) ON DELETE RESTRICT,
    -- Required for both kinds. See the block comment above for why this is
    -- stored rather than read through platform_client_id.
    name               VARCHAR(255) NOT NULL,
    -- Required for EXTERNAL (enforced below), optional for VERIFIED where the
    -- authoritative number is the vendor's own clients.phone.
    contact_phone      VARCHAR(50),
    email              VARCHAR(255),
    -- Same four address columns, same names and widths, as clients and
    -- vendor_waitlist_applications. Optional for both kinds. Nigeria-only.
    address_line1      VARCHAR(255),
    address_line2      VARCHAR(255),
    city               VARCHAR(100),
    state              VARCHAR(100),
    -- The company's own free-text note about this supplier. Never shown to the
    -- vendor.
    notes              VARCHAR(1000),
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_company_vendors_kind CHECK (vendor_kind IN ('VERIFIED', 'EXTERNAL')),

    -- The two kinds, made coherent in the database rather than in service code.
    --
    -- These are worth being CHECK constraints - unlike, say, "only the platform
    -- owner may list a product", which V6 had to leave to service code because
    -- it needs a join - precisely because both conditions are ROW-LOCAL. There
    -- is no excuse for enforcing them anywhere weaker, and the failure mode if
    -- they were left to service code is silent: a VERIFIED row with no
    -- platform_client_id looks like an ordinary directory entry and simply never
    -- links to the seller, and an EXTERNAL row carrying a platform_client_id
    -- claims a relationship with a real vendor account that the buyer typed in
    -- themselves. Neither would raise anything at write time.
    CONSTRAINT chk_company_vendors_verified_shape
        CHECK (vendor_kind <> 'VERIFIED' OR platform_client_id IS NOT NULL),
    CONSTRAINT chk_company_vendors_external_shape
        CHECK (vendor_kind <> 'EXTERNAL'
            OR (platform_client_id IS NULL AND contact_phone IS NOT NULL)),

    -- A company is not its own supplier. Cheap to state, and it closes the one
    -- way the auto-create-on-purchase path could produce a nonsense row (a
    -- seller that somehow also placed the order).
    CONSTRAINT chk_company_vendors_not_self
        CHECK (platform_client_id IS NULL OR platform_client_id <> client_id)
);

CREATE INDEX idx_company_vendors_client_id ON company_vendors (client_id);
CREATE INDEX idx_company_vendors_platform_client_id ON company_vendors (platform_client_id);
-- The directory list: this company's vendors, alphabetical.
CREATE INDEX idx_company_vendors_client_id_name ON company_vendors (client_id, name);

-- One VERIFIED entry per (buyer, platform vendor). The auto-create on purchase
-- is a find-or-create and runs on every order; without this, a company that
-- buys from the same vendor weekly accumulates a duplicate row per order.
--
-- A partial unique INDEX rather than a plain UNIQUE constraint, matching V6's
-- uq_products_client_id_slug. A plain UNIQUE would happen to work today, since
-- Postgres treats NULLs as distinct and EXTERNAL rows are all NULL here - but
-- that is a default (NULLS DISTINCT) rather than something this schema states,
-- and the partial form says what is actually meant: the rule applies to rows
-- that name a platform vendor, and to no others.
--
-- Deliberately NO equivalent uniqueness on (client_id, name) for EXTERNAL rows.
-- A company may genuinely deal with two suppliers of the same name, and
-- rejecting the second one at the database level - after they have typed it -
-- would be the schema overruling a fact about their business.
CREATE UNIQUE INDEX uq_company_vendors_client_id_platform_client_id
    ON company_vendors (client_id, platform_client_id)
    WHERE platform_client_id IS NOT NULL;

CREATE TRIGGER trg_company_vendors_set_updated_at
    BEFORE UPDATE ON company_vendors
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- products.company_vendor_id: which supplier a buyer's own inventory item came
-- from.
--
-- Set automatically to the VERIFIED entry for the seller when goods arrive from
-- a marketplace order, and settable by hand to an EXTERNAL entry for stock the
-- company sourced off-platform. This is what makes "last purchase price" and
-- "purchase history per vendor" answerable from the buyer's own catalog.
--
-- Same-tenant by construction - a product and its company_vendors row both
-- belong to the buyer - which is why, unlike products.source_product_id, this
-- one IS safe to map as a JPA association. source_product_id points at the
-- SELLER's product row and therefore crosses tenants, so it stays a raw UUID;
-- the precedent for the same-tenant case is DeliveryAddress.branch.
--
-- Nullable, and it stays nullable forever: most inventory rows have no vendor
-- attached, including everything that exists today, and a product whose vendor
-- was deleted is still a product.
--
-- SET NULL rather than RESTRICT: removing a supplier from the directory should
-- not be blocked by, or destroy, the stock the company bought from them.
-- ============================================================================
ALTER TABLE products
    ADD COLUMN company_vendor_id UUID REFERENCES company_vendors (id) ON DELETE SET NULL;

CREATE INDEX idx_products_company_vendor_id ON products (company_vendor_id);

-- ============================================================================
-- products: MODERATION.
--
-- Columns now, workflow later - and that ordering is the whole point of putting
-- them here. The queue, the reviewer screen, the rejection mail and the
-- resubmission loop belong to the module that opens listing up to vendors.
-- Adding these four columns at that point, once vendor product rows already
-- exist, is a far more expensive change than adding them here: it would need a
-- second backfill over live vendor data, decided against a catalogue somebody is
-- already selling from, instead of the one-line backfill below over rows that all
-- have the same honest answer. Nothing in this migration READS these columns, so
-- the application's behaviour is unchanged today.
--
-- WHY THE DEFAULT IS PENDING BUT THE BACKFILL IS APPROVED
-- They answer different questions. The backfill is about rows that already
-- exist: every one of them predates third-party selling, so every one of them is
-- either a buying company's own inventory or the platform owner's catalogue, and
-- APPROVED is simply true of them. The default is about rows written from now on,
-- where the dangerous case is a vendor's brand-new listing, and a default of
-- APPROVED would mean a vendor product reaching a real buying company's purchase
-- order because a moderation module had not been built yet. Fail closed:
-- VENDOR_RESEARCH.md Section C item 4 is explicit that an unmoderated vendor
-- listing in a B2B procurement catalog is somebody's real purchase order.
--
-- The accepted cost, stated so nobody finds it by surprise: an ordinary buying
-- company's own inventory rows will now be written as PENDING, which is
-- meaningless for them - nobody moderates a company's private stock list. That
-- is noise, not a bug, and it is the cheaper of the two mistakes. The rule the
-- later module must implement is that approval_status is only ever CONSULTED for
-- a product being listed on the marketplace, and that the platform owner's own
-- products are auto-approved (ProcurePal does not queue behind itself).
-- ============================================================================
ALTER TABLE products
    ADD COLUMN approval_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- Why a reviewer said no, shown to the vendor so they can fix and resubmit.
    -- Jumia rejects for image quality and prohibited items; a rejection with no
    -- reason attached just produces a support ticket. Kept after a later
    -- approval rather than cleared, so the history of a contested listing
    -- survives.
    ADD COLUMN rejection_reason VARCHAR(1000),
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    -- The super admin who decided - a super_admins row, not a users row, for the
    -- same reason vendor_waitlist_applications.reviewed_by is: moderating a
    -- listing is a platform-operator action. SET NULL so an operator leaving does
    -- not delete the decisions they made.
    ADD COLUMN reviewed_by UUID REFERENCES super_admins (id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_products_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));

-- Everything that exists right now predates third-party selling - see above.
-- On a database seeded before this file existed, this approves the demo
-- catalogue so the storefront keeps rendering exactly what it rendered yesterday.
--
-- On a FRESH database it matches zero rows, because db/seed/V9000+ has not run
-- yet - and the demo products it goes on to insert would then take the PENDING
-- default instead, which is the ordering divergence this file's header promises
-- not to produce. db/seed/V9003__approve_seeded_products.sql closes that, in the
-- same way and for the same reason V9002 closes V8's identical trap for email
-- verification. If you change either the default or the backfill above, read
-- that file before you do.
UPDATE products SET approval_status = 'APPROVED';

-- The moderation queue is "PENDING, oldest first", and it is the only read that
-- will filter on this column. Partial, like the other queue indexes here: once
-- the platform is working properly almost every row is APPROVED, and an index
-- over those is dead weight.
CREATE INDEX idx_products_approval_status_created_at
    ON products (approval_status, created_at)
    WHERE approval_status <> 'APPROVED';

CREATE INDEX idx_products_reviewed_by ON products (reviewed_by);

-- ============================================================================
-- COMMISSION: the rate the platform takes on a vendor's sale.
--
-- Columns now, engine later, for exactly the reason the moderation columns are
-- here: the ledger, the payout batch and the accrual job are a later module, but
-- the per-line rate has to be captured from the very first vendor sale or that
-- sale can never be settled correctly afterwards.
--
-- WHY THE RATE IS RECORDED TWICE
-- clients.commission_rate is the CURRENT agreement with a vendor - editable,
-- and the thing a super admin negotiates. order_items.commission_rate is what
-- was agreed AT THE MOMENT THAT LINE WAS SOLD, frozen. Without the second,
-- renegotiating a vendor's rate would retroactively rewrite what the platform
-- earned on every order they had ever shipped, and any statement printed before
-- the change would stop reconciling with any statement printed after it. It is
-- the same snapshotting order_items already does for unit_price and
-- product_name, and it exists for the same reason: an invoice must keep saying
-- what it said.
--
-- WHY THERE IS NO commission_amount COLUMN, AND THIS IS DELIBERATE
-- VENDOR_RESEARCH.md Section C item 2 records the decision that commission
-- ACCRUES ON DELIVERY, not at checkout and not at collection - Jumia deducts
-- commission only on successful delivery, and an order that is cancelled or
-- returned must never have earned the platform anything. So the amount is a
-- consequence of an event (the DELIVERED transition) rather than a property of
-- the line, and it belongs in the append-only vendor ledger that the payout
-- module builds, where a return can post a reversing entry against it. Storing a
-- computed amount on the line would create a second place for money to live and
-- a way for the two to disagree after the first refund.
-- ============================================================================
ALTER TABLE clients
    -- The platform's take on this vendor's sales, as a fraction: 0.0750 is 7.5%.
    -- NUMERIC(5,4) holds four decimal places, which is a quarter of a basis
    -- point - finer than any rate anyone will negotiate, and exact, because
    -- floating point has no business anywhere near money.
    --
    -- NULLABLE, and the null means something specific: "no vendor-specific rate
    -- has been agreed, use the platform default". It is not zero. A zero rate is
    -- a real and different arrangement - a vendor onboarded commission-free as an
    -- incentive - and collapsing the two would make "we agreed they pay nothing"
    -- indistinguishable from "nobody has decided yet". Where the default itself
    -- lives (marketplace_settings, or per-category rules as VENDOR_RESEARCH
    -- Section A suggests) is the later module's decision, not this one's.
    --
    -- On a COMPANY row it is simply null and meaningless, like logo_url. No CHECK
    -- ties it to client_type: a super admin negotiating a rate with a business
    -- before flipping them to VENDOR is an ordinary sequence, and a constraint
    -- forbidding it would buy nothing.
    ADD COLUMN commission_rate NUMERIC(5, 4),
    -- A rate outside [0, 1] is always a data-entry error - somebody typing 7.5
    -- meaning 7.5%, which would hand the platform 750% of the sale. Cheap to
    -- state, and the alternative is discovering it in a payout run.
    ADD CONSTRAINT chk_clients_commission_rate_fraction
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1));

ALTER TABLE order_items
    -- The rate in force when this line was sold, resolved from the seller's
    -- clients.commission_rate (or the platform default) at that moment and never
    -- touched again.
    --
    -- Nullable because every row that exists today has no answer and inventing
    -- one would be asserting a commercial fact that was never agreed: these are
    -- ProcurePal's own sales, where the platform and the seller are the same
    -- party and commission is not a concept. Reads must treat NULL as "no
    -- commission applies", never as zero-by-coincidence.
    ADD COLUMN commission_rate NUMERIC(5, 4),
    ADD CONSTRAINT chk_order_items_commission_rate_fraction
        CHECK (commission_rate IS NULL OR (commission_rate >= 0 AND commission_rate <= 1));

-- ============================================================================
-- The VENDOR role.
--
-- Roles are global rows (V1: "Not tied to a client_id - global and not
-- client-editable"), so VENDOR sits alongside the five tenant roles rather than
-- in a parallel table. What keeps it from being handed to an ordinary company's
-- staff is that it is NOT in TenantRoles.ALL, which is the allow-list both
-- UserManagementService and SuperAdminUserService validate a requested role
-- against - and it is not served by GET /api/roles either, which is a picker of
-- ASSIGNABLE roles. A vendor's single user gets this role only from the
-- super-admin vendor-creation path.
--
-- WHY A ROLE AT ALL, RATHER THAN DERIVING PERMISSIONS FROM client_type
-- Because the whole authorization stack - the JWT's authorities, @PreAuthorize,
-- PermissionCodes.of(user), /api/me - reads permissions off the user's role.
-- A type-derived permission set would be a second, parallel source of truth for
-- "what can this user do", and the two would eventually disagree. client_type
-- decides which surfaces exist (VendorGuard); the role decides what the user may
-- do on them. Same two-gate shape as PlatformOwnerGuard, for the same reason.
-- ============================================================================
INSERT INTO roles (name, description) VALUES
    ('VENDOR', 'A marketplace seller''s single account. Manages its own catalogue, stock, orders and sales analytics. Cannot buy, and cannot create users.')
ON CONFLICT (name) DO NOTHING;

-- ============================================================================
-- Permissions for the buyer-side vendor directory.
--
-- A MANAGE/VIEW pair rather than one code, following the MANAGE_PRODUCTS /
-- VIEW_PRODUCTS split V5 introduced and for the same reason: seeing who the
-- company buys from and what it last paid them is reporting, while adding or
-- editing a supplier is a change to the company's records. A role can need the
-- first without the second.
--
-- Note these are BUYER-side codes. They say nothing about selling, and the
-- VENDOR role below gets neither of them - a platform vendor has no supplier
-- directory of its own, because it does not buy.
-- ============================================================================
INSERT INTO permissions (code, description) VALUES
    ('VIEW_VENDORS',   'View the company''s vendor directory, including last purchase price and purchase history'),
    ('MANAGE_VENDORS', 'Add, edit and deactivate the company''s external vendors, and link products to them')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- VIEW_OWN_SALES_ANALYTICS: a seller's analytics over THEIR OWN sales.
--
-- WHY THIS IS NOT VIEW_MARKETPLACE_ANALYTICS
-- Because that code already means something else, and reusing it would be a
-- data leak dressed as a saving. V6 granted VIEW_MARKETPLACE_ANALYTICS to every
-- tenant's OWNER, PROCUREMENT_MANAGER and INVENTORY_OFFICER, and its description
-- there is "Platform owner: customer, revenue and fulfilment analytics" - the
-- WHOLE marketplace, every seller's revenue and every buyer's spend. What keeps
-- an ordinary company out of it today is PlatformOwnerGuard, not the permission.
--
-- Handing that same code to the VENDOR role would mean a third party's single
-- account holds the permission whose plain meaning is "see the entire
-- marketplace", and the only thing standing between them and a competitor's
-- revenue would be that every present and future analytics endpoint remembered
-- to apply a seller filter. VENDOR_RESEARCH.md Section C item 7 names this as
-- the boundary most likely to produce a commercial incident, and it is right:
-- a permission whose name overstates what its holder may see is a trap set for
-- whoever writes the next endpoint.
--
-- So the two codes stay distinct and mean exactly what they say. This one is
-- inherently scoped - "own sales" is in the name - and the endpoint that serves
-- it still has to pass the seller's id into a seller_client_id predicate.
--
-- WHO HOLDS IT, AND THE CONSEQUENCE FOR LATER MODULES
-- The VENDOR role only. Deliberately NOT granted to OWNER or
-- PROCUREMENT_MANAGER: giving it to every tenant would recreate the exact
-- property that makes VIEW_MARKETPLACE_ANALYTICS unusable here.
--
-- That has a consequence worth stating plainly, because it will otherwise be
-- discovered as a bug: ProcurePal is a seller too, and its staff do NOT hold
-- this code. An own-sales endpoint gated on this permission alone would 403 the
-- platform owner on its own sales figures. The intended shape is
--   @PreAuthorize("hasAnyAuthority('VIEW_OWN_SALES_ANALYTICS','VIEW_MARKETPLACE_ANALYTICS')")
-- plus VendorGuard.requireSeller() as the second gate - the same two-gate
-- arrangement every other marketplace surface uses, with the seller's own id
-- scoping the query.
-- ============================================================================
INSERT INTO permissions (code, description) VALUES
    ('VIEW_OWN_SALES_ANALYTICS', 'Seller: revenue, orders and top products for the holder''s OWN sales only')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- Grants, additive (see the header for why this file never restates the matrix).
--
-- BUYER-SIDE DIRECTORY
--   MANAGE_VENDORS -> OWNER, PROCUREMENT_MANAGER. Maintaining the supplier list
--     is procurement's job by definition, and PROCUREMENT_MANAGER already holds
--     the closest existing analogue (MANAGE_DELIVERY_ADDRESSES) - both are
--     operational records about who the company deals with and where things
--     move. OWNER holds everything its managers hold.
--   VIEW_VENDORS -> those two plus FINANCE_OFFICER and INVENTORY_OFFICER.
--     FINANCE_OFFICER is described by V5 as read-only over the catalogue and
--     analytics, and "what did we last pay this supplier" is precisely the
--     question that role exists to answer; it already holds VIEW_ORDERS for the
--     same reason. INVENTORY_OFFICER gets it because products now carry a
--     vendor, and an inventory officer looking at a stock item must be able to
--     see where it came from without that field rendering as a blank.
--     STOREKEEPER is excluded on V6's own logic: prices and spend are not their
--     business, and the directory is largely a view onto spend.
--
-- THE VENDOR ROLE
--   Selling side only, and every omission below is deliberate.
--
--   MANAGE_PRODUCTS / VIEW_PRODUCTS   - "can add their own products", "see their
--                                       own catalogue".
--   MANAGE_INVENTORY                  - a seller holds real stock and the
--                                       marketplace refuses orders that exceed
--                                       available stock, so a vendor who cannot
--                                       record what arrived cannot trade.
--   VIEW_ANALYTICS                    - the ordinary tenant-scoped inventory
--                                       reporting (/api/analytics), including
--                                       low-stock. Granting MANAGE_INVENTORY
--                                       without any way to see stock levels
--                                       would be a half-usable account.
--   MANAGE_MARKETPLACE                - list and unlist THEIR OWN products.
--                                       Note this code also covers categories
--                                       and marketplace_settings, which are
--                                       ProcurePal-only; VendorGuard plus the
--                                       existing PlatformOwnerGuard are what keep
--                                       a vendor off those. Holding the
--                                       permission is necessary, never
--                                       sufficient - the same arrangement V6
--                                       already relies on for every tenant OWNER.
--   MANAGE_MARKETPLACE_ORDERS         - "see orders placed with them" and advance
--                                       their status. Scoped to their own orders
--                                       by seller_client_id, not by this grant.
--   VIEW_OWN_SALES_ANALYTICS          - "analytics for their own sales", and a
--                                       NEW code rather than V6's
--                                       VIEW_MARKETPLACE_ANALYTICS, which means
--                                       the whole marketplace and which every
--                                       tenant OWNER already holds. See the
--                                       block above the permission insert.
--   MANAGE_DELIVERY_ADDRESSES         - "manage pickup addresses". Vendors reuse
--                                       delivery_addresses for those; see the
--                                       clients section above.
--   MANAGE_COMPANY_PROFILE            - a vendor's single user IS its account
--                                       holder, and V7 exists precisely so an
--                                       account holder can fix their own name,
--                                       phone and contact email without a support
--                                       ticket. Withholding it would recreate the
--                                       friction V7 removed, for the accounts
--                                       least able to absorb it.
--
--   NOT GRANTED, and each for a stated product reason:
--     MANAGE_USERS       - a vendor has exactly ONE user account and cannot
--                          create staff. This is the grant that would break that
--                          rule, so its absence is load-bearing, not incidental.
--     MANAGE_ROLES       - follows MANAGE_USERS.
--     PLACE_ORDERS       - vendors sell; they do not buy. The other grant whose
--                          absence is load-bearing.
--     BROWSE_MARKETPLACE - the buyer storefront. A vendor who cannot check out
--                          has no use for a cart, and showing them one would
--                          advertise a route that dead-ends.
--     VIEW_ORDERS        - buyer-side purchase history ("the company's
--                          marketplace purchase history", V6). A vendor's orders
--                          are the ones placed WITH them, which is
--                          MANAGE_MARKETPLACE_ORDERS.
--     RECEIVE_DELIVERIES - signing for goods bought from someone else.
--     VIEW_ALL_BRANCHES  - branches are a buying company's structure; a vendor
--                          has the one Head Office row every client gets.
--     VIEW_VENDORS /
--     MANAGE_VENDORS     - the buyer-side supplier directory, above. A vendor
--                          does not buy, so it has no supplier book.
--     VIEW_MARKETPLACE_ANALYTICS
--                        - the WHOLE marketplace, every seller's revenue.
--                          Replaced for this role by VIEW_OWN_SALES_ANALYTICS.
--                          This is the third omission that is load-bearing
--                          rather than incidental.
-- ============================================================================
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    -- Buyer-side vendor directory.
    ('OWNER',               'MANAGE_VENDORS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_VENDORS'),
    ('OWNER',               'VIEW_VENDORS'),
    ('PROCUREMENT_MANAGER', 'VIEW_VENDORS'),
    ('FINANCE_OFFICER',     'VIEW_VENDORS'),
    ('INVENTORY_OFFICER',   'VIEW_VENDORS'),
    -- The selling side.
    ('VENDOR',              'MANAGE_PRODUCTS'),
    ('VENDOR',              'VIEW_PRODUCTS'),
    ('VENDOR',              'MANAGE_INVENTORY'),
    ('VENDOR',              'VIEW_ANALYTICS'),
    ('VENDOR',              'MANAGE_MARKETPLACE'),
    ('VENDOR',              'MANAGE_MARKETPLACE_ORDERS'),
    ('VENDOR',              'VIEW_OWN_SALES_ANALYTICS'),
    ('VENDOR',              'MANAGE_DELIVERY_ADDRESSES'),
    ('VENDOR',              'MANAGE_COMPANY_PROFILE')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
