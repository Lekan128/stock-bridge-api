package com.procurepal_services.stock_bridge_api.email.template;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The chrome every email shares - header, card, button, footer - plus the small
 * formatting helpers the templates would otherwise each reinvent.
 *
 * <h2>Why hand-written HTML and not a template engine</h2>
 * Thymeleaf or Freemarker would mean a second rendering stack, a directory of
 * templates that no compiler checks against the code that fills them, and a class
 * of failure - a typo'd variable name - that only appears when a customer gets a
 * blank email. There are a dozen emails here, all built from the same three or four
 * blocks, and a Java method that takes typed arguments is checked at build time.
 * Revisit this if the count grows or if non-developers need to edit copy.
 *
 * <h2>Why the CSS is inline and the layout is a table</h2>
 * Not stylistic. Gmail strips {@code <style>} blocks, and Outlook's rendering engine
 * is Word's, which does not implement flexbox, grid, or float reliably. Inline
 * styles on nested tables is the only layout that survives both, and it is why this
 * markup looks fifteen years out of date. Do not "modernise" it without testing in
 * Outlook.
 */
public final class EmailLayout {

    /** ProcurePal's brand green, matched to the frontend's primary. */
    private static final String BRAND = "#15803d";
    private static final String TEXT = "#1f2937";
    private static final String MUTED = "#6b7280";
    private static final String BORDER = "#e5e7eb";
    private static final String CANVAS = "#f4f5f7";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("d MMM yyyy 'at' h:mma");
    private static final DecimalFormat AMOUNT = new DecimalFormat("#,##0.00");

    private EmailLayout() {
    }

