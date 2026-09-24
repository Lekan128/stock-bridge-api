package com.procurepal_services.stock_bridge_api.imports.io;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The two marks the downloadable templates put on a workbook for people, and that every reader
 * therefore has to step around (BULK_IMPORT_CX_PLAN.md tasks 1.4 and 1.6).
 *
 * <ul>
 *   <li>A <b>help tab</b> in front of the data tab. It is the first thing a person sees when the
 *       file opens, which is the point - and it is not data, so the reader skips it by name.</li>
 *   <li>A <b>guidance row</b> under the headers: one grey line explaining each column, visible in
 *       every spreadsheet app including phone ones, where cell comments are not. Its first cell
 *       starts with {@link #GUIDANCE_MARKER}, which is how it is dropped on read whether the user
 *       left it in place or not. A person never types that symbol at the start of a product
 *       name, so a real row cannot be mistaken for it.</li>
 * </ul>
 */
public final class TemplateConventions {

    /** Leads the first cell of a guidance row. */
    public static final String GUIDANCE_MARKER = "ⓘ";

    /** The help tab's name on both templates. */
    public static final String HELP_SHEET_NAME = "How to fill this in";

    private static final Set<String> HELP_SHEET_NAMES = Set.of(
            HELP_SHEET_NAME.toLowerCase(Locale.ROOT), "read me", "readme", "instructions", "help");

    private TemplateConventions() {
    }

    /** True for a tab that explains the file rather than holding its rows. */
    public static boolean isHelpSheet(String sheetName) {
        return sheetName != null && HELP_SHEET_NAMES.contains(sheetName.trim().toLowerCase(Locale.ROOT));
    }

    /** True for a guidance row: its first non-blank cell starts with {@link #GUIDANCE_MARKER}. */
    public static boolean isGuidanceRow(List<String> cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                return cell.strip().startsWith(GUIDANCE_MARKER);
            }
        }
        return false;
    }
}
