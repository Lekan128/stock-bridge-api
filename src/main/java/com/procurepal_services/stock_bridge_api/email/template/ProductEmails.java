package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.bold;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.callout;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.escape;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraphHtml;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.timestamp;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The product catalog's "can't find your unit? tell us" box: one message, to
 * ProcurePal's own support inbox, when a tenant needs a unit of measure that
 * is not on {@code UnitOfMeasure}'s fixed list.
 *
 * <h2>Why TRANSACTIONAL, and not VERIFICATION like VendorEmails' applicant mail</h2>
 * {@link VendorEmails} reaches for {@code EmailKind.VERIFICATION} for every
 * applicant-facing message because the applicant has no {@code users} row for
 * {@code EmailEligibility} to find - see that class's doc for the trap. That
 * trap does not apply here: the only recipient this template is ever built
 * for is {@code app.email.vendor-waitlist-address} (via
 * {@code EmailRecipients.forUnitOfMeasureRequests}, which reuses the same
 * inbox {@link VendorEmails#newApplicationForOperator} sends to), and
 * {@code EmailEligibility}'s rule 6 names that exact address as eligible for
 * TRANSACTIONAL mail by configuration. Reaching for VERIFICATION would be
 * borrowing a bypass this message never needed, on an address that was never
 * in doubt.
 */
public final class ProductEmails {

    private ProductEmails() {
    }

    /**
     * To ProcurePal: a tenant has asked for a unit of measure that is not on
     * the current list.
     *
     * <p>Carries the company, the person who asked, and whatever they typed -
     * enough for whoever reads the inbox to judge whether this is a one-off
     * or the third request this month for the same missing unit, without
     * opening the admin panel first.
     *
     * @param requesterEmail the address to reply to; may be blank for a
     *     sub-user with no contact email set (their username is in {@code
     *     requesterName} already via the caller, so the row is still
     *     identifiable to a reviewer)
     * @param note free text explaining why; may be blank, in which case no
     *     note block is rendered at all
     */
    public static EmailMessage unitOfMeasureRequested(
            List<String> to,
            String companyName,
            String requesterName,
            String requesterEmail,
            String requestedUnit,
            String note,
            OffsetDateTime submittedAt) {
        String body = paragraphHtml(bold(escape(companyName))
                        + " has asked for a unit that is not on the current list.")
                + detailTable(List.of(
                        new Detail("Company", companyName),
                        new Detail("Requested by", requesterName),
                        new Detail("Their email", requesterEmail),
                        new Detail("Requested unit", requestedUnit),
                        new Detail("Submitted", timestamp(submittedAt))))
                + noteBlockHtml(note);

        String text = companyName + " has asked for a unit that is not on the current list.\n\n"
                + "Company: " + companyName + "\n"
                + "Requested by: " + requesterName + "\n"
                + (requesterEmail == null || requesterEmail.isBlank() ? "" : "Their email: " + requesterEmail + "\n")
                + "Requested unit: " + requestedUnit + "\n"
                + "Submitted: " + timestamp(submittedAt) + "\n"
                + noteBlockText(note);

        return new EmailMessage(
                to,
                "Unit request: " + requestedUnit,
                page("New unit request", companyName + " asked for \"" + requestedUnit + "\".", body),
                text,
                EmailKind.TRANSACTIONAL);
    }

    private static String noteBlockHtml(String note) {
        return note == null || note.isBlank() ? "" : callout(note);
    }

    private static String noteBlockText(String note) {
        return note == null || note.isBlank() ? "" : "\nNote: " + note + "\n";
    }
}
