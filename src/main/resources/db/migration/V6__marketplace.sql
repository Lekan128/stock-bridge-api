-- ProcurePal marketplace: the schema behind "one platform owner sells to every
-- other tenant, and what they buy lands in their own inventory".
--
-- Two ideas drive almost every choice below.
--
-- 1. The platform owner is a tenant, not a special case. It is a clients row
--    with is_platform_owner = TRUE, so it keeps using products/stock_movements
--    exactly like everyone else and the marketplace catalog is just "the
--    platform owner's products that are flagged as listed". No parallel
--    catalog table, no second product concept to keep in sync.
-- 2. An order is a contract, so anything a buyer agreed to is SNAPSHOTTED onto
--    the order (address fields, product name/sku/price/unit) rather than read
--    back through a foreign key. Editing an address or renaming a product must
--    never rewrite the history of an order that already shipped.
--
-- Conventions carried over from V1: UUID PKs via gen_random_uuid(), TIMESTAMPTZ
-- everywhere, set_updated_at() trigger on every table that has updated_at,
-- explicit indexes on foreign keys, and CHECK constraints for the enum-ish text
-- columns (the app maps them to Java enums; the CHECK is what stops a bad
-- migration or a psql session putting an unmappable value in the column).
--
-- Like V5, every statement here is written to be safe on a database that has
-- already had the demo seed applied. On a fresh database Flyway runs
-- V1..V6 then db/seed/V9000+; on a local database that was seeded before this
-- file existed, V9000 has already run and this arrives "out of order" behind it
-- (see spring.flyway.out-of-order in application-local.yml). Both orderings
-- have to produce the same schema, which is why the backfills below are
-- expressed as "for whatever rows exist right now" and every insert is guarded.

-- ============================================================================
-- clients: the platform-owner flag, a phone number, and payment terms.
--
-- is_platform_owner is deliberately a flag on clients rather than a separate
-- table: everything a tenant can do, the platform owner can also do, so making
-- it a distinct entity would mean duplicating every tenant-scoped relationship.
--
-- payment_terms gates whether this company may choose pay-on-delivery at
-- checkout. It lives on the client (not on the order) because it is a
-- commercial relationship decision ProcurePal ops make about a customer, not a
-- per-order choice. PREPAID is the safe default: a brand-new signup has no
-- trading history, so it must pay before we ship.
-- ============================================================================
ALTER TABLE clients
    ADD COLUMN is_platform_owner BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN phone             VARCHAR(50),
    -- VARCHAR(30), not the 20 the contract sketched: 'PAY_ON_DELIVERY_ALLOWED'
    -- is 23 characters and would not fit.
    ADD COLUMN payment_terms     VARCHAR(30) NOT NULL DEFAULT 'PREPAID',
    ADD CONSTRAINT chk_clients_payment_terms
        CHECK (payment_terms IN ('PREPAID', 'PAY_ON_DELIVERY_ALLOWED'));

-- "At most one platform owner" enforced in the database, not just in service
-- code - a partial unique index, so the FALSE rows (every ordinary tenant) stay
-- unconstrained. Getting this wrong would silently split the public catalog in
-- two, which is exactly the kind of invariant that belongs in the DB.
CREATE UNIQUE INDEX uq_clients_single_platform_owner ON clients (is_platform_owner)
    WHERE is_platform_owner;

-- ============================================================================
-- branches: deliberately minimal. Branches exist as a business fact (a company
-- has a head office and maybe outlets) but they are NOT the focus of this pass
-- and there is no branch management UI yet.
--
-- Stock is intentionally NOT scoped per branch here: products.quantity_on_hand
-- stays client-wide. Per-branch stock is a real future step and would arrive as
-- a product_stock (product_id, branch_id, quantity) table plus a branch_id on
-- stock_movements - not as columns bolted onto products. Splitting stock per
-- branch now would force every existing inventory query, the low-stock report
-- and the analytics dashboard to grow a branch dimension for no delivered
-- value, so it is left out on purpose.
-- ============================================================================
CREATE TABLE branches (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,          -- e.g. 'Head Office', 'Lekki Branch'
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_client_id_name UNIQUE (client_id, name)
);

CREATE INDEX idx_branches_client_id ON branches (client_id);

