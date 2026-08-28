package com.procurepal_services.stock_bridge_api.imports;

import java.util.List;

/**
 * One column a row handler understands, described richly enough that the review grid can render
 * it without hardcoding a copy of the template - BULK_IMPORT_CONTRACT.md section 4's
 * {@code ImportFieldDescriptor}, serialized verbatim as the {@code fields} array on
 * {@code ImportSessionResponse}.
 *
 * <h2>Why the grid is told about columns rather than knowing them</h2>
 * The two imports have nothing in common at the column level: thirteen catalog columns whose
 * shape depends on whether the tenant sells, against nine stock-in columns of which the user
 * fills exactly one. A grid that hardcoded either would have to be edited every time a column
 * moved, and - worse - the two would drift, so the same {@code unit_of_measure} cell would offer
 * a different set of units on two screens of the same product. Sending the descriptor list means
 * there is one definition of what a column is, it lives beside the code that validates it, and
 * the frontend renders whatever it is handed.
 *
 * <p>Order matters and is the template's column order (BULK_IMPORT_CONTRACT.md section 5). The
 * grid lays columns out in the order it receives them so the screen reads like the file the user
 * is looking at in the other window - which is the whole reason the review step can be scanned
 * at all.
 *
 * @param key snake_case and identical to the template's column header, so a header maps to
 *     itself in the common case and {@code column_mapping} is an identity map for our own
 *     template. This is the vocabulary both sides speak (contract section 5).
 * @param label what a person calls this column. Never show {@code key} to a user - section 8.7
 *     of the contract is explicit that a column name is never an error subject, and this is the
 *     string that exists so it never has to be.
 * @param type drives the editor the grid renders: a MONEY cell gets a number pad on mobile, an
 *     ENUM cell gets {@code options} as a picker, a REFERENCE cell defers to the value resolver.
 * @param required whether a row is incomplete without it. Also drives the mapping step: an
 *     unmapped required field is the ONLY thing that forces the mapping screen (design 6.2).
 * @param readOnly the stock-in sheet's reference columns ({@code sku}, {@code product_name}) -
 *     they identify the row and must not be edited in the grid, because editing them would
 *     silently re-point the row at a different product.
 * @param primaryInput true for exactly one column per kind: stock-in's {@code quantity}. The
 *     grid highlights it, because "we bring the rows, the user brings one number" (design 6.7)
 *     is only true if the screen makes that number obvious.
 * @param helpText the same sentence the template's header comment carries, so the answer to
 *     "what goes in here?" is identical in Excel and in the browser.
 * @param options ENUM only, else null - the exact set the server will accept, so the grid never
 *     offers a value that validation would then reject.
 */
public record ImportFieldDescriptor(
        String key,
        String label,
        Type type,
        boolean required,
        boolean readOnly,
        boolean primaryInput,
        String helpText,
        List<Option> options) {

    public ImportFieldDescriptor {
        options = options == null ? null : List.copyOf(options);
    }

    /** Spelled exactly as BULK_IMPORT_CONTRACT.md section 4 lists them; the frontend mirrors these. */
    public enum Type {
        TEXT,
        NUMBER,
        INTEGER,
        MONEY,
        BOOLEAN,
        DATE,
        ENUM,
        REFERENCE
    }

    /** One choice on an ENUM column. {@code value} goes on the wire, {@code label} on the screen. */
    public record Option(String value, String label) {
    }

    /** A plain optional text column - the shape most descriptors turn out to be. */
    public static ImportFieldDescriptor text(String key, String label, String helpText) {
        return new ImportFieldDescriptor(key, label, Type.TEXT, false, false, false, helpText, null);
    }

    public static ImportFieldDescriptor of(String key, String label, Type type, boolean required, String helpText) {
        return new ImportFieldDescriptor(key, label, type, required, false, false, helpText, null);
    }

    public static ImportFieldDescriptor enumeration(
            String key, String label, boolean required, String helpText, List<Option> options) {
        return new ImportFieldDescriptor(key, label, Type.ENUM, required, false, false, helpText, options);
    }
}
