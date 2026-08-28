package com.procurepal_services.stock_bridge_api.imports.handler;

import com.procurepal_services.stock_bridge_api.imports.ImportCopy;
import com.procurepal_services.stock_bridge_api.imports.ImportFieldDescriptor;
import com.procurepal_services.stock_bridge_api.imports.NameSimilarity;
import com.procurepal_services.stock_bridge_api.imports.RowContext;
import com.procurepal_services.stock_bridge_api.imports.RowIssue;
import com.procurepal_services.stock_bridge_api.imports.RowValidation;
import com.procurepal_services.stock_bridge_api.imports.io.NumberValues;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasure;
import com.procurepal_services.stock_bridge_api.product.unit.UnitOfMeasureRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cell coercion shared by the two row handlers: text to number, text to unit code, text to date,
 * text to yes/no - each returning the value AND adding the error when it cannot.
 *
 * <h2>Why this is not just a call to the M2 parsers</h2>
 * {@code ProductExcelService.parse} and {@code StockInExcelService.parse} already coerce these
 * columns, and the review pipeline cannot use either. Both are all-or-nothing: they collect
 * errors and throw {@code BulkUploadValidationException}, returning rows only when the file is
 * perfect. That is exactly right for the one-shot compatibility endpoint they serve, and exactly
 * wrong for a screen whose entire purpose is to hold a partly-broken file open while the user
 * repairs it one cell at a time. This module needs a *row* that is half-good, with the bad cells
 * marked - so the coercion is re-expressed here in a form that returns rather than throws.
 *
 * <p>The forgiving bits are kept identical on purpose. {@link NumberValues#parseDecimal} is M2's
 * and handles {@code 45,000}, {@code ₦45,000.50} and {@code (300)}; {@link
 * UnitOfMeasure#fromCodeOrLabel} is M2's and handles {@code kg}, {@code KGS}, {@code kilo} and
 * {@code "Kilogram (kg)"}. A file that parses one way through the legacy endpoint and another way
 * through the review grid would be a support nightmare, and re-deriving these rules rather than
 * calling them is how that would happen.
 *
 * <h2>Idempotence</h2>
 * Every method accepts its own output. A pass over an already-normalized row must produce the
 * same answer, because re-validation runs on every repair, every mapping change and every value
 * resolution - see {@link com.procurepal_services.stock_bridge_api.imports.ImportRowHandler#validate}.
 * That is why the accessors go through {@link RowContext}, which already unwraps a
 * {@link BigDecimal} that a previous pass left in place rather than re-parsing its
 * {@code toString()}.
 */
final class RowValues {

    private static final Set<String> TRUTHY = Set.of("TRUE", "T", "YES", "Y", "1", "X", "✓", "PREFERRED");
    private static final Set<String> FALSY = Set.of("FALSE", "F", "NO", "N", "0", "-");

    /**
     * The date formats a Nigerian spreadsheet actually contains, in the order they are tried.
     * Same list as {@code StockInExcelService}, for the same reason the number parsing is shared:
     * a date that the template's own parser accepts must not be rejected by the review grid.
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("uuuu/M/d", Locale.ENGLISH));

    /** Excel serial-date window, 2000-01-01 to 2050-01-01. Copied from M2 rather than re-derived. */
    private static final int EARLIEST_SERIAL = 36526;
    private static final int LATEST_SERIAL = 54788;

    private RowValues() {
    }

    // ---------------------------------------------------------------- numbers

    static BigDecimal money(RowContext ctx, RowValidation.Builder out, String field, String label, String subject) {
        String raw = ctx.text(field);
        if (raw == null) {
            out.value(field, null);
            return null;
        }
        Optional<BigDecimal> parsed = ctx.decimal(field);
        if (parsed.isEmpty()) {
            out.error(field, "NOT_A_NUMBER", "%s is %s, which is not an amount we can read. Use digits only - 45000 or 45,000.50."
                    .formatted(label, ImportCopy.quote(raw)));
            out.value(field, null);
            return null;
        }
        if (parsed.get().signum() < 0) {
            out.error(field, "NEGATIVE_NUMBER",
                    "%s cannot be a negative amount for %s.".formatted(label, subject));
            out.value(field, null);
            return null;
        }
        out.value(field, parsed.get());
        return parsed.get();
    }

    static BigDecimal decimal(RowContext ctx, RowValidation.Builder out, String field, String label, String subject) {
        return money(ctx, out, field, label, subject);
    }

    static Integer wholeNumber(
            RowContext ctx, RowValidation.Builder out, String field, String label, String subject) {
        String raw = ctx.text(field);
        if (raw == null) {
            out.value(field, null);
            return null;
        }
        Optional<BigDecimal> parsed = ctx.decimal(field);
        if (parsed.isEmpty()) {
            out.error(field, "NOT_A_NUMBER", "%s is %s, which is not a number we can read for %s."
                    .formatted(label, ImportCopy.quote(raw), subject));
            out.value(field, null);
            return null;
        }
        BigDecimal value = parsed.get();
        if (value.signum() < 0) {
            out.error(field, "NEGATIVE_NUMBER", "%s cannot be negative for %s.".formatted(label, subject));
            out.value(field, null);
            return null;
        }
        if (value.stripTrailingZeros().scale() > 0) {
            out.error(field, "NOT_A_WHOLE_NUMBER",
                    "%s has to be a whole number for %s - %s is not.".formatted(label, subject, ImportCopy.quote(raw)));
            out.value(field, null);
            return null;
        }
        // Bounded before conversion rather than letting intValueExact throw: a cell holding a
        // twenty-digit number is a mistyped value, not a server fault, and it has to come back
        // as a cell error like every other bad number.
        if (value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            out.error(field, "NUMBER_TOO_LARGE",
                    "%s is larger than we can record for %s.".formatted(label, subject));
            out.value(field, null);
            return null;
        }
        out.value(field, value.intValue());
        return value.intValue();
    }

    // ------------------------------------------------------------------ units

    /**
     * A unit code, narrowed to a role, with the nearest legal code offered as a one-click fix.
     *
     * <p>The suggestion is the point. Contract section 4 ties {@code bulkFixCount} to an error
     * that carries one, and design 9.3 names {@code [Fix all 12 "KGS" rows]} as the single
     * highest-value interaction on the review screen - which only exists if the error knows what
     * the right answer probably was. A wrong-role code gets a different sentence naming the
     * column it does belong in, because "BAG is not a unit of measure" is unhelpful when the
     * user's real mistake was putting it one column to the left.
     */
    static String unitCode(
            RowContext ctx,
            RowValidation.Builder out,
            String field,
            UnitOfMeasureRole role,
            String label,
            String otherColumnLabel,
            String subject) {
        String raw = ctx.text(field);
        if (raw == null) {
            out.value(field, null);
            return null;
        }
        Optional<UnitOfMeasure> resolved = UnitOfMeasure.fromCodeOrLabel(raw, role);
        if (resolved.isPresent()) {
            out.value(field, resolved.get().code());
            return resolved.get().code();
        }
        UnitOfMeasureRole otherRole =
                role == UnitOfMeasureRole.BASE ? UnitOfMeasureRole.PACKAGING : UnitOfMeasureRole.BASE;
        Optional<UnitOfMeasure> wrongRole = UnitOfMeasure.fromCodeOrLabel(raw, otherRole);
        if (wrongRole.isPresent()) {
            out.error(field, "UNIT_WRONG_ROLE",
                    "%s is %s, which belongs in the %s column rather than here."
                            .formatted(label, wrongRole.get().label(), otherColumnLabel));
            out.value(field, null);
            return null;
        }
        ImportFieldDescriptor.Option suggestion = closestUnit(raw, role);
        String message = suggestion == null
                ? "We don't recognise %s as %s for %s. Pick one from the list."
                        .formatted(ImportCopy.quote(raw), label.toLowerCase(Locale.ROOT), subject)
                : "We don't recognise %s as %s for %s. Did you mean %s?"
                        .formatted(ImportCopy.quote(raw), label.toLowerCase(Locale.ROOT), subject, suggestion.label());
        out.issue(RowIssue.error(field, "UNIT_NOT_RECOGNISED", message, suggestion));
        out.value(field, null);
        return null;
    }

    /** Best guess at what an unrecognised unit code meant, or null when nothing is close enough. */
    static ImportFieldDescriptor.Option closestUnit(String raw, UnitOfMeasureRole role) {
        List<UnitOfMeasure> candidates =
                role == UnitOfMeasureRole.PACKAGING ? UnitOfMeasure.packagingUnits() : UnitOfMeasure.baseUnits();
        UnitOfMeasure best = null;
        double bestScore = 0;
        for (UnitOfMeasure candidate : candidates) {
            double score = Math.max(
                    NameSimilarity.score(raw, candidate.code()), NameSimilarity.score(raw, candidate.label()));
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best == null || bestScore < NameSimilarity.SUGGESTION_FLOOR
                ? null
                : new ImportFieldDescriptor.Option(best.code(), best.label());
    }

    static List<ImportFieldDescriptor.Option> options(List<UnitOfMeasure> units) {
        return units.stream()
                .map(unit -> new ImportFieldDescriptor.Option(unit.code(), unit.label()))
                .toList();
    }

    // ------------------------------------------------------------------ flags

    /**
     * TRUE/blank, forgivingly. Anything in the truthy set counts, anything in the falsy set is a
     * no, and everything else is an error rather than a quiet false - a cell reading
     * {@code "main"} clearly meant yes, and silently reading it as no would pick the wrong
     * preferred supplier without ever saying so.
     */
    static Boolean flag(RowContext ctx, RowValidation.Builder out, String field, String label, String subject) {
        // A previous pass leaves a real Boolean here; re-parsing its toString() would work by
        // luck rather than by design, and the SPI requires validate to be idempotent.
        Object existing = ctx.input().get(field);
        if (existing instanceof Boolean already) {
            out.value(field, already);
            return already;
        }
        String raw = ctx.text(field);
        if (raw == null) {
            out.value(field, null);
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (TRUTHY.contains(normalized)) {
            out.value(field, Boolean.TRUE);
            return Boolean.TRUE;
        }
        if (FALSY.contains(normalized)) {
            out.value(field, Boolean.FALSE);
            return Boolean.FALSE;
        }
        out.error(field, "NOT_A_FLAG",
                "%s for %s should say TRUE, or be left empty - we don't know what %s means here."
                        .formatted(label, subject, ImportCopy.quote(raw)));
        out.value(field, null);
        return null;
    }

    // ------------------------------------------------------------------ dates

    static LocalDate date(RowContext ctx, RowValidation.Builder out, String field, String label, String subject) {
        Object existing = ctx.input().get(field);
        if (existing instanceof LocalDate already) {
            out.value(field, already.toString());
            return already;
        }
        String raw = ctx.text(field);
        if (raw == null) {
            out.value(field, null);
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(raw, format);
                out.value(field, parsed.toString());
                return parsed;
            } catch (DateTimeParseException ignored) {
                // Next format. A date is worth several attempts before it is called wrong.
            }
        }
        // Excel hands a date cell over as a serial number when the sheet has no date format on
        // it, which is common in exports from other systems. Bounded to a plausible window so a
        // genuine quantity typed into the date column is not silently read as 2013.
        Optional<BigDecimal> serial = NumberValues.parseDecimal(raw);
        if (serial.isPresent()
                && serial.get().stripTrailingZeros().scale() <= 0
                && serial.get().intValue() >= EARLIEST_SERIAL
                && serial.get().intValue() <= LATEST_SERIAL) {
            LocalDate parsed = org.apache.poi.ss.usermodel.DateUtil.getLocalDateTime(
                            serial.get().doubleValue())
                    .toLocalDate();
            out.value(field, parsed.toString());
            return parsed;
        }
        out.error(field, "NOT_A_DATE",
                "We can't read %s as %s for %s. Try 2026-01-31 or 31/01/2026."
                        .formatted(ImportCopy.quote(raw), label.toLowerCase(Locale.ROOT), subject));
        out.value(field, null);
        return null;
    }
}
