-- Selling till Step 5 (owner, 2026-11-17): a made-here item says what it is made with and where it is made. Selling
-- it takes those ingredients out of that area at the branch it was sold at - a sale is never stopped by the area's
-- figure, which may go below 0 until a move or a count. Ingredients are optional: an item with none takes nothing.

-- "Made in": an area by name, chosen per item ("Kitchen" = each branch's own Kitchen).
ALTER TABLE products ADD COLUMN made_in_area_name VARCHAR(80);

-- A branch the item is for that has no area of that name: its own choice, never a default.
CREATE TABLE product_made_in (
    product_id UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    branch_id  UUID NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    area_id    UUID NOT NULL REFERENCES areas (id) ON DELETE CASCADE,
    client_id  UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, branch_id)
);

-- "Made with, for one": each ingredient as typed (1 Derica) and in its stock unit (0.6 kg).
CREATE TABLE product_ingredients (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID           NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    product_id       UUID           NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    ingredient_id    UUID           NOT NULL REFERENCES products (id),
    quantity         NUMERIC(14, 3) NOT NULL CHECK (quantity > 0),
    entered_unit     VARCHAR(40),
    entered_quantity NUMERIC(14, 3),
    position         INTEGER        NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_ingredients UNIQUE (product_id, ingredient_id),
    CONSTRAINT chk_product_ingredients_not_itself CHECK (product_id <> ingredient_id)
);
CREATE INDEX idx_product_ingredients_ingredient ON product_ingredients (ingredient_id);

-- An ingredient's stock-out names the sale line it was used for: "Used for 3 x Jollof rice & chicken".
ALTER TABLE stock_movements ADD COLUMN used_for_movement_id UUID REFERENCES stock_movements (id);
CREATE INDEX idx_stock_movements_used_for ON stock_movements (used_for_movement_id) WHERE used_for_movement_id IS NOT NULL;
