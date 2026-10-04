package com.procurepal_services.stock_bridge_api.product.sync;

/**
 * A position in the change feed: everything at or before (xid, id), in that order, has been read.
 * Encoded as {@code "xid:id"} - opaque to the client, which only ever stores and returns it.
 */
record SyncCursor(long xid, long id) {

    /**
     * The position just before every transaction still running at {@code horizon} (the oldest
     * xid not yet finished): changes from those, and from anything later, are still to come.
     */
    static SyncCursor beforeHorizon(long horizon) {
        return new SyncCursor(horizon - 1, Long.MAX_VALUE);
    }

    static SyncCursor decode(String text) {
        if (text == null) {
            throw new InvalidSyncCursorException();
        }
        String[] parts = text.split(":", -1);
        if (parts.length != 2) {
            throw new InvalidSyncCursorException();
        }
        try {
            return new SyncCursor(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            throw new InvalidSyncCursorException();
        }
    }

    String encode() {
        return xid + ":" + id;
    }
}
