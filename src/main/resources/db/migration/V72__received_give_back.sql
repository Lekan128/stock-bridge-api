-- Areas, Part 6 (owner, 2026-11-01: "the kitchen might accept the can and want to give it back on inspection that
-- it has expired"): any area that received stock can give part of it back, or report it, not only one that uses
-- stock up. A give back may say why (EXPIRED, DAMAGED, OTHER; none = not needed), so the area it goes back to knows.
ALTER TABLE area_move_returns DROP CONSTRAINT chk_area_move_returns_reason;
ALTER TABLE area_move_returns ADD CONSTRAINT chk_area_move_returns_reason CHECK (kind = 'GIVE_BACK' OR reason IS NOT NULL);

-- From an area that keeps stock, a give back is a move back to where it came from: that move.
ALTER TABLE area_move_returns ADD COLUMN back_move_id UUID REFERENCES area_moves (id);
-- And the move back names what it gave back, so it isn't offered to be given back itself.
ALTER TABLE area_moves ADD COLUMN gives_back_move_id UUID REFERENCES area_moves (id);
