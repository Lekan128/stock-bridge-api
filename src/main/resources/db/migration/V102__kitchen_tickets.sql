-- Selling till Stage C, Group C (SELLING_TILL_PLAN.md Step 8; owner, 2026-11-19): kitchen and bar tickets.
--
-- Each Send on a bill makes one ticket per area its items come from (where they're made or kept), shown on that area's
-- Tickets page and printed there. The bill's first send is NEW; later ones are ADDED. An item taken off before it was
-- made gives its area a DONT_MAKE ticket. Done is marked on the area's device, and can be undone.
CREATE TABLE tickets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id  UUID        NOT NULL REFERENCES clients (id) ON DELETE CASCADE,
    branch_id  UUID        NOT NULL REFERENCES branches (id) ON DELETE CASCADE,
    area_id    UUID        NOT NULL REFERENCES areas (id) ON DELETE CASCADE,
    bill_id    UUID        NOT NULL REFERENCES bills (id) ON DELETE CASCADE,
    -- One Send from a bill: its lines arrive one by one and gather on one ticket per area.
    send_key   UUID        NOT NULL,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('NEW', 'ADDED', 'DONT_MAKE')),
    created_by UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_by    UUID REFERENCES users (id) ON DELETE SET NULL,
    done_at    TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_tickets_send ON tickets (bill_id, area_id, send_key);
CREATE INDEX idx_tickets_area ON tickets (area_id, created_at DESC);

CREATE TABLE ticket_lines (
    ticket_id   UUID NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    movement_id UUID NOT NULL REFERENCES stock_movements (id) ON DELETE CASCADE,
    PRIMARY KEY (ticket_id, movement_id)
);
CREATE INDEX idx_ticket_lines_movement ON ticket_lines (movement_id);
