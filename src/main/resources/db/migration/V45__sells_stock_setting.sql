-- The company switch for selling from stock (SELLING_PRICE_PLAN.md section 4.2).
--
-- sells_stock: a company that sells some of what it stocks over the counter, as
-- well as (or instead of) using it. It decides what the SCREENS show - selling
-- prices, Record a sale, the Sales report - and never which stock writes are
-- accepted: a sale queued on a phone while offline must still land after the
-- owner turns this off (plan principle 3). So nothing in the stock ledger reads
-- it, and no constraint mentions it.
--
-- Off for every existing row: a company has never been asked, and switching
-- surfaces on for someone who did not choose them is the change the plan
-- promises not to make.
ALTER TABLE clients
    ADD COLUMN sells_stock BOOLEAN NOT NULL DEFAULT FALSE;

-- Who changed a company setting, from where, and why. One row per change,
-- never updated (append-only, like vendor_settlement_settings_changes).
--
-- setting is a name rather than a column per setting so the next company
-- switch logs here too instead of growing a sibling table.
--
-- source says WHICH PATH the change came through - the owner's settings page,
-- ProcurePal support, the first-week question, or an offer in context (picking
-- "Sold", uploading a sheet with prices). The plan's success measure for how
-- people discover selling (section 9) is read straight off this column.
--
-- changed_by is a users.id for a tenant user and a super_admins.id for support;
-- changed_by_kind says which, so neither needs a foreign key that would have to
-- point at two tables. changed_by_username is a snapshot so the log still
-- reads correctly after the account is renamed or removed.
CREATE TABLE client_setting_changes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id           UUID         NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    setting             VARCHAR(40)  NOT NULL,
    old_value           VARCHAR(100),
    new_value           VARCHAR(100) NOT NULL,
    source              VARCHAR(20)  NOT NULL,
    changed_by          UUID,
    changed_by_kind     VARCHAR(20)  NOT NULL,
    changed_by_username VARCHAR(255) NOT NULL,
    reason              VARCHAR(500),
    changed_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT chk_client_setting_changes_source
        CHECK (source IN ('OWNER', 'SUPPORT', 'FIRST_WEEK', 'IN_CONTEXT')),
    CONSTRAINT chk_client_setting_changes_changed_by_kind
        CHECK (changed_by_kind IN ('TENANT_USER', 'SUPER_ADMIN')),
    -- Support must say why: the owner sees the change appear and is told
    -- ProcurePal made it, and "why" is the first question they will ask.
    CONSTRAINT chk_client_setting_changes_support_reason
        CHECK (source <> 'SUPPORT' OR (reason IS NOT NULL AND length(trim(reason)) > 0))
);

CREATE INDEX idx_client_setting_changes_client_id_changed_at
    ON client_setting_changes (client_id, changed_at DESC);

COMMENT ON COLUMN clients.sells_stock IS
    'The company sells some of its stock over the counter (SELLING_PRICE_PLAN.md). '
    'Gates screens only - selling prices, Record a sale, the Sales report - never '
    'which stock writes the server accepts. Changes are logged in client_setting_changes.';

-- ProcurePal support changing a company's setting tells the owner in the app
-- (plan section 5.5): the screens change under them, and a notice saying who
-- did it and why is what keeps that from looking like a fault. Not an email -
-- one email per event per reader, and this is not one the owner needs in their
-- inbox.
ALTER TABLE notifications DROP CONSTRAINT chk_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT chk_notifications_type CHECK (type IN (
    'NEW_ORDER', 'ORDER_STATUS_CHANGED', 'PAYMENT_RECEIVED', 'PAYMENT_FAILED', 'ORDER_DELIVERED',
    'ACCOUNT_SETTING_CHANGED'));
