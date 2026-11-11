-- Areas inside a branch, Part 1 (STOCK_AREAS_PART_1.md): adding areas, and phones hearing about them.

-- Support lets a company into the areas pilot (AREAS_ROLLOUT=pilot), like branches_enabled (V52). It
-- gates adding areas only: a branch that already has areas keeps them whatever this says.
ALTER TABLE clients ADD COLUMN areas_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- The on-device catalogue hears when an area's figure changes (V37 pattern, as branch_stock in V52).
-- Today every area change also moves branch_stock, which already logs; Part 2's moves between areas
-- will not, so the area table logs for itself.
CREATE OR REPLACE FUNCTION area_stock_log_change() RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM log_product_change(OLD.client_id, OLD.product_id);
        RETURN OLD;
    END IF;
    PERFORM log_product_change(NEW.client_id, NEW.product_id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_area_stock_log_change
    AFTER INSERT OR UPDATE OR DELETE ON area_stock
    FOR EACH ROW EXECUTE FUNCTION area_stock_log_change();
