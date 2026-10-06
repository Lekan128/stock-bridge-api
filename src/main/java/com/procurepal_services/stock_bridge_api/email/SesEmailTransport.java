package com.procurepal_services.stock_bridge_api.email;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/**
 * Amazon SES v2 as the mail provider. Selected with {@code app.email.provider=ses};
 * shelved in favour of {@link ResendEmailTransport} while SES production access is
 * pending, and kept working (and tested) so switching back is a config change.
 *
 * <p>This is the request-building half of what used to be {@code EmailSender},
 * moved verbatim. {@link SesClientConfig} still builds the client unconditionally -
 * building it talks to nobody - so this class is the only thing that changes with
 * the provider.
 */
@Component
@ConditionalOnProperty(prefix = "app.email", name = "provider", havingValue = "ses")
public class SesEmailTransport implements EmailTransport {

    private final SesV2Client sesV2Client;
    private final EmailProperties emailProperties;

    public SesEmailTransport(SesV2Client sesV2Client, EmailProperties emailProperties) {
        this.sesV2Client = sesV2Client;
        this.emailProperties = emailProperties;
    }

    @Override
    public String name() {
        return "ses";
    }

    /**
     * Always true: SES credentials come from {@code app.aws}, and a deploy with none
     * still builds a client (see {@link SesClientConfig}). Whether a send is
     * attempted is decided by {@link EmailProperties#isConfigured()}, exactly as
     * before the provider became switchable.
     */
    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String send(EmailMessage message, String unsubscribeUrl) {
        return sesV2Client.sendEmail(buildRequest(message, unsubscribeUrl)).messageId();
    }

    /**
     * @param unsubscribeUrl this recipient's one-click unsubscribe URL, or null for
     *     every kind of mail that must not carry one. Null is the overwhelmingly
     *     common case and leaves the request byte-identical to what this method
     *     built before RFC 8058 support existed, which is the property that keeps
     *     order receipts out of the blast radius of this change.
     */
    private SendEmailRequest buildRequest(EmailMessage message, String unsubscribeUrl) {
        Message.Builder simple = Message.builder()
                .subject(utf8(message.subject()))
                .body(buildBody(message));
        if (unsubscribeUrl != null) {
            simple.headers(unsubscribeHeaders(unsubscribeUrl));
        }

        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(emailProperties.formattedFrom())
                .destination(Destination.builder().toAddresses(message.to()).build())
                .content(EmailContent.builder().simple(simple.build()).build());

        // Both are optional refinements, and an unset one must not become the
        // string "null" in a header - see EmailProperties.isConfigured().
        if (notBlank(emailProperties.replyToAddress())) {
            request.replyToAddresses(emailProperties.replyToAddress().trim());
        }
        if (notBlank(emailProperties.configurationSet())) {
            request.configurationSetName(emailProperties.configurationSet().trim());
        }
        return request.build();
    }

    /**
     * The two RFC 8058 headers, as an SES v2 {@code Message.headers} list.
     *
     * <h2>Why Message.headers() and not a raw MIME message</h2>
     * SES v2 offers two shapes of {@code EmailContent}. A {@code simple} message is
     * the subject and bodies as structured fields and SES assembles the MIME itself;
     * a {@code raw} message is a block of bytes the caller has assembled, headers
     * and multipart boundaries and transfer encodings included. Custom headers were
     * historically only possible with the second, which is why so much advice on the
     * internet says to build MIME by hand.
     *
     * <p>That advice is out of date for this SDK. {@code Message.headers} exists in
     * software.amazon.awssdk:sesv2 2.47.4 (the version the AWS BOM in pom.xml pins),
     * takes a list of name/value pairs, and is explicitly the supported way to add
     * List-Unsubscribe. It was checked against the actual API rather than assumed.
     *
     * <p>Switching to raw would have been the wrong trade even if it were the only
     * option. It means this class becomes responsible for MIME: multipart/alternative
     * boundaries between the HTML and text parts, quoted-printable or base64 encoding
     * for the non-ASCII that {@link #utf8} exists to protect, RFC 2047 encoding of a
     * subject line containing a customer's name, and correct line folding - every one
     * of which is a way to produce a message that renders as source code in somebody's
     * client.
     *
     * <p>The header names, the one-click literal and the reasoning for offering no
     * {@code mailto:} alternative are on {@link EmailSender}, shared by every
     * provider.
     */
    private static List<MessageHeader> unsubscribeHeaders(String unsubscribeUrl) {
        return List.of(
                MessageHeader.builder()
                        .name(EmailSender.LIST_UNSUBSCRIBE_HEADER)
                        .value(EmailSender.listUnsubscribeValue(unsubscribeUrl))
                        .build(),
                MessageHeader.builder()
                        .name(EmailSender.LIST_UNSUBSCRIBE_POST_HEADER)
                        .value(EmailSender.ONE_CLICK)
                        .build());
    }

    private static Body buildBody(EmailMessage message) {
        Body.Builder body = Body.builder();
        if (notBlank(message.htmlBody())) {
            body.html(utf8(message.htmlBody()));
        }
        if (notBlank(message.textBody())) {
            body.text(utf8(message.textBody()));
        }
        return body.build();
    }

    /**
     * Naira amounts, Nigerian addresses and customer names all routinely carry
     * characters outside US-ASCII, and SES defaults to 7-bit when no charset is
     * given - which turns a currency symbol into a question mark rather than
     * failing loudly.
     */
    private static Content utf8(String data) {
        return Content.builder().charset("UTF-8").data(data).build();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
