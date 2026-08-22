package com.procurepal_services.stock_bridge_api.email;

import com.procurepal_services.stock_bridge_api.email.preferences.UnsubscribeTokenService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * The SES call, and the promise that it never becomes somebody else's problem.
 * Modelled on {@link com.procurepal_services.stock_bridge_api.storage.S3ImageService}:
 * every failure mode - unconfigured, no recipients, SES itself refusing - is
 * absorbed here and reported as a {@code false} return plus a log line.
 *
 * <h2>Why nothing may propagate</h2>
 * An email is a courtesy attached to something that already happened. The order is
 * placed, the payment is applied, the user exists. Letting a throttled SES call or
 * an expired sending identity turn a successful checkout into a 500 - or worse,
 * roll it back - would trade a real business outcome for a notification, which is
 * exactly backwards. The corollary is that a caller gets no useful signal from the
 * return value and none of them check it; it exists for tests and for the log.
 *
 * <h2>Unconfigured logs the message instead of sending it</h2>
 * Locally and in the test suite there is no SES account, and the useful behaviour
 * there is to see that the right mail would have gone to the right people. The body
 * is deliberately not logged - order emails contain a customer's delivery address
 * and phone number, and that does not belong in an application log.
 *
 * <h2>PROMOTIONAL mail is sent one recipient at a time. Everything else is not.</h2>
 * This is the one place the shape of a send depends on {@link EmailKind}, and the
 * reason is RFC 8058. A promotional message must carry a {@code List-Unsubscribe}
 * header, and that header contains a token bound to <em>one</em> address (see
 * {@link UnsubscribeTokenService}). A single SES {@code SendEmail} call produces a
 * single MIME message with a single set of headers, so three recipients on one
 * promotional send could only ever carry one recipient's token - and whichever two
 * of them pressed Unsubscribe would silently unsubscribe the third. Not a rare
 * edge: it is what happens every time a company has more than one contact.
 *
 * <p>Three alternatives were considered and each is worse.
 *
 * <p><em>Omit the header when there is more than one recipient.</em> Cheapest to
 * write, and it makes the guarantee conditional on data - the multi-recipient
 * messages, which are the ones a reader is most likely to consider bulk, would be
 * the ones with no unsubscribe button. That is the exact profile that gets a domain
 * reported.
 *
 * <p><em>Bind the token to the message instead of the address.</em> Then one
 * person's click unsubscribes everybody on the message. An unsubscribe is a
 * statement by one human about one inbox and cannot be allowed to speak for a
 * colleague.
 *
 * <p><em>Push the fan-out up into the (as yet unwritten) promotional sender.</em>
 * Puts the guarantee in the hands of whoever writes that class, and there is no
 * compiler check that they honoured it. Here it is structural: a PROMOTIONAL
 * message physically cannot leave with somebody else's token in its header.
 *
 * <p>The cost is N API calls instead of one, and it is the right cost to pay -
 * promotional mail is exactly the traffic that should be per-recipient anyway
 * (SES's own bulk API is per-destination for the same reason), while transactional
 * mail keeps its single multi-recipient send and its unchanged code path. Note the
 * partial-failure consequence: with N calls, some can succeed and some fail, so the
 * return value below means "all of them were accepted".
 *
 * <h2>Promotional mail is refused outright when unsubscribe is unconfigured</h2>
 * If {@code app.email.unsubscribe.secret} or {@code app.email.unsubscribe.api-base-url}
 * is blank, no valid unsubscribe link can be built, and a PROMOTIONAL message is
 * dropped rather than sent without one. This is the one place the email package
 * deliberately does <em>not</em> degrade gracefully, and it is the same call {@code
 * EmailEligibility} makes in its rule 6: sending marketing that nobody can opt out
 * of is not a degraded send, it is spam, and the damage lands on the sending
 * domain's reputation where every tenant pays for it. Nothing in the application
 * produces PROMOTIONAL messages yet, so today this costs exactly nothing - which is
 * precisely why it is worth fixing the behaviour now, before there is a campaign to
 * be tempted by.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSender {

    /** RFC 8058 / RFC 2369. Both are required; either alone does nothing. */
    private static final String LIST_UNSUBSCRIBE_HEADER = "List-Unsubscribe";

    private static final String LIST_UNSUBSCRIBE_POST_HEADER = "List-Unsubscribe-Post";

    /**
     * The literal RFC 8058 requires, byte for byte. It is what tells Gmail and Yahoo
     * that the URL may be POSTed without asking the reader anything, and it is the
     * reason the button appears at all - with the URL header alone, mail clients at
     * best render a link the reader has to follow and confirm, and Gmail's inbox-level
     * "Unsubscribe" affordance does not show up.
     */
    private static final String ONE_CLICK = "List-Unsubscribe=One-Click";

    private final EmailProperties emailProperties;
    private final SesV2Client sesV2Client;
    private final UnsubscribeTokenService unsubscribeTokenService;

    @PostConstruct
    void logConfigurationStatus() {
        if (!emailProperties.isConfigured()) {
            log.warn("Email is not configured (app.email.enabled must be true and app.email.from-address "
                    + "must be set to an SES-verified identity) - outgoing mail will be logged and dropped "
                    + "instead of sent.");
        }
        if (!unsubscribeTokenService.canIssueLinks()) {
            // Separate line and separate condition from the one above: a deploy can
            // be perfectly able to send order receipts while unable to send
            // marketing, and that is a normal state rather than a broken one. Says
            // what is disabled and what to set, so nobody has to read this class to
            // find out why a campaign went nowhere.
            log.warn("One-click unsubscribe is not configured (app.email.unsubscribe.secret and "
                    + "app.email.unsubscribe.api-base-url must both be set) - PROMOTIONAL email will be "
                    + "dropped rather than sent without a working List-Unsubscribe header. Transactional, "
                    + "verification and security email are unaffected.");
        }
    }

    public boolean isConfigured() {
        return emailProperties.isConfigured();
    }

    /**
     * The thread boundary. {@link EmailDispatcher} calls this rather than
     * {@link #send} so the SES round trip happens on the email executor instead of
     * on the request thread that just committed - a slow SES response would
     * otherwise be latency the buyer waits through, after their order is already
     * safely persisted and there is nothing left to tell them.
     *
     * <p>It lives on this bean, not on the dispatcher, because {@code @Async} is
     * proxy-based: a dispatcher calling its own async method would run it inline on
     * the caller's thread and silently lose the whole point.
     */
    @Async("emailExecutor")
    public void sendAsync(EmailMessage message) {
        send(message);
    }

    /**
     * @return true only when SES accepted the message - and for PROMOTIONAL mail,
     *     only when SES accepted <em>every</em> one of its per-recipient sends. See
     *     the class doc: nobody depends on this, it exists for tests and the log.
     */
    public boolean send(EmailMessage message) {
        if (message == null || !message.hasRecipients()) {
            return false;
        }
        if (!isConfigured()) {
            log.info("Email not configured - would have sent \"{}\" to {}", message.subject(), message.to());
            return false;
        }
        if (message.kind() == EmailKind.PROMOTIONAL) {
            return sendPromotional(message);
        }
        // Every other kind keeps the original path exactly: one SES call, every
        // recipient in one Destination, and no List-Unsubscribe header anywhere near
        // it. Putting one on an order receipt would invite a customer to unsubscribe
        // from the confirmation of goods they just paid for - a button that either
        // does nothing (dishonest) or breaks their account (worse). The header is
        // the sender promising "you can make this stop", and that promise must only
        // be made about mail we are actually willing to stop.
        return sendOne(message, null);
    }

    /**
     * One SES call per recipient, each carrying that recipient's own unsubscribe
     * link. See the class doc for why the fan-out lives here rather than in the
     * caller that will eventually produce campaigns.
     */
    private boolean sendPromotional(EmailMessage message) {
        if (!unsubscribeTokenService.canIssueLinks()) {
            log.warn("Dropped a PROMOTIONAL email \"{}\" addressed to {} recipient(s): one-click unsubscribe "
                    + "is not configured, and marketing must not go out without a working List-Unsubscribe "
                    + "header. See app.email.unsubscribe.* in ENVIRONMENT.md.",
                    message.subject(), message.to().size());
            return false;
        }

        boolean allAccepted = true;
        for (String recipient : message.to()) {
            String unsubscribeUrl = unsubscribeTokenService.unsubscribeUrlFor(recipient);
            if (unsubscribeUrl == null) {
                // canIssueLinks() already said this deploy can mint links, so this is
                // a failure about one address (a crypto fault, or something that
                // normalises to nothing) rather than about configuration. Skip that
                // recipient and keep going - one bad address must not cost the rest
                // of the list its copy, which is the same rule EmailDispatcher
                // applies to eligibility and EmailMessage applies to malformed
                // addresses. Skipping is safe here in a way that sending would not
                // be: the recipient loses one campaign, rather than receiving one
                // they cannot opt out of.
                log.warn("Skipping one recipient of the PROMOTIONAL email \"{}\": no unsubscribe link could "
                        + "be built for them.", message.subject());
                allAccepted = false;
                continue;
            }
            allAccepted &= sendOne(message.withRecipients(List.of(recipient)), unsubscribeUrl);
        }
        return allAccepted;
    }

    /**
     * The single SES round trip. Shared by both paths deliberately, so there is one
     * try/catch, one log line format, and no second place where an SES exception
     * could learn to escape - see the class doc on why nothing may propagate.
     */
    private boolean sendOne(EmailMessage message, String unsubscribeUrl) {
        try {
            SendEmailResponse response = sesV2Client.sendEmail(buildRequest(message, unsubscribeUrl));
            log.debug("Sent email \"{}\" to {} (SES message id {})",
                    message.subject(), message.to(), response.messageId());
            return true;
        } catch (Exception e) {
            // Warn, not error: a failed notification is not an incident, and paging
            // on it would train people to ignore the page. Bounces and complaints -
            // the failures that actually matter - are not visible here at all and
            // are what the SES configuration set exists to capture.
            log.warn("Failed to send email \"{}\" to {}: {}", message.subject(), message.to(), e.getMessage());
            return false;
        }
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
     * client. It would also have applied to <em>all</em> mail or forced two divergent
     * assembly paths, so a bug in the promotional path could surface on order
     * receipts. Using the structured field keeps the existing simple-message code
     * exactly as it was and adds two strings to it.
     *
     * <h2>Why there is no mailto: alternative</h2>
     * RFC 8058 permits a {@code mailto:} entry alongside the https one and RFC 2369
     * predates the URL form entirely, so adding one looks like free compatibility. It
     * is not free: a mailto unsubscribe is only honoured if something on our side
     * actually receives and parses that mailbox, and there is no such thing here. An
     * advertised address that silently discards unsubscribe requests is strictly worse
     * than not advertising one - the reader believes they have opted out, keeps
     * receiving mail, and reports it as spam. The https one-click URL is what Gmail
     * and Yahoo's bulk-sender requirements actually demand, and it is the one this
     * application can honour, so it is the only one offered.
     *
     * <p>Note the angle brackets. RFC 2369 defines the value as a list of URLs each
     * enclosed in {@code < >}; a bare URL is not merely untidy, several providers will
     * not parse it and will render no button at all - which looks identical to having
     * shipped no header.
     */
    private static List<MessageHeader> unsubscribeHeaders(String unsubscribeUrl) {
        return List.of(
                MessageHeader.builder()
                        .name(LIST_UNSUBSCRIBE_HEADER)
                        .value("<" + unsubscribeUrl + ">")
                        .build(),
                MessageHeader.builder()
                        .name(LIST_UNSUBSCRIBE_POST_HEADER)
                        .value(ONE_CLICK)
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
