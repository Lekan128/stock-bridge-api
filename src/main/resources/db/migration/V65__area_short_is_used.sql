-- Areas, Part 3.5 (STOCK_AREAS_PART_3_5.md, D-A12/D-E13): what happens in an area that keeps stock.
-- FALSE: stock is stored there (Store, a cold room) - a count that comes up short is missing, an adjustment.
-- TRUE: stock is worked with there (Kitchen, Bar) - a count that comes up short is what was used.
-- Meaningless where keeps_stock is false (what it's given is used up). Every existing area stays as it is.
ALTER TABLE areas ADD COLUMN short_is_used BOOLEAN NOT NULL DEFAULT FALSE;
