package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a header cell becomes a field key. One rule, applied to every sheet this feature reads, so
 * that "our own template maps to itself" (BULK_IMPORT_CONTRACT.md section 5) stays true even
 * after a file has been round-tripped through Google Sheets, exported from an ERP, or had its
 * headers re-typed in title case by someone tidying up.
 *
 * <h2>What it normalizes, and why each one earns its place</h2>
 * <ul>
 *   <li><b>Invisible characters.</b> A BOM on the first header of a CSV, or a non-breaking space
 *       left by a paste from a web page, makes {@code "sku"} not equal {@code "sku"} - and the
 *       resulting "required column is missing" error is unanswerable, because the column is
 *       visibly right there. This is the same class of bug the existing parser already fixed for
 *       cell values; headers are simply where it is most confusing.</li>
 *   <li><b>Case and surrounding whitespace.</b> Already done by the pre-existing parser and kept
 *       verbatim.</li>
 *   <li><b>Spaces, hyphens and dots to underscores.</b> {@code "Unit Of Measure"},
 *       {@code "unit-of-measure"} and {@code "Unit of measure"} are the same column to everyone
 *       except a string comparison. Field keys are snake_case by the contract, so folding the
 *       separators a human would type onto that one shape costs nothing and removes a whole
 *       category of column-mapping prompts the user should never have been asked.</li>
 * </ul>
 *
 * <p>Deliberately NOT done here: synonym matching ({@code "Product Name"} to {@code name},
 * {@code "Supplier"} to {@code vendor_name}). That is auto-mapping, it is per-kind, it has to be
 * overridable by the user, and BULK_IMPORT_CONTRACT.md section 2 assigns it to the row handler's
 * {@code fields()} descriptors in M4. Doing a little of it here would produce a mapping the user
 * cannot see or correct, which is worse than not doing it.
 */
public final class HeaderNames {

    private static final Pattern INVISIBLE_CHARACTERS =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00A0]");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-\\u2010-\\u2015]+");

    private HeaderNames() {
    }

    /**
     * The field key a header cell maps to, or null when the cell is empty - an unnamed column is
     * not a column, and giving it the key {@code ""} would let two of them collide.
     */
    public static String normalize(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        String cleaned = INVISIBLE_CHARACTERS.matcher(rawHeader).replaceAll("").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        String key = SEPARATORS.matcher(cleaned).replaceAll("_").toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }
}
