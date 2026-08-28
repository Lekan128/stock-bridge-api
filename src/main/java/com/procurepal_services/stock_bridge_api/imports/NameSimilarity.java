package com.procurepal_services.stock_bridge_api.imports;

import java.util.Locale;

/**
 * "Did you mean Dangote Nigeria Plc?" - plain name similarity, scored 0..1.
 *
 * <p>MULTI_VENDOR_INVENTORY_DESIGN.md sections 7.1/9 settled on plain name similarity for
 * suggesting an existing product, and design 6.4 reuses that decision here for suppliers and
 * SKUs rather than inventing a second answer to the same question. Deliberately not a
 * trigram index, not Postgres {@code pg_trgm}, not a learned matcher: the candidate set is one
 * tenant's supplier directory, which is tens of rows, and the cost of being slightly wrong is a
 * suggestion the user declines - the resolution card always offers "add it as new" and "leave
 * blank" beside the guesses.
 *
 * <h2>The scoring, and why it is three rules rather than one distance</h2>
 * Raw edit distance ranks {@code "Dangote Ltd"} against {@code "Dangote Nigeria Plc"} badly,
 * because most of the second string is absent from the first - yet it is obviously the right
 * answer, and a supplier list is full of exactly this shape (a trading name plus a legal suffix
 * nobody types). So containment and shared-token overlap are scored first and edit distance is
 * only the fallback. The three are combined by taking the best, not by averaging, because a
 * strong signal on any one of them is enough and averaging would drown it.
 */
public final class NameSimilarity {

    /** Below this a suggestion is noise, and offering it costs more attention than it saves. */
    public static final double SUGGESTION_FLOOR = 0.45;

    private NameSimilarity() {
    }

    public static double score(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        double best = 0;
        if (a.contains(b) || b.contains(a)) {
            // Containment scaled by how much of the longer string is accounted for, so
            // "Dangote" inside "Dangote Nigeria Plc" scores well and "A" inside "Anything"
            // does not.
            best = 0.6 + 0.4 * ((double) Math.min(a.length(), b.length()) / Math.max(a.length(), b.length()));
        }
        best = Math.max(best, tokenOverlap(a, b));
        best = Math.max(best, editSimilarity(a, b));
        return Math.min(1, best);
    }

    private static double tokenOverlap(String a, String b) {
        String[] left = a.split(" ");
        String[] right = b.split(" ");
        int shared = 0;
        for (String token : left) {
            if (token.length() < 3) {
                continue;
            }
            for (String other : right) {
                if (token.equals(other)) {
                    shared++;
                    break;
                }
            }
        }
        if (shared == 0) {
            return 0;
        }
        return 0.5 + 0.5 * ((double) shared / Math.max(left.length, right.length));
    }

    private static double editSimilarity(String a, String b) {
        int distance = levenshtein(a, b);
        int longest = Math.max(a.length(), b.length());
        return longest == 0 ? 0 : 1.0 - ((double) distance / longest);
    }

    /**
     * Two-row Levenshtein. The full matrix would be simpler to read but this runs once per
     * (unmatched value x candidate) pair, and a 200-supplier directory against a file with 40
     * distinct unknown names is 8,000 comparisons on a request the user is waiting on.
     */
    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Lower-cases, strips punctuation and collapses whitespace. Punctuation goes because
     * {@code "Dangote Ltd."} and {@code "Dangote Ltd"} are the same supplier and a full stop is
     * not a fact about them.
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
