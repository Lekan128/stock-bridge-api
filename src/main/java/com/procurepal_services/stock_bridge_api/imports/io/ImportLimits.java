package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.List;

/**
 * The one place the import feature's hard numbers live, per BULK_IMPORT_CONTRACT.md section 6.
 * The frontend mirrors these in {@code features/imports/constants.ts} with a comment pointing at
 * the contract; the dropzone copy quotes {@link #MAX_ROWS} and {@link #MAX_FILE_BYTES} to the
 * user before they pick a file, so the two sides drifting would mean promising one limit and
 * enforcing another.
 *
 * <h2>The messages live here too, and that is the point</h2>
 * BULK_IMPORT_DESIGN.md section 11 is specific that an over-limit file is rejected <em>at upload,
 * with the actual number in the message</em> - "This file has 12,400 rows - the limit is 5,000.
 * Split it into three files." - rather than after a two-minute parse. A constant with the number
 * in it and a message composed somewhere else is how the two end up disagreeing, so
 * {@link #tooManyRowsMessage} and {@link #tooLargeMessage} are here, next to the numbers they
 * quote, and every rejection path calls them instead of writing its own sentence.
 *
 * <p>The messages also do the arithmetic the user would otherwise have to: telling someone their
 * 12,400-row file is over a 5,000-row limit leaves them working out how many files that is, and
 * the answer ("three") is one division we can do for them. This is the same copy principle
 * section 9.6 states for the review screen - say the count, don't make them derive it.
 */
public final class ImportLimits {

    /**
     * Rows of DATA - the header row is not counted, because the user counts what they typed, not
     * what the template gave them. The cap exists for two independent reasons and either alone
     * would justify it: a commit holds one row lock per product for the length of one transaction
     * (BULK_IMPORT_DESIGN.md section 8.2), and the review grid's per-row state has to stay
     * something a browser can hold.
     */
    public static final int MAX_ROWS = 5000;

    /** 10 MB. Comfortably above a 5,000-row spreadsheet with images stripped, and below anything that threatens the heap. */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** How long an uncommitted session survives before the purge job takes it - "we'll keep this for 2 days" in the UI. */
    public static final int SESSION_TTL_HOURS = 48;

    /**
     * Below this many rows a commit answers 200 with the finished result; at or above it, 202 and
     * a job to poll. One threshold, both behaviours, one endpoint - a progress bar on a 40-row
     * import is worse than an instant result, and a spinner on a 4,000-row import is worse than a
     * progress bar.
     */
    public static final int ASYNC_ROW_THRESHOLD = 500;

    /**
     * Above this many active vendors the template's {@code vendor_name} column is left as free
     * text instead of a dropdown. The cap is not about the xlsx format (the hidden lookup sheet
     * has no length limit - that is the whole reason it exists); it is about the dropdown itself.
     * A 900-entry unsorted combo box is slower to use than typing, so past some size the
     * affordance stops being one, and the review step's value mapper catches whatever the user
     * typed instead.
     */
    public static final int VENDOR_DROPDOWN_CAP = 200;

    /** Rows per page in the review grid, and therefore the page size the rows endpoint defaults to. */
    public static final int GRID_PAGE_SIZE = 50;

    /** Spelled-out counts for the "split it into N files" sentence - digits under ten read as terse in prose. */
    private static final List<String> SMALL_NUMBER_WORDS =
            List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

    private ImportLimits() {
    }

    /** Whether a file of this many DATA rows may be parsed at all. */
    public static boolean isWithinRowLimit(int dataRowCount) {
        return dataRowCount <= MAX_ROWS;
    }

    public static boolean isWithinByteLimit(long bytes) {
        return bytes <= MAX_FILE_BYTES;
    }

    /**
     * The exact sentence BULK_IMPORT_DESIGN.md section 11 asks for, with the user's own number in
     * it and the split already worked out. Thousands separators on both figures because a bare
     * {@code 12400} next to a bare {@code 5000} is genuinely harder to compare at a glance.
     */
    public static String tooManyRowsMessage(int actualRowCount) {
        int filesNeeded = (actualRowCount + MAX_ROWS - 1) / MAX_ROWS;
        return "This file has %,d rows - the limit is %,d. Split it into %s files."
                .formatted(actualRowCount, MAX_ROWS, spellOut(filesNeeded));
    }

    /**
     * Megabytes rather than bytes, to one decimal place: nobody knows what 11,534,336 bytes is,
     * and "11 MB - the limit is 10 MB" is immediately actionable. The advice differs from the
     * row-limit message's on purpose - a too-BIG file is usually one fat file (embedded images,
     * a second sheet of charts), not too many rows, so "split it" would be the wrong instruction.
     */
    public static String tooLargeMessage(long actualBytes) {
        return "This file is %.1f MB - the limit is %d MB. Remove any images or extra sheets, or save it as .csv."
                .formatted(actualBytes / (1024.0 * 1024.0), MAX_FILE_BYTES / (1024 * 1024));
    }

    private static String spellOut(int count) {
        return count >= 0 && count < SMALL_NUMBER_WORDS.size() ? SMALL_NUMBER_WORDS.get(count) : String.valueOf(count);
    }
}
