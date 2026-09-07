package com.procurepal_services.stock_bridge_api.product.sku;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a pattern's tokens in with a concrete sequence value, point in time, and (optionally)
 * product name. Pure - no DB access, no side effects - so it is trivially unit-testable, and
 * every caller ({@code SkuGenerationService}'s preview/single/block paths, all three) gets
 * identical rendering behavior from one place.
 *
 * <p>Trusts that {@code pattern} already passed {@link SkuPatternValidator#validate} - it does not
 * re-check grammar, only renders tokens it recognizes and lets {@code Matcher} pass anything else
 * through as literal text.
 */
public final class SkuPatternRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z]+)(?::(\\d+))?}");

    private SkuPatternRenderer() {}

    /**
     * @param productName may be null/blank - {@code {NAME:N}} renders as empty in that case,
     *     never an error, since a product's name is supplied by the caller creating it and a
     *     pattern must still produce something even if that caller left it blank.
     * @throws SkuGenerationExhaustedException {@code sequenceValue} no longer fits in the
     *     pattern's {@code {SEQ:N}} digit count - see the exception's javadoc.
     */
    public static String render(String pattern, long sequenceValue, OffsetDateTime now, String productName) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = TOKEN.matcher(pattern);
        int last = 0;
        while (matcher.find()) {
            result.append(pattern, last, matcher.start());
            result.append(renderToken(matcher.group(1), matcher.group(2), sequenceValue, now, productName));
            last = matcher.end();
        }
        result.append(pattern, last, pattern.length());
        return result.toString();
    }

    private static String renderToken(
            String name, String rawParam, long sequenceValue, OffsetDateTime now, String productName) {
        return switch (name) {
            case "SEQ" -> renderSequence(sequenceValue, Integer.parseInt(rawParam));
            case "NAME" -> renderName(productName, Integer.parseInt(rawParam));
            case "YYYY" -> String.format(Locale.ROOT, "%04d", now.getYear());
            case "YY" -> String.format(Locale.ROOT, "%02d", now.getYear() % 100);
            case "MM" -> String.format(Locale.ROOT, "%02d", now.getMonthValue());
            case "DD" -> String.format(Locale.ROOT, "%02d", now.getDayOfMonth());
            // Unreachable once a pattern has passed SkuPatternValidator - kept as a loud failure
            // rather than silently dropping the token if that invariant is ever violated.
            default -> throw new InvalidSkuPatternException("Unknown token {" + name + "}.");
        };
    }

    private static String renderSequence(long sequenceValue, int digits) {
        long ceiling = (long) Math.pow(10, digits);
        if (sequenceValue < 0 || sequenceValue >= ceiling) {
            throw SkuGenerationExhaustedException.sequenceOverflow(sequenceValue, digits);
        }
        return String.format(Locale.ROOT, "%0" + digits + "d", sequenceValue);
    }

    private static String renderName(String productName, int chars) {
        if (productName == null || productName.isBlank()) {
            return "";
        }
        String trimmed = productName.trim();
        String prefix = trimmed.substring(0, Math.min(chars, trimmed.length()));
        return prefix.toUpperCase(Locale.ROOT);
    }
}
