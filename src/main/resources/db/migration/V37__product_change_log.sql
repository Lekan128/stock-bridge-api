-- The change feed behind the on-device catalogue (INVENTORY_OFFLINE_AND_CHARACTER_PLAN.md, A3).
--
-- A phone keeps a full copy of its company's product list and asks "what changed since my
-- cursor?" (ProductSyncService). This table answers it: one row per change to anything the
-- Inventory list shows about a product, written by triggers so that NO write path - a stock
-- movement, an import, its undo, a catalogue reset, a category rename - can forget to report one.
--
-- <h2>Why every row records its transaction id</h2>
-- A plain sequence is not a safe cursor: ids are handed out when a transaction writes, but become
-- visible when it COMMITS, so a slow transaction can commit id 41 after a phone has already read
-- up to 42 - and that change would never be delivered. Each row therefore records the xid of the
-- transaction that wrote it, and the feed only hands out rows from transactions older than the
-- oldest one still running (pg_snapshot_xmin). Everything below that line is finished, so a
-- cursor never moves past a change that could still appear behind it.
--
-- The row says only WHICH product changed. The feed reads the product's current state when it
-- answers, and a product that no longer exists is reported as removed - so there is no separate
-- tombstone, and a delete is never lost to the order triggers fire in during a cascade.
CREATE TABLE product_changes (
    id         BIGSERIAL PRIMARY KEY,
    client_id  UUID   NOT NULL,
    product_id UUID   NOT NULL,
    xid        BIGINT NOT NULL DEFAULT (pg_current_xact_id()::text::bigint)
);

-- The feed's only read: one company's changes after a (xid, id) cursor, in that order.
CREATE INDEX idx_product_changes_feed ON product_changes (client_id, xid, id);
-- The compaction sweep, which keeps only each product's LAST row in feed order - (xid, id), not
-- id alone: a long transaction can hold an older id with a newer-in-feed-order xid, and deleting
-- by id would drop the one row a phone still has to receive.
CREATE INDEX idx_product_changes_product ON product_changes (product_id, xid, id);

CREATE OR REPLACE FUNCTION log_product_change(p_client_id UUID, p_product_id UUID) RETURNS VOID AS $$
BEGIN
    IF p_client_id IS NOT NULL AND p_product_id IS NOT NULL THEN
        INSERT INTO product_changes (client_id, product_id) VALUES (p_client_id, p_product_id);
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Products themselves: created, edited, stock moved, deactivated, deleted.
CREATE OR REPLACE FUNCTION products_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM log_product_change(OLD.client_id, OLD.id);
        RETURN OLD;
    END IF;
    PERFORM log_product_change(NEW.client_id, NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_log_change
    AFTER INSERT OR UPDATE OR DELETE ON products
    FOR EACH ROW EXECUTE FUNCTION products_log_change();

-- A product's suppliers decide whether it shows "(default)" beside its pack (hasMultiplePacks).
CREATE OR REPLACE FUNCTION product_vendors_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM log_product_change(OLD.client_id, OLD.product_id);
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM log_product_change(NEW.client_id, NEW.product_id);
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_vendors_log_change
    AFTER INSERT OR UPDATE OR DELETE ON product_vendors
    FOR EACH ROW EXECUTE FUNCTION product_vendors_log_change();

-- So do the packs under those suppliers. A pack row carries no product id of its own; it is read
-- through its supplier line, which is already gone when the whole line is deleted - and that
-- deletion was logged by the trigger above.
CREATE OR REPLACE FUNCTION product_vendor_packs_log_change() RETURNS TRIGGER AS $$
DECLARE
    line_id UUID := COALESCE(NEW.product_vendor_id, OLD.product_vendor_id);
BEGIN
    PERFORM log_product_change(pv.client_id, pv.product_id) FROM product_vendors pv WHERE pv.id = line_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_product_vendor_packs_log_change
    AFTER INSERT OR UPDATE OR DELETE ON product_vendor_packs
    FOR EACH ROW EXECUTE FUNCTION product_vendor_packs_log_change();

-- What is on an open expected delivery ("+ 500 expected"): any line changing, and any delivery
-- changing status (opened, received, cancelled) changes the figure for every product on it.
CREATE OR REPLACE FUNCTION expected_delivery_lines_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        PERFORM log_product_change(ed.client_id, OLD.product_id)
            FROM expected_deliveries ed WHERE ed.id = OLD.expected_delivery_id;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        PERFORM log_product_change(ed.client_id, NEW.product_id)
            FROM expected_deliveries ed WHERE ed.id = NEW.expected_delivery_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_expected_delivery_lines_log_change
    AFTER INSERT OR UPDATE OR DELETE ON expected_delivery_lines
    FOR EACH ROW EXECUTE FUNCTION expected_delivery_lines_log_change();

CREATE OR REPLACE FUNCTION expected_deliveries_log_change() RETURNS TRIGGER AS $$
BEGIN
    PERFORM log_product_change(NEW.client_id, l.product_id)
        FROM expected_delivery_lines l WHERE l.expected_delivery_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_expected_deliveries_log_change
    AFTER UPDATE OF status ON expected_deliveries
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION expected_deliveries_log_change();

-- Every product row names its category. A rename changes that name on all of them.
CREATE OR REPLACE FUNCTION company_categories_log_change() RETURNS TRIGGER AS $$
BEGIN
    PERFORM log_product_change(p.client_id, p.id) FROM products p WHERE p.company_category_id = NEW.id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_company_categories_log_change
    AFTER UPDATE OF name ON company_categories
    FOR EACH ROW WHEN (OLD.name IS DISTINCT FROM NEW.name)
    EXECUTE FUNCTION company_categories_log_change();
