package com.procurepal_services.stock_bridge_api.product.sku;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grammar for {@code product_sku_settings.pattern}: literal text plus {@code {SEQ:N}} (required
 * - the only token that guarantees two products never get the same SKU), {@code {YYYY}}/{@code
 * {YY}}/{@code {MM}}/{@code {DD}}, and {@code {NAME:N}}.
 *
 * <p>Called from {@code ProductSkuSettingsService.update} at save time, unconditionally - even
 * when saving {@code enabled=false}, so a tenant can never save a broken pattern and have it bite
 * them only once they flip the toggle on. {@code SkuGenerationService} never calls this; it trusts
 * a pattern that already passed here.
 */
public final class SkuPatternValidator {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z]+)(?::(\\d+))?}");
    private static final Set<String> DATE_TOKENS = Set.of("YYYY", "YY", "MM", "DD");
    private static final Set<String> KNOWN_TOKENS = Set.of("SEQ", "NAME", "YYYY", "YY", "MM", "DD");

    /** products.sku is VARCHAR(100) - a pattern whose worst-case rendering exceeds this can never be saved. */
    public static final int MAX_RENDERED_LENGTH = 100;

    private SkuPatternValidator() {}

    /**
     * @throws InvalidSkuPatternException the pattern is blank, has an unrecognized/malformed
     *     token, is missing its one required {@code {SEQ:N}}, or repeats it
     * @throws SkuPatternTooLongException the exact worst-case rendered length (see {@link
     *     SkuPatternRenderer}) exceeds {@link #MAX_RENDERED_LENGTH}
     */
    public static void validate(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new InvalidSkuPatternException("Pattern must not be blank.");
        }

        Matcher matcher = TOKEN.matcher(pattern);
        int last = 0;
        int seqCount = 0;
        int maxLength = 0;
        while (matcher.find()) {
            maxLength += rejectStrayBraces(pattern.substring(last, matcher.start()));

            String name = matcher.group(1);
            String rawParam = matcher.group(2);
            if (!KNOWN_TOKENS.contains(name)) {
                throw new InvalidSkuPatternException("Unknown token {" + name + "}.");
            }
            if (DATE_TOKENS.contains(name)) {
                if (rawParam != null) {
                    throw new InvalidSkuPatternException("{" + name + "} does not take a size, e.g. write {" + name
                            + "} not {" + name + ":" + rawParam + "}.");
                }
                maxLength += name.equals("YYYY") ? 4 : 2;
            } else if (name.equals("SEQ")) {
                seqCount++;
                int digits = requireParamInRange(name, rawParam, 1, 10);
                maxLength += digits;
            } else { // NAME
                int chars = requireParamInRange(name, rawParam, 1, 20);
                maxLength += chars;
            }
            last = matcher.end();
        }
        maxLength += rejectStrayBraces(pattern.substring(last));

        if (seqCount == 0) {
            throw new InvalidSkuPatternException("Pattern must include exactly one {SEQ:N} token - it is the "
                    + "only part of the pattern that guarantees two products never get the same SKU.");
        }
        if (seqCount > 1) {
            throw new InvalidSkuPatternException("Pattern may include only one {SEQ:N} token.");
        }
        if (maxLength > MAX_RENDERED_LENGTH) {
            throw new SkuPatternTooLongException(maxLength, MAX_RENDERED_LENGTH);
        }
    }

    /** A '{' or '}' outside a well-formed token is a typo (a missing digit count, an unclosed brace), not literal text. */
    private static int rejectStrayBraces(String literal) {
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) {
            throw new InvalidSkuPatternException("Unrecognized token near '" + literal + "'. Supported tokens: "
                    + "{SEQ:N}, {YYYY}, {YY}, {MM}, {DD}, {NAME:N}.");
        }
        return literal.length();
    }

    private static int requireParamInRange(String name, String rawParam, int min, int max) {
        Integer value = rawParam == null ? null : Integer.valueOf(rawParam);
        if (value == null || value < min || value > max) {
            throw new InvalidSkuPatternException(
                    "{" + name + ":N} requires a number from " + min + " to " + max + ".");
        }
        return value;
    }
}
