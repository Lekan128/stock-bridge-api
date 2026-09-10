package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.email.preferences.UnsubscribeProperties;
import com.procurepal_services.stock_bridge_api.email.preferences.UnsubscribeTokenService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

/**
 * Plain Mockito, no Spring context - the same shape as S3ImageServiceTest, and for
 * the same reason: EmailSender's contract is that an email problem never becomes an
 * exception, so every case here asserts on the return value and none expects a throw.
 */
class EmailSenderTest {

    private static final EmailProperties CONFIGURED = new EmailProperties(
            true, "us-east-1", "no-reply@procurepal.test", "ProcurePal",
            "support@procurepal.test", "primary-config-set", "https://app.procurepal.test", null,
            // vendorWaitlistAddress. Null here means "take the default" - see
            // EmailProperties' compact constructor. Nothing in this class reads it;
            // it is the ninth component and has to be supplied.
            null);
    private static final EmailProperties DISABLED = new EmailProperties(
            false, null, "no-reply@procurepal.test", null, null, null, null, null, null);
    private static final EmailProperties NO_FROM_ADDRESS = new EmailProperties(
            true, null, "  ", null, null, null, null, null, null);

    /**
     * A real UnsubscribeTokenService rather than a mock, on purpose: these tests
     * assert on the exact header VALUE, and a stubbed URL would prove the header
     * plumbing works while leaving open the one thing that actually breaks in
     * production - two recipients silently getting the same token.
     */
    private static final UnsubscribeTokenService UNSUBSCRIBE = new UnsubscribeTokenService(
            new UnsubscribeProperties("test-unsubscribe-signing-secret", "https://api.procurepal.test/"));

    private static final UnsubscribeTokenService UNSUBSCRIBE_UNCONFIGURED =
            new UnsubscribeTokenService(new UnsubscribeProperties("  ", "  "));

    private static final EmailMessage MESSAGE = new EmailMessage(
            List.of("buyer@example.com"), "Order PP-1 confirmed", "<p>Hi</p>", "Hi");

    private SesV2Client sesV2Client;

    @BeforeEach
    void setUp() {
        sesV2Client = mock(SesV2Client.class);
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ses-message-id").build());
    }

    @Test
    void sendsThroughSesWhenConfigured() {
        assertThat(sender(CONFIGURED).send(MESSAGE)).isTrue();
        verify(sesV2Client).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void buildsTheRequestFromTheConfiguredIdentity() {
        sender(CONFIGURED).send(MESSAGE);

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client).sendEmail(request.capture());
        SendEmailRequest sent = request.getValue();

        assertThat(sent.fromEmailAddress()).isEqualTo("\"ProcurePal\" <no-reply@procurepal.test>");
        assertThat(sent.destination().toAddresses()).containsExactly("buyer@example.com");
        assertThat(sent.replyToAddresses()).containsExactly("support@procurepal.test");
        assertThat(sent.configurationSetName()).isEqualTo("primary-config-set");
        assertThat(sent.content().simple().subject().data()).isEqualTo("Order PP-1 confirmed");
        assertThat(sent.content().simple().body().html().data()).isEqualTo("<p>Hi</p>");
        assertThat(sent.content().simple().body().text().data()).isEqualTo("Hi");
    }

