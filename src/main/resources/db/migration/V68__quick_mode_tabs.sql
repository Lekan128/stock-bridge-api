-- Quick mode's buttons, chosen by each person (owner, 2026-11-01): up to 4 of sell, in, out, move, count, in the
-- order quick mode shows them, comma-separated. NULL - the default for everyone - means the app's own choice for
-- what they can do and where they work. What they may do still decides: a choice they no longer have rights to is
-- simply left out by the app.
ALTER TABLE users ADD COLUMN quick_mode_tabs VARCHAR(100);