-- One default branch per client, same partial-unique-index trick as
-- users.is_root: the fallback "which branch does an order belong to" answer
-- must be unambiguous.
CREATE UNIQUE INDEX uq_branches_one_default_per_client ON branches (client_id) WHERE is_default;

CREATE TRIGGER trg_branches_set_updated_at
    BEFORE UPDATE ON branches
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- Backfill: every client that exists right now gets exactly one 'Head Office'
-- branch, flagged default. New signups get theirs from ClientSignupService, in
-- the same transaction as the client, so "every client has exactly one default
-- branch" holds for old and new rows alike. ON CONFLICT keeps this idempotent
-- if it is ever re-run against a partially populated table.
INSERT INTO branches (client_id, name, is_default, is_active)
SELECT c.id, 'Head Office', TRUE, TRUE
FROM clients c
ON CONFLICT (client_id, name) DO NOTHING;

-- Reserved scaffolding, unused in this pass: a user may later be pinned to one
-- branch, with the VIEW_ALL_BRANCHES permission (granted to OWNER below)
-- letting them see across branches. Nothing filters on this column yet, and
-- deliberately so - branch-scoped queries are a future step, and a half-applied
-- filter is worse than none.
ALTER TABLE users
    ADD COLUMN branch_id UUID REFERENCES branches (id) ON DELETE SET NULL;

CREATE INDEX idx_users_branch_id ON users (branch_id);

