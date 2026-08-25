package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.email.template.ProductEmails;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ProductEmails} is a pure function of its arguments, same as the rest
 * of the {@code email.template} package - see {@code EmailTemplateTest} for
 * the sibling coverage of {@code OrderEmails}/{@code AccountEmails}.
 */
class ProductEmailsTest {

    private static final List<String> TO = List.of("support@procurepaddy.com");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-08-25T09:30:00+01:00");

    @Test
    void carriesTheCompanyTheRequesterTheUnitAndTheNote() {
        EmailMessage message = ProductEmails.unitOfMeasureRequested(
                TO, "Ada Millers Ltd", "Chidi Okafor", "chidi@ada-millers.test",
                "50L Jerry Can", "We buy palm oil in these, not bags.", SUBMITTED_AT);

        assertThat(message.subject()).contains("50L Jerry Can");
        assertThat(message.htmlBody())
                .contains("Ada Millers Ltd")
                .contains("Chidi Okafor")
                .contains("chidi@ada-millers.test")
                .contains("50L Jerry Can")
                .contains("We buy palm oil in these, not bags.");
        assertThat(message.textBody())
                .contains("Ada Millers Ltd")
                .contains("Chidi Okafor")
                .contains("50L Jerry Can")
                .contains("We buy palm oil in these, not bags.");
    }

    /**
     * The recipient is always ProcurePal's own trusted support inbox (see
     * {@code EmailRecipients.forUnitOfMeasureRequests}), never an address a
     * stranger typed - so unlike VendorEmails' applicant-facing messages this
     * one is TRANSACTIONAL, not VERIFICATION. See the class doc for why.
     */
    @Test
    void isTransactionalRatherThanVerification() {
        EmailMessage message = ProductEmails.unitOfMeasureRequested(
                TO, "Ada Millers Ltd", "Chidi Okafor", "chidi@ada-millers.test",
                "50L Jerry Can", null, SUBMITTED_AT);

        assertThat(message.kind()).isEqualTo(EmailKind.TRANSACTIONAL);
    }

    /** A blank note renders no note callout at all, rather than an empty one. */
    @Test
    void omitsTheNoteBlockWhenThereIsNoNote() {
        EmailMessage message = ProductEmails.unitOfMeasureRequested(
                TO, "Ada Millers Ltd", "Chidi Okafor", "chidi@ada-millers.test",
                "50L Jerry Can", "  ", SUBMITTED_AT);

        assertThat(message.htmlBody()).contains("50L Jerry Can");
        assertThat(message.textBody()).doesNotContain("Note:");
    }

    /** A blank requester email is a real case - a sub-user with no contact email set. */
    @Test
    void omitsTheEmailRowWhenTheRequesterHasNoContactEmailOnFile() {
        EmailMessage message = ProductEmails.unitOfMeasureRequested(
                TO, "Ada Millers Ltd", "warehouse-lead", "", "50L Jerry Can", null, SUBMITTED_AT);

        assertThat(message.htmlBody()).doesNotContain("Their email");
        assertThat(message.textBody()).doesNotContain("Their email");
    }

    /** Requested-unit text is user-supplied and reaches a mail client that will render markup. */
    @Test
    void escapesUserSuppliedTextInsteadOfEmittingItAsMarkup() {
        EmailMessage message = ProductEmails.unitOfMeasureRequested(
                TO, "<script>alert(1)</script>", "Chidi Okafor", "chidi@ada-millers.test",
                "<img src=x onerror=alert(1)>", "<b>note</b>", SUBMITTED_AT);

        assertThat(message.htmlBody())
                .doesNotContain("<script>alert(1)</script>")
                .doesNotContain("<img src=x onerror")
                .doesNotContain("<b>note</b>")
                .contains("&lt;script&gt;")
                .contains("&lt;img src=x onerror=alert(1)&gt;")
                .contains("&lt;b&gt;note&lt;/b&gt;");
    }
}
