-- Areas, Part 3 (STOCK_AREAS_PART_3.md, D-E9): the areas a person works in. No rows for a branch's areas =
-- every area of that branch, including ones added later; a role with Work at all branches ignores the list.
CREATE TABLE user_areas (
    client_id UUID NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    area_id   UUID NOT NULL REFERENCES areas (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, area_id)
);

CREATE INDEX idx_user_areas_area ON user_areas (area_id);