-- ============================================================================
-- delivery_addresses: where goods actually go. Distinct from branches on
-- purpose - a company with a single branch can still ship to a main kitchen, a
-- warehouse and an event site, and a company with three branches may want one
-- shared receiving bay. Conflating the two would force the buyer to create
-- fake branches just to name a drop-off point.
--
-- No country column: this is Nigeria-only. state is one of the 36 states + FCT
-- (the frontend holds that list as a single shared constant). If the product
-- ever ships outside NG this grows a country column and the state select
-- becomes country-driven - not worth modelling before then.
-- ============================================================================
CREATE TABLE delivery_addresses (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id      UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    -- Optional association. SET NULL rather than CASCADE: losing a branch must
    -- not delete a physical address the buyer still ships to.
    branch_id      UUID REFERENCES branches (id) ON DELETE SET NULL,
    label          VARCHAR(100) NOT NULL,      -- 'Main kitchen', 'Warehouse 2'
    contact_name   VARCHAR(255) NOT NULL,
    contact_phone  VARCHAR(50)  NOT NULL,
    address_line1  VARCHAR(255) NOT NULL,
    address_line2  VARCHAR(255),
    city           VARCHAR(100) NOT NULL,
    state          VARCHAR(100) NOT NULL,
    landmark       VARCHAR(255),
    delivery_notes VARCHAR(500),
    is_default     BOOLEAN NOT NULL DEFAULT FALSE,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_delivery_addresses_client_id ON delivery_addresses (client_id);
CREATE INDEX idx_delivery_addresses_branch_id ON delivery_addresses (branch_id);

-- Addresses are soft-deleted (is_active), so the "one default" rule has to
-- ignore deactivated rows - otherwise deleting the default address would block
-- promoting another one.
CREATE UNIQUE INDEX uq_delivery_addresses_one_default_per_client
    ON delivery_addresses (client_id) WHERE is_default AND is_active;

CREATE TRIGGER trg_delivery_addresses_set_updated_at
    BEFORE UPDATE ON delivery_addresses
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- product_categories: global and ProcurePal-managed, NOT tenant-scoped. The
-- categories exist to organise one shared public catalog, so per-tenant copies
-- would make the storefront's category filter meaningless.
--
-- parent_id supports one level of nesting. It is here so a later "Grains >
-- Rice" split doesn't need a migration; the UI is free to render the tree flat
-- until that matters.
-- ============================================================================
CREATE TABLE product_categories (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(120) NOT NULL,
    -- URL-facing identifier, used by the public catalog filter.
    slug       VARCHAR(120) NOT NULL,
    -- SET NULL, not CASCADE: deleting a parent must not silently delete every
    -- product's category out from under the catalog.
    parent_id  UUID REFERENCES product_categories (id) ON DELETE SET NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_categories_slug UNIQUE (slug)
);

CREATE INDEX idx_product_categories_parent_id ON product_categories (parent_id);

CREATE TRIGGER trg_product_categories_set_updated_at
    BEFORE UPDATE ON product_categories
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- products: the columns that turn a tenant inventory item into either a
-- marketplace listing (on the platform owner's side) or a purchased item
-- awaiting delivery (on the buyer's side).
--
-- incoming_quantity is the whole point of the buying flow: paid for, not yet
-- in your hands, and NOT usable stock. It is a separate column rather than a
-- stock_movement because no goods have moved - writing an IN movement at
-- payment time would corrupt quantity_on_hand and the audit trail. The movement
-- is written when the buyer confirms receipt, which is the moment stock really
-- arrives.
--
-- source_product_id links a buyer's own product row back to the ProcurePal
-- catalog product it was created from. This is what makes reorder, "you bought
-- this before" and per-product analytics work without matching on names. It
-- deliberately crosses tenants, so it is never navigated as a JPA association -
-- see the Product entity for why.
-- ============================================================================
ALTER TABLE products
    ADD COLUMN category_id           UUID REFERENCES product_categories (id) ON DELETE SET NULL,
    ADD COLUMN is_marketplace_listed BOOLEAN NOT NULL DEFAULT FALSE,
    -- How this item is actually traded in Nigerian B2B procurement: nobody buys
    -- "1 rice", they buy 'bag (50kg)' or 'carton (24)'. Nullable because an
    -- ordinary tenant's inventory item needn't declare one.
    ADD COLUMN unit_of_measure       VARCHAR(50),
    ADD COLUMN min_order_quantity    INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN brand                 VARCHAR(120),
    ADD COLUMN slug                  VARCHAR(160),
    ADD COLUMN incoming_quantity     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN source_product_id     UUID REFERENCES products (id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_products_incoming_non_negative CHECK (incoming_quantity >= 0),
    ADD CONSTRAINT chk_products_min_order_quantity_positive CHECK (min_order_quantity >= 1);

CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_source_product_id ON products (source_product_id);

-- Slugs are unique per tenant, like SKUs, and only where present - an ordinary
-- tenant's product has no slug, and NULLs must not collide.
CREATE UNIQUE INDEX uq_products_client_id_slug ON products (client_id, slug) WHERE slug IS NOT NULL;

-- The public catalog's hot path is "listed AND active". A partial index keeps it
-- small: only the platform owner's listed rows are ever in it.
CREATE INDEX idx_products_marketplace ON products (is_marketplace_listed, is_active)
    WHERE is_marketplace_listed;

-- NOTE: is_marketplace_listed must only ever be TRUE on the platform owner's
-- products. That cannot be a CHECK constraint - it needs a join to
-- clients.is_platform_owner, and Postgres CHECKs are row-local - so it is
-- enforced in service code (the listing endpoint goes through the
-- platform-owner guard) and, defensively, by every public catalog query also
-- filtering on the platform owner's client_id rather than trusting the flag
-- alone.

-- ============================================================================
-- carts: ONE SHARED CART PER COMPANY, not per user. This is B2B procurement -
-- a storekeeper builds the requisition through the week and an owner or
-- procurement manager checks it out. A per-user cart would make that normal
-- workflow impossible and would surprise buyers who expect their colleague's
-- additions to be there.
--
-- added_by on cart_items is what keeps that honest: the UI can show who put
-- each line in, so a shared cart is still accountable.
-- ============================================================================
CREATE TABLE carts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_carts_client_id UNIQUE (client_id)
);

CREATE TRIGGER trg_carts_set_updated_at
    BEFORE UPDATE ON carts
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

CREATE TABLE cart_items (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cart_id    UUID NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    -- Points at the PLATFORM OWNER's product, not the cart owner's. This is why
    -- cart_items has no client_id and is not a tenant-scoped entity: it is
    -- scoped through its cart. CASCADE because a cart line for a deleted catalog
    -- product is meaningless.
    product_id UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity   INTEGER NOT NULL,
    -- Who added the line, for the shared-cart UI. SET NULL so removing a user
    -- doesn't empty their colleagues' cart.
    added_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cart_items_cart_id_product_id UNIQUE (cart_id, product_id),
    CONSTRAINT chk_cart_items_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_cart_items_cart_id ON cart_items (cart_id);
CREATE INDEX idx_cart_items_product_id ON cart_items (product_id);
CREATE INDEX idx_cart_items_added_by ON cart_items (added_by);

CREATE TRIGGER trg_cart_items_set_updated_at
    BEFORE UPDATE ON cart_items
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- orders: client_id is the BUYER. ON DELETE RESTRICT, unlike most client_id
-- columns here, because an order is a financial record - deleting a customer
-- must not erase what they bought and paid for.
--
-- Two independent status axes, not one combined status:
--   status         - fulfilment: PENDING_PAYMENT -> PLACED -> CONFIRMED ->
--                    PROCESSING -> OUT_FOR_DELIVERY -> DELIVERED -> RECEIVED,
--                    plus terminal CANCELLED.
--   payment_status - money: PENDING -> PAID | FAILED, ON_DELIVERY -> PAID,
--                    PAID -> REFUNDED.
-- Collapsing them would make "delivered but not yet collected on a
-- pay-on-delivery order" unrepresentable, which is a real state ProcurePal has.
--
-- The delivery_* columns are a SNAPSHOT of the address at checkout, copied
-- rather than read through delivery_address_id. delivery_address_id is kept
-- (nullable, SET NULL) only so "reuse this address" and analytics can still
-- point at the original row; the shipping label always comes from the snapshot.
-- ============================================================================
CREATE TABLE orders (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Human-readable and quotable in a WhatsApp message, e.g. 'PP-2026-000123'.
    order_number            VARCHAR(30) NOT NULL,
    client_id               UUID NOT NULL REFERENCES clients (id) ON DELETE RESTRICT,
    placed_by               UUID REFERENCES users (id) ON DELETE SET NULL,
    branch_id               UUID REFERENCES branches (id) ON DELETE SET NULL,
    status                  VARCHAR(30) NOT NULL,
    payment_status          VARCHAR(30) NOT NULL,
    payment_method          VARCHAR(30) NOT NULL,
    -- VARCHAR rather than CHAR(3): the app maps this to a plain String, and a
    -- blank-padded CHAR only invites trailing-space comparison bugs. No CHECK -
    -- adding a currency should not need a migration. NGN is the only value today.
    currency                VARCHAR(3) NOT NULL DEFAULT 'NGN',
    subtotal                NUMERIC(14,2) NOT NULL,
    delivery_fee            NUMERIC(14,2) NOT NULL DEFAULT 0,
    total                   NUMERIC(14,2) NOT NULL,
    delivery_address_id     UUID REFERENCES delivery_addresses (id) ON DELETE SET NULL,
    delivery_label          VARCHAR(100),
    delivery_contact_name   VARCHAR(255),
    delivery_contact_phone  VARCHAR(50),
    delivery_address_line1  VARCHAR(255),
    delivery_address_line2  VARCHAR(255),
    delivery_city           VARCHAR(100),
    delivery_state          VARCHAR(100),
    delivery_landmark       VARCHAR(255),
    delivery_notes          VARCHAR(500),
    customer_note           VARCHAR(1000),
    cancellation_reason     VARCHAR(500),
    -- One timestamp per milestone rather than deriving them from
    -- order_status_events: these are read on every order list/detail render and
    -- feed the fulfilment analytics, and order_status_events remains the audit
    -- trail of *who* changed *what*.
    placed_at               TIMESTAMPTZ,
    confirmed_at            TIMESTAMPTZ,
    dispatched_at           TIMESTAMPTZ,
    delivered_at            TIMESTAMPTZ,
    received_at             TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT chk_orders_status CHECK (status IN (
        'PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PROCESSING',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_orders_payment_status CHECK (payment_status IN (
        'PENDING', 'PAID', 'FAILED', 'ON_DELIVERY', 'REFUNDED')),
    CONSTRAINT chk_orders_payment_method CHECK (payment_method IN ('MONNIFY', 'PAY_ON_DELIVERY')),
    CONSTRAINT chk_orders_totals_non_negative CHECK (subtotal >= 0 AND delivery_fee >= 0 AND total >= 0)
);

CREATE INDEX idx_orders_client_id ON orders (client_id);
CREATE INDEX idx_orders_placed_by ON orders (placed_by);
CREATE INDEX idx_orders_branch_id ON orders (branch_id);
CREATE INDEX idx_orders_delivery_address_id ON orders (delivery_address_id);
-- The buyer's "my orders" list and ProcurePal's fulfilment queue are both
-- "newest first, optionally filtered by status", from opposite ends.
CREATE INDEX idx_orders_client_id_created_at ON orders (client_id, created_at DESC);
CREATE INDEX idx_orders_status_created_at ON orders (status, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders (payment_status);

CREATE TRIGGER trg_orders_set_updated_at
    BEFORE UPDATE ON orders
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- order_items: one line per catalog product bought, with the commercial terms
-- frozen in place. No client_id - a line belongs to its order, and the order
-- already says who the buyer is.
-- ============================================================================
CREATE TABLE order_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id          UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    -- The ProcurePal catalog product sold. RESTRICT: an order line must always
    -- resolve to a real catalog row for reorder and product analytics.
    product_id        UUID NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    -- The buyer's own inventory product this fulfils into, found-or-created when
    -- the order reaches PLACED. Nullable because it does not exist before then.
    buyer_product_id  UUID REFERENCES products (id) ON DELETE SET NULL,
    -- Snapshots: exactly what the buyer saw and agreed to pay, immune to later
    -- catalog edits.
    product_name      VARCHAR(255) NOT NULL,
    product_sku       VARCHAR(100) NOT NULL,
    unit_of_measure   VARCHAR(50),
    image_url         TEXT,
    unit_price        NUMERIC(14,2) NOT NULL,
    quantity          INTEGER NOT NULL,
    -- Partial receipt is normal in wholesale: 8 of 10 bags arrive, 2 follow.
    -- The remainder stays as incoming stock until it is received.
    received_quantity INTEGER NOT NULL DEFAULT 0,
    line_total        NUMERIC(14,2) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT chk_order_items_received_quantity CHECK (received_quantity >= 0 AND received_quantity <= quantity)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_product_id ON order_items (product_id);
CREATE INDEX idx_order_items_buyer_product_id ON order_items (buyer_product_id);

CREATE TRIGGER trg_order_items_set_updated_at
    BEFORE UPDATE ON order_items
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- order_status_events: append-only audit trail, and the data the buyer-facing
-- tracking timeline renders from. Same shape and reasoning as stock_movements -
-- no updated_at, because an event is recorded, never edited.
--
-- from_status is nullable: the first event (order created) has no predecessor.
-- ============================================================================
CREATE TABLE order_status_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status VARCHAR(30),
    to_status   VARCHAR(30) NOT NULL,
    note        VARCHAR(500),
    -- Nullable: a system transition (payment webhook verified, 24h timeout
    -- sweep) has no acting user.
    created_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_order_status_events_from_status CHECK (from_status IS NULL OR from_status IN (
        'PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PROCESSING',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_order_status_events_to_status CHECK (to_status IN (
        'PENDING_PAYMENT', 'PLACED', 'CONFIRMED', 'PROCESSING',
        'OUT_FOR_DELIVERY', 'DELIVERED', 'RECEIVED', 'CANCELLED'))
);

CREATE INDEX idx_order_status_events_order_id ON order_status_events (order_id, created_at);
CREATE INDEX idx_order_status_events_created_by ON order_status_events (created_by);

-- ============================================================================
-- payments: one row per payment ATTEMPT, not per order - a buyer who abandons
-- Monnify's checkout and retries must not overwrite the record of the first
-- try, or a dispute becomes unanswerable.
--
-- NOT tenant-scoped, and neither is payment_webhook_events: the Monnify webhook
-- arrives unauthenticated on a public endpoint with no TenantContext at all, so
-- a tenant filter here would either block the write or silently mis-scope it.
-- Reads are scoped through order.client_id in service code instead.
-- ============================================================================
CREATE TABLE payments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID NOT NULL REFERENCES orders (id) ON DELETE RESTRICT,
    -- No CHECK: onboarding a second PSP should not need a migration.
    provider              VARCHAR(30) NOT NULL DEFAULT 'MONNIFY',
    -- Our reference, sent to Monnify as paymentReference. Unique so a retry can
    -- never collide with an earlier attempt on the same order.
    payment_reference     VARCHAR(100) NOT NULL,
    -- Monnify's own reference, returned on init/verify/webhook. Nullable until
    -- we have been told what it is.
    transaction_reference VARCHAR(100),
    status                VARCHAR(30) NOT NULL,
    amount                NUMERIC(14,2) NOT NULL,
    -- What the provider says was actually paid. Kept separate from amount so an
    -- underpayment is visible rather than rounded away - underpayment is treated
    -- as FAILED, never as paid.
    amount_paid           NUMERIC(14,2),
    currency              VARCHAR(3) NOT NULL DEFAULT 'NGN',
    -- Provider-controlled free text (CARD, ACCOUNT_TRANSFER, USSD, ...), so no
    -- CHECK: a new Monnify channel must not start rejecting webhooks.
    payment_method_used   VARCHAR(50),
    checkout_url          TEXT,
    paid_at               TIMESTAMPTZ,
    -- Full provider payload of the last verify/webhook, for dispute forensics.
    -- JSONB rather than TEXT so it stays queryable when someone has to answer
    -- "what exactly did Monnify tell us on the 3rd?".
    provider_payload      JSONB,
    verified_via          VARCHAR(20),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_payment_reference UNIQUE (payment_reference),
    CONSTRAINT chk_payments_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'ABANDONED', 'REVERSED')),
    CONSTRAINT chk_payments_verified_via CHECK (verified_via IS NULL OR verified_via IN (
        'WEBHOOK', 'RETURN_VERIFY', 'RECONCILIATION')),
    CONSTRAINT chk_payments_amount_non_negative CHECK (amount >= 0)
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
-- Supports the reconciliation sweep ("still PENDING and older than N minutes").
CREATE INDEX idx_payments_status_created_at ON payments (status, created_at);

-- Unique where present: the same Monnify transaction must never be recorded
-- twice, but many of our own attempts legitimately have no transaction
-- reference yet.
CREATE UNIQUE INDEX uq_payments_transaction_reference ON payments (transaction_reference)
    WHERE transaction_reference IS NOT NULL;

CREATE TRIGGER trg_payments_set_updated_at
    BEFORE UPDATE ON payments
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- payment_webhook_events: every callback we receive, valid or not, kept whole.
-- A rejected signature or a replayed callback is otherwise invisible, and
-- "Monnify says they told us" is a conversation that needs evidence. No FK to
-- payments: a malformed or spoofed callback may reference nothing we know, and
-- it still has to be recordable.
-- ============================================================================
CREATE TABLE payment_webhook_events (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider              VARCHAR(30) NOT NULL,
    event_type            VARCHAR(60),
    transaction_reference VARCHAR(100),
    payment_reference     VARCHAR(100),
    -- Recorded, not assumed: an invalid-signature callback is stored with FALSE
    -- and never processed.
    signature_valid       BOOLEAN NOT NULL,
    processed             BOOLEAN NOT NULL DEFAULT FALSE,
    processing_note       VARCHAR(500),
    payload               JSONB NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_webhook_events_payment_reference ON payment_webhook_events (payment_reference);
CREATE INDEX idx_payment_webhook_events_transaction_reference ON payment_webhook_events (transaction_reference);
CREATE INDEX idx_payment_webhook_events_received_at ON payment_webhook_events (received_at DESC);

-- ============================================================================
-- notifications: in-app only, polled by the bell in the header. Tenant-scoped -
-- ProcurePal's "new order" notification and the buyer's "your order shipped"
-- notification are two rows for two different clients.
--
-- user_id NULL means "the whole company", which is the common case: a new order
-- concerns whoever is on shift, not one named person.
--
-- order_id deliberately crosses tenants for ProcurePal's copy (client_id =
-- ProcurePal, order_id = a buyer's order), which is why it is a plain id and
-- never navigated as a filtered association.
-- ============================================================================
CREATE TABLE notifications (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    user_id    UUID REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(50) NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       VARCHAR(1000),
    -- In-app route, e.g. '/app/marketplace/orders/<id>'. Stored rather than
    -- derived so an old notification keeps working if routes are reorganised
    -- for new ones.
    link       VARCHAR(300),
    order_id   UUID REFERENCES orders (id) ON DELETE CASCADE,
    -- Read state as a timestamp, not a boolean: "when did they see it" is
    -- strictly more useful and costs nothing.
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_notifications_type CHECK (type IN (
        'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED'))
);

-- The bell polls "unread count for my company" constantly; read_at is in the
-- index so that count is answered without touching the heap.
CREATE INDEX idx_notifications_client_unread ON notifications (client_id, read_at);
CREATE INDEX idx_notifications_client_created_at ON notifications (client_id, created_at DESC);
CREATE INDEX idx_notifications_user_id ON notifications (user_id);
CREATE INDEX idx_notifications_order_id ON notifications (order_id);

-- ============================================================================
-- marketplace_settings: single-row table so ProcurePal can change delivery fees,
-- free-delivery thresholds and pay-on-delivery rules without a redeploy. A
-- config table beats environment variables here because these are commercial
-- decisions made by ops, not deployment concerns.
--
-- The singleton column plus its unique index is what makes "exactly one row"
-- true in the database rather than a convention nobody enforces.
-- ============================================================================
CREATE TABLE marketplace_settings (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton                       BOOLEAN NOT NULL DEFAULT TRUE,
    delivery_fee                    NUMERIC(14,2) NOT NULL DEFAULT 2500,
    free_delivery_threshold         NUMERIC(14,2) NOT NULL DEFAULT 150000,
    minimum_order_value             NUMERIC(14,2) NOT NULL DEFAULT 0,
    pay_on_delivery_enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    -- A cap on trust: pay-on-delivery is fine for a ₦200k restaurant restock,
    -- not for a ₦4m equipment order.
    pay_on_delivery_max_order_value NUMERIC(14,2) NOT NULL DEFAULT 500000,
    support_phone                   VARCHAR(50),
    support_email                   VARCHAR(255),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketplace_settings_singleton UNIQUE (singleton),
    CONSTRAINT chk_marketplace_settings_singleton CHECK (singleton),
    CONSTRAINT chk_marketplace_settings_amounts CHECK (
        delivery_fee >= 0 AND free_delivery_threshold >= 0 AND minimum_order_value >= 0
        AND pay_on_delivery_max_order_value >= 0)
);

CREATE TRIGGER trg_marketplace_settings_set_updated_at
    BEFORE UPDATE ON marketplace_settings
    FOR EACH ROW
    EXECUTE FUNCTION set_updated_at();

-- The one row. Every other column takes its column default, so the defaults
-- above are the single definition of "our commercial rules on day one".
INSERT INTO marketplace_settings (support_email, support_phone)
VALUES ('support@procurepal.ng', '+234 800 000 0000')
ON CONFLICT (singleton) DO NOTHING;

-- ============================================================================
-- Permissions for the marketplace surfaces.
--
-- The split is between "spend the company's money" (PLACE_ORDERS), "see what
-- was spent" (VIEW_ORDERS) and "sign for goods" (RECEIVE_DELIVERIES) - three
-- genuinely different jobs in a procurement team, which is why a storekeeper
-- can receive a delivery without being able to place an order.
--
-- The three MARKETPLACE ones read oddly at first: they are granted to EVERY
-- tenant's OWNER, not only ProcurePal's. That is deliberate. Permissions are
-- global rows attached to global roles, so making them ProcurePal-only would
-- require per-tenant roles. Instead every /api/marketplace/admin/** endpoint
-- carries a SECOND, independent check - the caller's client must have
-- is_platform_owner = TRUE (see PlatformOwnerGuard). Holding the permission is
-- necessary but not sufficient.
-- ============================================================================
INSERT INTO permissions (code, description) VALUES
    ('BROWSE_MARKETPLACE',         'Browse the ProcurePal marketplace catalog while signed in'),
    ('PLACE_ORDERS',               'Check out and place orders on the marketplace'),
    ('VIEW_ORDERS',                'View the company''s marketplace purchase history'),
    ('MANAGE_DELIVERY_ADDRESSES',  'Add, edit and remove the company''s delivery addresses'),
    ('RECEIVE_DELIVERIES',         'Confirm receipt of a delivery, turning incoming stock into on-hand stock'),
    ('VIEW_ALL_BRANCHES',          'See data across every branch, not just the assigned one'),
    ('MANAGE_MARKETPLACE',         'Platform owner: list/unlist products, manage categories and marketplace settings'),
    ('MANAGE_MARKETPLACE_ORDERS',  'Platform owner: advance order status and fulfil marketplace orders'),
    ('VIEW_MARKETPLACE_ANALYTICS', 'Platform owner: customer, revenue and fulfilment analytics')
ON CONFLICT (code) DO NOTHING;

-- ============================================================================
-- role_permissions: rebuilt from scratch for these five roles, exactly as V5
-- does it, rather than patched with inserts. Two reasons, both of which V5 also
-- had: the full matrix stays readable in one place, and the result is identical
-- whether this runs on a fresh database or on one that already has V5's grants
-- (or a partial re-run of this file). The rows below therefore restate V5's
-- grants verbatim as well as adding the new ones - dropping any of them would
-- silently revoke access.
-- ============================================================================
DELETE FROM role_permissions
WHERE role_id IN (
    SELECT id FROM roles
    WHERE name IN ('OWNER', 'PROCUREMENT_MANAGER', 'INVENTORY_OFFICER', 'FINANCE_OFFICER', 'STOREKEEPER')
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    -- Carried over from V5, unchanged.
    ('OWNER',               'MANAGE_USERS'),
    ('OWNER',               'MANAGE_ROLES'),
    ('OWNER',               'MANAGE_PRODUCTS'),
    ('OWNER',               'VIEW_PRODUCTS'),
    ('OWNER',               'MANAGE_INVENTORY'),
    ('OWNER',               'VIEW_ANALYTICS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_PRODUCTS'),
    ('PROCUREMENT_MANAGER', 'VIEW_PRODUCTS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_INVENTORY'),
    ('PROCUREMENT_MANAGER', 'VIEW_ANALYTICS'),
    ('INVENTORY_OFFICER',   'VIEW_PRODUCTS'),
    ('INVENTORY_OFFICER',   'MANAGE_INVENTORY'),
    ('INVENTORY_OFFICER',   'VIEW_ANALYTICS'),
    ('FINANCE_OFFICER',     'VIEW_PRODUCTS'),
    ('FINANCE_OFFICER',     'VIEW_ANALYTICS'),
    ('STOREKEEPER',         'VIEW_PRODUCTS'),
    ('STOREKEEPER',         'MANAGE_INVENTORY'),
    -- New: browsing is universal. Anyone who can log in can see what the
    -- company could buy; seeing a price list is not a privilege.
    ('OWNER',               'BROWSE_MARKETPLACE'),
    ('PROCUREMENT_MANAGER', 'BROWSE_MARKETPLACE'),
    ('INVENTORY_OFFICER',   'BROWSE_MARKETPLACE'),
    ('FINANCE_OFFICER',     'BROWSE_MARKETPLACE'),
    ('STOREKEEPER',         'BROWSE_MARKETPLACE'),
    -- Committing the company's money is narrow on purpose.
    ('OWNER',               'PLACE_ORDERS'),
    ('PROCUREMENT_MANAGER', 'PLACE_ORDERS'),
    -- Everyone with a reporting or reconciliation job can see the history; the
    -- storekeeper cannot, because prices and spend are not their business.
    ('OWNER',               'VIEW_ORDERS'),
    ('PROCUREMENT_MANAGER', 'VIEW_ORDERS'),
    ('INVENTORY_OFFICER',   'VIEW_ORDERS'),
    ('FINANCE_OFFICER',     'VIEW_ORDERS'),
    ('OWNER',               'MANAGE_DELIVERY_ADDRESSES'),
    ('PROCUREMENT_MANAGER', 'MANAGE_DELIVERY_ADDRESSES'),
    -- Signing for goods is a floor job, which is why the storekeeper has it and
    -- the finance officer does not.
    ('OWNER',               'RECEIVE_DELIVERIES'),
    ('PROCUREMENT_MANAGER', 'RECEIVE_DELIVERIES'),
    ('INVENTORY_OFFICER',   'RECEIVE_DELIVERIES'),
    ('STOREKEEPER',         'RECEIVE_DELIVERIES'),
    ('OWNER',               'VIEW_ALL_BRANCHES'),
    -- Platform-owner surfaces. Gated a second time by PlatformOwnerGuard.
    ('OWNER',               'MANAGE_MARKETPLACE'),
    ('PROCUREMENT_MANAGER', 'MANAGE_MARKETPLACE'),
    ('OWNER',               'MANAGE_MARKETPLACE_ORDERS'),
    ('PROCUREMENT_MANAGER', 'MANAGE_MARKETPLACE_ORDERS'),
    ('OWNER',               'VIEW_MARKETPLACE_ANALYTICS'),
    ('PROCUREMENT_MANAGER', 'VIEW_MARKETPLACE_ANALYTICS'),
    ('INVENTORY_OFFICER',   'VIEW_MARKETPLACE_ANALYTICS')
) AS v(role_name, permission_code)
JOIN roles r ON r.name = v.role_name
JOIN permissions p ON p.code = v.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;
