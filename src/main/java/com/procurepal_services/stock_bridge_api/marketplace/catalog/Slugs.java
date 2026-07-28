package com.procurepal_services.stock_bridge_api.marketplace.catalog;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * URL-safe identifiers for storefront links.
 *
 * Two rules make this predictable rather than clever:
 *
 * 1. {@link #normalize} is idempotent - normalize(normalize(x)) == normalize(x) - so a
 *    slug that round-trips through an edit form is never mangled a second time.
 * 2. {@link #uniquify} disambiguates deterministically (foo, foo-2, foo-3), not with a
 *    random suffix. Deterministic means the same input on a fresh database produces the
 *    same URL, which is what makes the seed reproducible and a test assertable. A random
 *    suffix would also make every accidental double-submit mint a new URL.
 *
 * Accents are folded rather than dropped so "Café Crème" becomes "cafe-creme" instead of
 * "caf-crme". Non-Latin names collapse to empty, which the callers treat as "no slug the
 * operator would want" and fall back from - a Yoruba product name is a real possibility
 * here and silently producing "" would be worse than an explicit fallback.
 */
final class Slugs {

    /** products.slug is VARCHAR(160) and product_categories.slug VARCHAR(120); the shorter wins. */
    private static final int MAX_LENGTH = 120;

    private Slugs() {
    }

    /** Returns null (not "") when nothing survives, so callers can use a plain null check. */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String slug = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            return null;
        }
        if (slug.length() > MAX_LENGTH) {
            // Trim at the length limit, then re-strip a trailing hyphen so the cut never
            // leaves "long-product-name-" as the canonical URL.
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }

    /**
     * Appends -2, -3, ... until {@code taken} says the slug is free.
     *
     * The loop is bounded because {@code taken} hits the database: a caller that passed a
     * predicate which is always true would otherwise spin forever inside a transaction.
     * Hitting the bound is a bug, not a user error, hence IllegalStateException.
     */
    static String uniquify(String base, Predicate<String> taken) {
        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= 1000; suffix++) {
            String candidate = truncateFor(base, suffix) + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not derive a unique slug from '" + base + "'");
    }

    /** Keeps base + "-" + suffix inside the column, so the suffix is never the part that gets cut. */
    private static String truncateFor(String base, int suffix) {
        int room = MAX_LENGTH - (String.valueOf(suffix).length() + 1);
        return base.length() <= room ? base : base.substring(0, room).replaceAll("-+$", "");
    }
}
