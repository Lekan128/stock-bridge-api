-- Multi-seller checkout: one basket, several sellers, one payment.
--
-- ============================================================================
-- WHAT V11 LEFT UNFINISHED
-- ============================================================================
-- V11 established that an order has exactly one seller (orders.seller_client_id)
-- and stated the rule this file implements: a cart holding two sellers' products
-- SPLITS at checkout into one orders row per seller. V11 could stop at the
-- column because nothing could yet produce a multi-seller cart - only the
-- platform owner had listed products. Opening the catalogue to vendors makes the
-- split real, and the split immediately raises a question V11 did not have to
-- answer:
--
--   A buyer pressed "pay" ONCE. Three orders came out of it. Monnify takes one
--   amount and gives back one transaction. What ties them together?
--
-- Nothing did. orders had no way to express "these three rows are one shopping
-- trip", so the buyer's order history would read as three unrelated purchases
-- that happened to share a timestamp, and the payment module would have had to
-- reconstruct the grouping by guessing from created_at.
--
-- ============================================================================
-- checkout_group_id: ONE CHECKOUT, N ORDERS
-- ============================================================================
-- A plain UUID minted once per checkout and stamped on every order that checkout
-- produced. It is NOT a foreign key and there is deliberately no checkout_groups
-- table, because a group has no attributes of its own that are not already on
-- its members: the buyer, the delivery address, the moment and the payment
-- method are identical across the group by construction (they all came from one
-- press of one button), and duplicating them into a parent row would create a
-- second place for them to live and a way for the two to disagree after the
-- first support edit.
--
-- What the column buys, concretely:
--   * "You placed 3 orders" on the confirmation screen, and an order history
--     that groups them instead of interleaving them with last week's.
--   * ONE Monnify transaction covering the whole basket. The payments row
--     anchors on one order of the group; settlement fans out across every
--     member inside a single transaction. See PaymentApplicationService.
--   * A cancellation or refund conversation that can name the whole trip.
--
-- WHY NOT REUSE orders.id OF A DESIGNATED "PARENT" ORDER
-- Because it makes one of the N orders structurally special, and every query
-- then has to know whether it is holding a parent or a child. Cancelling the
-- parent of a group would orphan its siblings' grouping. A group identity that
-- belongs to no single member cannot be deleted by cancelling a member.
--
-- NOT NULL after a backfill, for the same reason V11 made seller_client_id NOT
-- NULL after its own: a nullable column would push a "or is it a group of one"
-- branch into every read of it, forever.
-- ============================================================================
ALTER TABLE orders
    ADD COLUMN checkout_group_id UUID;

-- Every order that exists today was its own checkout - there was one seller, so
-- a basket could only ever produce one order. Making each historical row a group
-- of one is not a placeholder: it is precisely what those checkouts were, and it
-- means "show me everything that came out of this checkout" returns the right
-- answer for a 2025 order and a 2026 three-way split alike, with no NULL branch.
--
-- On a fresh database this matches zero rows and is a no-op; on a seeded local
-- or a production database it fills in every historical row.
UPDATE orders SET checkout_group_id = id WHERE checkout_group_id IS NULL;

ALTER TABLE orders
    ALTER COLUMN checkout_group_id SET NOT NULL;

-- The hot read is "every order in this checkout", which runs on the buyer's
-- confirmation screen and on every payment settlement - the latter with no
-- tenant context at all, from the Monnify webhook thread. Not unique: N rows
-- share a value by design.
CREATE INDEX idx_orders_checkout_group_id ON orders (checkout_group_id);

COMMENT ON COLUMN orders.checkout_group_id IS
    'Groups the orders produced by ONE checkout. A single-seller basket yields a group of one. '
    'Minted per checkout, not a foreign key - see V12__order_splitting.sql.';