    /**
     * The blank ones must be absent, not present-and-empty: SES rejects an empty
     * configuration set name outright, which would fail every send on a deploy that
     * simply never created one.
     */
    @Test
    void omitsOptionalHeadersWhenTheyAreNotConfigured() {
        EmailProperties minimal = new EmailProperties(
                true, null, "no-reply@procurepal.test", null, "  ", "  ", null, null, null);

        sender(minimal).send(MESSAGE);

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client).sendEmail(request.capture());
        assertThat(request.getValue().replyToAddresses()).isEmpty();
        assertThat(request.getValue().configurationSetName()).isNull();
        // No display name configured, so the bare address is the whole From header.
        assertThat(request.getValue().fromEmailAddress()).isEqualTo("no-reply@procurepal.test");
    }

    @Test
    void doesNotCallSesWhenDisabled() {
        assertThat(sender(DISABLED).send(MESSAGE)).isFalse();
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void doesNotCallSesWhenNoFromAddressIsConfigured() {
        assertThat(sender(NO_FROM_ADDRESS).send(MESSAGE)).isFalse();
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void doesNotCallSesWhenThereIsNobodyToSendTo() {
        EmailMessage nobody = new EmailMessage(List.of(), "Subject", "<p>Hi</p>", "Hi");

        assertThat(sender(CONFIGURED).send(nobody)).isFalse();
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    /**
     * The single most important assertion in this file. A throttled or misconfigured
     * SES must never reach the checkout that triggered the email.
     */
    @Test
    void swallowsSesFailuresInsteadOfThrowing() {
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SesV2Exception.builder().message("Daily message quota exceeded").build());

        assertThat(sender(CONFIGURED).send(MESSAGE)).isFalse();
    }

    @Test
    void toleratesANullMessage() {
        assertThat(sender(CONFIGURED).send(null)).isFalse();
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    // ========================================================================
    // RFC 8058 one-click unsubscribe headers.
    //
    // The acceptance criterion is a pair, not a single assertion: the headers
    // must be present on PROMOTIONAL mail and absent from everything else. Only
    // testing the first half would pass just as happily if the headers were
    // stapled to every message, which is the failure that puts an "unsubscribe"
    // button on an order receipt.
    // ========================================================================

    @Test
    void promotionalMailCarriesBothUnsubscribeHeaders() {
        EmailMessage promotional = new EmailMessage(
                List.of("buyer@example.com"), "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);

        assertThat(sender(CONFIGURED).send(promotional)).isTrue();

        List<MessageHeader> headers = captureSingleRequest().content().simple().headers();
        assertThat(headers).hasSize(2);
        assertThat(headerValue(headers, "List-Unsubscribe"))
                .startsWith("<https://api.procurepal.test/api/email/unsubscribe?token=")
                .endsWith(">");
        // Byte-for-byte what RFC 8058 requires. Anything else and Gmail renders no
        // button, which is indistinguishable from having shipped no header at all.
        assertThat(headerValue(headers, "List-Unsubscribe-Post")).isEqualTo("List-Unsubscribe=One-Click");
    }

    /**
     * The token in the header must be the one the endpoint will accept for that
     * exact recipient. Asserting the header merely "contains a token" would not
     * catch a normalisation drift between minting and verifying, which is the
     * subtle way this feature dies.
     */
    @Test
    void theTokenInTheHeaderResolvesBackToTheRecipient() {
        EmailMessage promotional = new EmailMessage(
                List.of("Buyer@Example.com"), "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);

        sender(CONFIGURED).send(promotional);

        String token = tokenFromHeader(captureSingleRequest());
        assertThat(UNSUBSCRIBE.addressFrom(token)).isEqualTo("buyer@example.com");
    }

    @Test
    void transactionalMailCarriesNeitherHeader() {
        assertThat(sender(CONFIGURED).send(MESSAGE)).isTrue();

        assertThat(captureSingleRequest().content().simple().headers()).isEmpty();
    }

    @Test
    void verificationAndSecurityMailCarryNeitherHeader() {
        EmailSender emailSender = sender(CONFIGURED);
        emailSender.send(new EmailMessage(
                List.of("new@example.com"), "Confirm your email", "<p>Hi</p>", "Hi", EmailKind.VERIFICATION));
        emailSender.send(new EmailMessage(
                List.of("new@example.com"), "Your password changed", "<p>Hi</p>", "Hi", EmailKind.SECURITY));

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client, times(2)).sendEmail(request.capture());
        assertThat(request.getAllValues())
                .allSatisfy(sent -> assertThat(sent.content().simple().headers()).isEmpty());
    }

    /**
     * The design wrinkle this whole feature turns on. One SES send carries one set
     * of headers, so three recipients on one promotional message could only ever
     * carry one recipient's token - and two of them pressing Unsubscribe would
     * silence the third.
     */
    @Test
    void promotionalMailFansOutToOneSendPerRecipientWithDistinctTokens() {
        EmailMessage promotional = new EmailMessage(
                List.of("one@example.com", "two@example.com", "three@example.com"),
                "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);

        assertThat(sender(CONFIGURED).send(promotional)).isTrue();

        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client, times(3)).sendEmail(request.capture());

        // Each send addresses exactly one person...
        assertThat(request.getAllValues())
                .extracting(sent -> sent.destination().toAddresses())
                .containsExactly(
                        List.of("one@example.com"), List.of("two@example.com"), List.of("three@example.com"));
        // ...and each carries that person's own token, not a shared one.
        assertThat(request.getAllValues())
                .extracting(sent -> UNSUBSCRIBE.addressFrom(tokenFromHeader(sent)))
                .containsExactly("one@example.com", "two@example.com", "three@example.com");
    }

    /** Transactional mail must keep the cheap single call, however many people are on it. */
    @Test
    void transactionalMailStillSendsOnceForAllItsRecipients() {
        EmailMessage toEveryone = new EmailMessage(
                List.of("one@example.com", "two@example.com", "three@example.com"),
                "Order PP-1 confirmed", "<p>Hi</p>", "Hi");

        assertThat(sender(CONFIGURED).send(toEveryone)).isTrue();

        assertThat(captureSingleRequest().destination().toAddresses())
                .containsExactly("one@example.com", "two@example.com", "three@example.com");
    }

    /**
     * The one place the email package refuses to degrade gracefully. Marketing
     * nobody can opt out of is spam, and the reputation damage is shared by every
     * tenant - see EmailSender's class doc.
     */
    @Test
    void promotionalMailIsDroppedWhenUnsubscribeIsNotConfigured() {
        EmailSender emailSender = new EmailSender(CONFIGURED, sesV2Client, UNSUBSCRIBE_UNCONFIGURED);
        EmailMessage promotional = new EmailMessage(
                List.of("buyer@example.com"), "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);

        assertThat(emailSender.send(promotional)).isFalse();
        verify(sesV2Client, never()).sendEmail(any(SendEmailRequest.class));
    }

    /** ...while everything else still sends on exactly the same deploy. */
    @Test
    void transactionalMailIsUnaffectedWhenUnsubscribeIsNotConfigured() {
        EmailSender emailSender = new EmailSender(CONFIGURED, sesV2Client, UNSUBSCRIBE_UNCONFIGURED);

        assertThat(emailSender.send(MESSAGE)).isTrue();
        verify(sesV2Client).sendEmail(any(SendEmailRequest.class));
    }

    /**
     * With N calls some can fail, so "accepted" has to mean all of them. A caller
     * that read a partial failure as success would report a campaign as delivered
     * to people who never got it.
     */
    @Test
    void promotionalFanOutReportsFailureWhenAnyRecipientIsRejected() {
        when(sesV2Client.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(SendEmailResponse.builder().messageId("ok").build())
                .thenThrow(SesV2Exception.builder().message("Throttled").build());
        EmailMessage promotional = new EmailMessage(
                List.of("one@example.com", "two@example.com"),
                "New catalogue", "<p>Offers</p>", "Offers", EmailKind.PROMOTIONAL);

        assertThat(sender(CONFIGURED).send(promotional)).isFalse();
        // Still attempted for everybody: one throttled recipient must not cancel the rest.
        verify(sesV2Client, times(2)).sendEmail(any(SendEmailRequest.class));
    }

    private EmailSender sender(EmailProperties properties) {
        return new EmailSender(properties, sesV2Client, UNSUBSCRIBE);
    }

    private SendEmailRequest captureSingleRequest() {
        ArgumentCaptor<SendEmailRequest> request = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(sesV2Client).sendEmail(request.capture());
        return request.getValue();
    }

    private static String tokenFromHeader(SendEmailRequest sent) {
        String value = headerValue(sent.content().simple().headers(), "List-Unsubscribe");
        return value.substring(value.indexOf("token=") + "token=".length(), value.length() - 1);
    }

    private static String headerValue(List<MessageHeader> headers, String name) {
        return headers.stream()
                .filter(header -> header.name().equals(name))
                .map(MessageHeader::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + name + " header was set"));
    }
}
