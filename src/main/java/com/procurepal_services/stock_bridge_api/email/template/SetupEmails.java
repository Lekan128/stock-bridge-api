package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.timestamp;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Procurepaddy founding setups (LANDING_PAGE_PLAN.md, step 4).
 *
 * <p>{@link #setupRequested} goes to the support inbox ({@code app.email.vendor-waitlist-address},
 * eligible by name under EmailEligibility's rule 6). It leads with the one action that matters,
 * replying on WhatsApp, because the plan promises a reply within 5 minutes and a reader who has to
 * open the admin panel first has already used two of them.
 */
public final class SetupEmails {

    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("d MMMM", Locale.UK);

    private SetupEmails() {
    }

    public static EmailMessage setupRequested(
            List<String> to,
            String businessName,
            String whatsapp,
            String source,
            boolean founding,
            LocalDate setupWeekStarts,
            OffsetDateTime requestedAt,
            String replyUrl,
            String appBaseUrl) {
        String plan = founding ? "Founding (week of " + WEEK.format(setupWeekStarts) + ")" : "Regular (founding places gone)";
        String body = paragraph("A shop has booked a setup. Reply on WhatsApp within 5 minutes (8am to 6pm, Monday to "
                        + "Saturday) and ask for their product list.")
                + detailTable(List.of(
                        new Detail("Business", businessName),
                        new Detail("WhatsApp", whatsapp),
                        new Detail("Plan", plan),
                        new Detail("From", source),
                        new Detail("Requested", timestamp(requestedAt))))
                + button("Reply on WhatsApp", replyUrl)
                + (queueUrl(appBaseUrl).isBlank() ? "" : paragraph("Then mark it contacted in the setup queue: " + queueUrl(appBaseUrl)));

        String text = "A shop has booked a setup. Reply on WhatsApp within 5 minutes.\n\n"
                + "Business: " + businessName + "\n"
                + "WhatsApp: " + whatsapp + "\n"
                + "Plan: " + plan + "\n"
                + "From: " + source + "\n"
                + "Requested: " + timestamp(requestedAt) + "\n\n"
                + "Reply: " + replyUrl + "\n"
                + (queueUrl(appBaseUrl).isBlank() ? "" : "Queue: " + queueUrl(appBaseUrl) + "\n");

        return new EmailMessage(to, "Reply now: " + businessName + " booked a setup",
                page("New setup request", businessName + " booked a setup. Reply on WhatsApp now.", body),
                text, EmailKind.TRANSACTIONAL);
    }

    /** To the support inbox: a shop sent its product list from the app. Load it within 24 hours. */
    public static EmailMessage listReceived(
            List<String> to, String businessName, String companyId, String whatsapp, int files, String appBaseUrl) {
        String body = paragraph(businessName + " sent " + files + (files == 1 ? " file" : " files")
                        + " with Send us your list. The offer is to load it within 24 hours.")
                + detailTable(List.of(
                        new Detail("Business", businessName),
                        new Detail("Company ID", companyId),
                        new Detail("WhatsApp", whatsapp == null || whatsapp.isBlank() ? "Not given" : whatsapp)))
                + (queueUrl(appBaseUrl).isBlank() ? "" : button("Open the setup queue", queueUrl(appBaseUrl)));
        String text = businessName + " sent " + files + (files == 1 ? " file" : " files")
                + " with Send us your list. Load it within 24 hours.\n\n"
                + "Company ID: " + companyId + "\n"
                + "WhatsApp: " + (whatsapp == null || whatsapp.isBlank() ? "Not given" : whatsapp) + "\n"
                + (queueUrl(appBaseUrl).isBlank() ? "" : "Queue: " + queueUrl(appBaseUrl) + "\n");
        return new EmailMessage(to, "List received: " + businessName,
                page("Product list received", businessName + " sent their product list.", body),
                text, EmailKind.TRANSACTIONAL);
    }

    private static String queueUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/admin/setup-requests";
    }
}
