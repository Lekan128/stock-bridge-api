package com.procurepal_services.stock_bridge_api.entity;

/**
 * Where an {@link ImportSession} stands, stored as its name in {@code import_sessions.status}
 * under a CHECK on exactly these seven spellings (BULK_IMPORT_CONTRACT.md section 1).
 *
 * <h2>This enum is the frontend's router</h2>
 * BULK_IMPORT_CONTRACT.md section 7 derives the step the user sees from this value and never
 * from component state, specifically so a refresh or a shared link lands on the right screen:
 * {@link #READY} redirects to confirm, {@link #COMMITTED} to the result, {@link #EXPIRED} back
 * to the chooser with a notice. A status added or renamed here is a broken route there.
 *
 * <h2>COMMITTING is not decoration</h2>
 * It is the idempotency guard of BULK_IMPORT_DESIGN.md section 11. A double-clicked Commit
 * must not double-import, and the mechanism is a {@link #READY} -&gt; {@link #COMMITTING}
 * transition taken under a row lock ({@code
 * ImportSessionRepository.findByIdAndClientIdForUpdate}); the second call blocks, then finds a
 * status that is no longer {@link #READY} and answers 409 with the first call's result rather
 * than importing everything a second time. A state machine that went straight from READY to
 * COMMITTED would have no window in which to hold that lock.
 */
public enum ImportStatus {

    /** Bytes accepted, rows being read. The only transient state; nothing user-visible waits here long. */
    PARSING,

    /**
     * Parsed, and something needs a human: an error cell, an unresolved vendor name, an
     * unmapped required column. The review grid renders in its "exception" form.
     */
    NEEDS_REVIEW,

    /**
     * Parsed clean, or repaired clean - nothing left to decide. Design doc 9.3's first rule
     * lives on this value: <b>a clean file shows one green line and a Continue button</b>, no
     * grid, no scrolling. The review screen must never feel like a toll booth on a correct file.
     */
    READY,

    /** Commit in flight, entered under a row lock. See the class javadoc - this is the double-click guard. */
    COMMITTING,

    /** Committed. Terminal, and deliberately outlives {@code expires_at} - it is the undo record. */
    COMMITTED,

    /** The commit transaction rolled back, or the file could not be parsed at all. Terminal. */
    FAILED,

    /**
     * Past its 48h TTL without committing. Terminal, and mostly a tombstone: the scheduled job
     * DELETES expired uncommitted sessions, so a user meets this status only in the window
     * between expiry and collection, where its whole job is to produce an honest notice instead
     * of a 404 on a link somebody bookmarked.
     */
    EXPIRED
}
