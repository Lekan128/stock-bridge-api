package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.regex.Pattern;

/**
 * The one cleaning rule every cell value passes through, whichever reader produced it.
 *
 * <h2>The invisible-character problem, restated because it keeps costing people days</h2>
 * Spreadsheet apps leave zero-width and non-breaking space characters behind in cells that look
 * empty - most often when formatting or a fill handle is dragged past the last real row, and
 * routinely in anything pasted from a web page or a PDF. Java's {@code String.isBlank()} and
 * {@code trim()} do not treat any of them as whitespace, so without this a cell that is visibly
 * empty reads as non-blank and gets parsed as real data, and a SKU with a trailing non-breaking
 * space silently fails to match the identical-looking SKU already in the catalog. The
 * pre-existing bulk-upload parser learned this from a real customer file; the rule is kept
 * verbatim and simply moved somewhere both readers and every future one can share it.
 *
 * <p>Applying it in the READER rather than in each parser is what makes the two formats
 * interchangeable: a CSV exported from an ERP carries a UTF-8 BOM on its first field about as
 * often as an xlsx carries zero-width spaces in its ghost rows, and a rule applied on one path
 * only would mean the same data validated differently depending on which button the user clicked
 * to export it.
 */
public final class CellValues {

    private static final Pattern INVISIBLE_CHARACTERS =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00A0]");

    private CellValues() {
    }

    /**
     * The cleaned value, or null when nothing is left. Null rather than an empty string
     * deliberately: "the user left this cell alone" and "the user typed nothing into it" are the
     * same fact, and giving them one representation means no parser has to remember to check for
     * both.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = INVISIBLE_CHARACTERS.matcher(raw).replaceAll("").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }
}
