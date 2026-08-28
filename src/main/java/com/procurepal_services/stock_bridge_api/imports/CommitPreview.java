package com.procurepal_services.stock_bridge_api.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * What the confirm screen says - BULK_IMPORT_CONTRACT.md section 4's
 * {@code CommitPreviewResponse}, serialized as-is.
 *
 * <h2>Why the sentences are composed here and not in the browser</h2>
 * Design 9.4 makes the confirm step read-only prose rather than a form to refill, and design 9.6
 * sets the rules that prose has to follow: never a column name as a subject, never a UUID,
 * always the count inside the bulk affordance. Rules that live in the frontend get applied by
 * whoever is writing that component that week and are impossible to test against the data. Rules
 * that live here are applied once for both import kinds, so a catalog import and a stock-in
 * import genuinely read alike, and a test can assert on the sentence.
 *
 * <p>The {@code label}/{@code text} split serves the two-column layout in the spec's mock:
 * {@code label} is the left gutter ("Create", "Vendors"), {@code text} the sentence beside it.
 * {@code count} is there for ordering and for the frontend's own conditional rendering; it is
 * never re-formatted into the sentence, because the sentence already contains it.
 *
 * @param headline the line above the list. "Import 42 rows from products-jan.xlsx".
 * @param lines the body, in the order they should appear.
 * @param confirmLabel what the button says. "Import 42 rows", never "Confirm" - design 9.4 is
 *     explicit that the button says what it does.
 * @param blocked whether the commit can run at all.
 * @param blockedReason the sentence explaining why not. Non-null exactly when {@code blocked}.
 */
public record CommitPreview(
        String headline, List<Line> lines, String confirmLabel, boolean blocked, String blockedReason) {

    public CommitPreview {
        lines = List.copyOf(lines);
    }

    /**
     * @param key stable identifier ("create", "update", "skip", "vendors", "stock"), so the
     *     frontend can style or ice one line without parsing its text.
     * @param label the left gutter word.
     * @param count the number behind the line.
     * @param text the sentence, already complete.
     */
    public record Line(String key, String label, int count, String text) {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Line> lines = new ArrayList<>();
        private String headline;
        private String confirmLabel;
        private String blockedReason;

        public Builder headline(String value) {
            this.headline = value;
            return this;
        }

        public Builder confirmLabel(String value) {
            this.confirmLabel = value;
            return this;
        }

        public Builder blockedReason(String value) {
            this.blockedReason = value;
            return this;
        }

        /** Adds a line only when its count is non-zero - a "0 skipped" line is noise, not information. */
        public Builder line(String key, String label, int count, String text) {
            if (count > 0) {
                lines.add(new Line(key, label, count, text));
            }
            return this;
        }

        /** Adds a line whose count is not the point (the stock summary), if there is one to add. */
        public Builder always(String key, String label, int count, String text) {
            if (text != null) {
                lines.add(new Line(key, label, count, text));
            }
            return this;
        }

        public CommitPreview build() {
            return new CommitPreview(headline, lines, confirmLabel, blockedReason != null, blockedReason);
        }
    }
}