    /**
     * Wraps rendered body HTML in the shared shell.
     *
     * <p>{@code preheader} is the line most mail clients show next to the subject in
     * the inbox list. Left unset, they helpfully use the first text in the body,
     * which here would be the header - so every inbox row would read "ProcurePal".
     * It is hidden in the body itself by the zero-height span below, which is the
     * standard (ugly) trick for exactly this.
     */
    public static String page(String heading, String preheader, String bodyHtml) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>%s</title>
                </head>
                <body style="margin:0; padding:0; background:%s;">
                  <span style="display:none; max-height:0; overflow:hidden; opacity:0;">%s</span>
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background:%s; padding:24px 12px;">
                    <tr><td align="center">
                      <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="max-width:560px; background:#ffffff; border:1px solid %s; border-radius:12px; overflow:hidden; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                        <tr><td style="background:%s; padding:20px 28px;">
                          <span style="color:#ffffff; font-size:18px; font-weight:700; letter-spacing:-0.2px;">ProcurePal</span>
                        </td></tr>
                        <tr><td style="padding:28px;">
                          <h1 style="margin:0 0 16px; font-size:20px; line-height:1.3; color:%s; font-weight:650;">%s</h1>
                          %s
                        </td></tr>
                        <tr><td style="padding:18px 28px; border-top:1px solid %s; background:#fafafa;">
                          <p style="margin:0; font-size:12px; line-height:1.6; color:%s;">
                            You are receiving this because your company has a ProcurePal account.
                            This is an automated message - replies to it are not monitored unless a
                            reply-to address is configured.
                          </p>
                        </td></tr>
                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """
                .formatted(
                        escape(heading), CANVAS, escape(preheader), CANVAS, BORDER, BRAND,
                        TEXT, escape(heading), bodyHtml, BORDER, MUTED);
    }

    public static String paragraph(String text) {
        return "<p style=\"margin:0 0 14px; font-size:15px; line-height:1.6; color:%s;\">%s</p>"
                .formatted(TEXT, escape(text));
    }

    /** For copy that needs one bolded fragment - an order number, an amount - inside a sentence. */
    public static String paragraphHtml(String html) {
        return "<p style=\"margin:0 0 14px; font-size:15px; line-height:1.6; color:%s;\">%s</p>".formatted(TEXT, html);
    }

    public static String bold(String text) {
        return "<strong style=\"font-weight:650;\">" + escape(text) + "</strong>";
    }

    /**
     * A bulletproof-ish call to action. Renders as a real link with a background,
     * which every client draws; Outlook loses the rounded corners and nothing else.
     *
     * <p>Returns empty when the URL is blank, which is the unconfigured
     * {@code app.email.app-base-url} case - a button linking to
     * {@code /app/orders/123} with no host is a broken link, and no button at all
     * is better than one that 404s.
     */
    public static String button(String label, String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return """
                <table role="presentation" cellpadding="0" cellspacing="0" style="margin:20px 0 8px;">
                  <tr><td style="background:%s; border-radius:8px;">
                    <a href="%s" style="display:inline-block; padding:11px 22px; font-size:15px; font-weight:600; color:#ffffff; text-decoration:none;">%s</a>
                  </td></tr>
                </table>
                """
                .formatted(BRAND, escape(url), escape(label));
    }

    /** Label/value pairs - order number, total, delivery address. The backbone of most of these emails. */
    public static String detailTable(List<Detail> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (Detail detail : details) {
            if (detail == null || detail.value() == null || detail.value().isBlank()) {
                continue;
            }
            rows.append("""
                    <tr>
                      <td style="padding:7px 0; font-size:14px; color:%s; vertical-align:top; width:40%%;">%s</td>
                      <td style="padding:7px 0; font-size:14px; color:%s; vertical-align:top; font-weight:600;">%s</td>
                    </tr>
                    """.formatted(MUTED, escape(detail.label()), TEXT, escape(detail.value())));
        }
        if (rows.isEmpty()) {
            return "";
        }
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin:8px 0 16px; border-top:1px solid %s; border-bottom:1px solid %s;">
                  %s
                </table>
                """.formatted(BORDER, BORDER, rows);
    }

    /** The line items on an order. Quantity and unit price on one line, line total right-aligned. */
    public static String lineItems(List<LineItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (LineItem item : items) {
            rows.append("""
                    <tr>
                      <td style="padding:10px 0; border-bottom:1px solid %s; font-size:14px; color:%s;">
                        <span style="font-weight:600;">%s</span><br>
                        <span style="font-size:13px; color:%s;">%s</span>
                      </td>
                      <td style="padding:10px 0; border-bottom:1px solid %s; font-size:14px; color:%s; text-align:right; white-space:nowrap; vertical-align:top;">%s</td>
                    </tr>
                    """.formatted(
                    BORDER, TEXT, escape(item.name()), MUTED, escape(item.quantityLine()),
                    BORDER, TEXT, escape(item.lineTotal())));
        }
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin:8px 0 4px;">
                  %s
                </table>
                """.formatted(rows);
    }

    /** Grand total, visually separated from the line items above it. */
    public static String total(String label, String amount) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin:0 0 16px;">
                  <tr>
                    <td style="padding:12px 0; font-size:15px; color:%s; font-weight:650;">%s</td>
                    <td style="padding:12px 0; font-size:17px; color:%s; font-weight:700; text-align:right; white-space:nowrap;">%s</td>
                  </tr>
                </table>
                """.formatted(TEXT, escape(label), BRAND, escape(amount));
    }

    /**
     * A tinted callout for the one thing the reader must act on or must not miss -
     * "confirm receipt to move this into your stock", "your account is suspended".
     */
    public static String callout(String text) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="margin:4px 0 16px; background:#f0fdf4; border-left:3px solid %s; border-radius:4px;">
                  <tr><td style="padding:12px 14px; font-size:14px; line-height:1.6; color:%s;">%s</td></tr>
                </table>
                """.formatted(BRAND, TEXT, escape(text));
    }

    /**
     * Currency is rendered as an ISO code and a space ("NGN 12,500.00") rather than
     * a symbol. The naira sign is outside Windows-1252, and Outlook on a
     * non-UTF-8-defaulting Windows install renders it as a box - on the number a
     * customer is most likely to scrutinise. The code is unambiguous everywhere.
     *
     * <p>HALF_UP because these are prices being displayed, not recomputed: the value
     * is already the persisted total, and this only guards against a scale the
     * database happens to store wider than two places.
     */
    public static String money(String currency, BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        String formatted = AMOUNT.format(amount.setScale(2, RoundingMode.HALF_UP));
        return (currency == null || currency.isBlank() ? "" : currency.trim() + " ") + formatted;
    }

    public static String timestamp(OffsetDateTime value) {
        return value == null ? "" : TIMESTAMP.format(value);
    }

    /**
     * Turns an enum-shaped status into something a customer reads - OUT_FOR_DELIVERY
     * becomes "Out for delivery". Same transformation
     * {@code OrderLifecycleService.humanise} does for the in-app bell, repeated here
     * rather than shared because that one is private to a service with no business
     * being a dependency of the email package.
     */
    public static String humanise(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return "";
        }
        String spaced = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /**
     * Every interpolated value goes through this. Product names, company names and
     * delivery notes are all user-supplied and all reach a mail client that will
     * happily execute what it is given - a product called
     * {@code <img src=x onerror=...>} must arrive as text, not as markup. The one
     * exception is {@link #paragraphHtml}, which exists for copy this class itself
     * assembles from already-escaped fragments.
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Joins the non-blank parts of an address into one comma-separated line. */
    public static String joinNonBlank(String separator, String... parts) {
        List<String> present = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.trim());
            }
        }
        return String.join(separator, present);
    }

    public record Detail(String label, String value) {
    }

    public record LineItem(String name, String quantityLine, String lineTotal) {
    }
}
