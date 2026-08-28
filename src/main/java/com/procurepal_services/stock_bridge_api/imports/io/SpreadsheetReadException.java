package com.procurepal_services.stock_bridge_api.imports.io;

import lombok.Getter;

/**
 * Every way reading a file can fail before any of its content has been interpreted, carried as a
 * {@link Reason} the caller can branch on plus a message already written for the user.
 *
 * <h2>Why a reason code and not just a message</h2>
 * The three failures need three different HTTP shapes and three different things said on screen -
 * an over-limit file gets a specific, actionable sentence with the user's own numbers in it, a
 * corrupt file gets the deliberately-vague "not a valid .xlsx file" (there is nothing useful to
 * say about a truncated zip, and guessing invites the user to try fixes that cannot work). The
 * caller has to be able to tell them apart without matching on English text, which would break
 * the first time the copy is edited.
 */
@Getter
public class SpreadsheetReadException extends RuntimeException {

    public enum Reason {
        /** Not a readable .xlsx or .csv at all - corrupt, truncated, password-protected, or another format renamed. */
        NOT_A_SPREADSHEET,
        /** Structurally fine, but past {@link ImportLimits#MAX_ROWS}. Detected before a full parse. */
        TOO_MANY_ROWS,
        /** Past {@link ImportLimits#MAX_FILE_BYTES}. Detected before the bytes are even read. */
        FILE_TOO_LARGE,
        /** Readable, but there is no header row - an empty sheet, or a file whose first sheet is blank. */
        NO_HEADER_ROW
    }

    private final Reason reason;

    public SpreadsheetReadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SpreadsheetReadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }
}
