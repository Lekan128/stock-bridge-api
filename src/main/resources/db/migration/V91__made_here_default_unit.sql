-- Made-here items (V90) get a unit like every other product - "Piece", one sold at a time - so every path that
-- reads a product's unit (quantities, receipts, the phone's copy) treats them the same (owner, 2026-11-17).
UPDATE products SET unit_of_measure = 'PIECE'
WHERE made_here AND (unit_of_measure IS NULL OR unit_of_measure = '');
