package com.procurepal_services.stock_bridge_api.imports;

import com.procurepal_services.stock_bridge_api.imports.dto.UndoBlockedResponse;
import java.util.List;
import lombok.Getter;

/**
 * The failures this module can hand back, in one file because each is three lines and scattering
 * them across seven would make the set harder to see than any one of them is to read.
 *
 * <p>Every message here is user-facing prose, per design 9.6 - and none of them contains the
 * words "escrow", "session", "staging" or "batch", which contract section 8.6 bans from anything
 * a user can see. That is why "session" appears nowhere below even though it is what the row is
 * called in the schema: the user uploaded a file, and a file is what they get told about.
 */
public final class ImportExceptions {

    private ImportExceptions() {
    }

    /** 404. Also the answer for another tenant's id, which is why it says nothing specific. */
    public static class NotFound extends RuntimeException {
        public NotFound() {
            super("We can't find that import. It may have been discarded, or it may have expired.");
        }
    }

    /** 403 - the caller holds one import permission but not the one this kind needs. */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) {
            super(message);
        }
    }

    /**
     * 409 on commit. Covers both halves of design 11's idempotency guard: a second click while
     * the first is still running, and a click on something that has already been imported.
     *
     * <p>Says which, because "already imported" is reassuring and "still running" is a reason to
     * wait - and a single generic message would leave a user who double-clicked genuinely unsure
     * whether their stock had gone in twice.
     */
    public static class NotCommittable extends RuntimeException {
        public NotCommittable(String message) {
            super(message);
        }
    }

    /** 404 on {@code GET /result} before there is anything to report. */
    public static class NoResultYet extends RuntimeException {
        public NoResultYet() {
            super("This import hasn't run yet, so there is nothing to report.");
        }
    }

    /**
     * 409 on undo, carrying contract section 4's exact {@code {message, blockers}} body.
     *
     * <p>The body shape is the requirement. The frontend detects it by {@code typeof message ===
     * 'string' && Array.isArray(blockers)}; anything else on the same status degrades to a
     * generic toast and the panel built to render this never appears. That is why this exception
     * carries a response object rather than just a string.
     */
    @Getter
    public static class UndoBlocked extends RuntimeException {
        private final UndoBlockedResponse body;

        public UndoBlocked(String message, List<UndoBlockedResponse.Blocker> blockers) {
            super(message);
            this.body = new UndoBlockedResponse(message, blockers);
        }
    }

    /**
     * 500 - the write itself threw, and the transaction rolled back.
     *
     * <p>Says "nothing was changed" because that is literally true: the commit is one
     * transaction over the whole batch (design 6.5), so a failure half way through leaves no
     * trace. Being able to promise that is the main practical benefit of the all-or-nothing
     * choice, and it is worth spending the sentence on.
     */
    public static class CommitFailed extends RuntimeException {
        public CommitFailed(Throwable cause) {
            super("This import could not be completed, so nothing was changed. Please try again.", cause);
        }
    }

    /** 400 - a file we could not read, or one over the caps in contract section 6. */
    public static class BadFile extends RuntimeException {
        public BadFile(String message) {
            super(message);
        }
    }
}
