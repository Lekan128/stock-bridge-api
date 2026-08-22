package com.procurepal_services.stock_bridge_api.email.webhook;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Decides whether a URL that arrived <em>inside a request body</em> may be fetched.
 *
 * <h2>Why this class exists: the webhook hands us URLs and asks us to GET them</h2>
 * The SNS protocol has two places where a message tells this server to make an
 * outbound request:
 * <ul>
 *   <li>{@code SigningCertURL} - fetched to obtain the public key that verifies the
 *       message's own signature, and therefore fetched <em>before</em> anything
 *       about the message is trusted. There is no ordering that avoids this: the
 *       fetch is how trust is established, so it necessarily happens while the input
 *       is still hostile.</li>
 *   <li>{@code SubscribeURL} - fetched to confirm a subscription.</li>
 * </ul>
 *
 * <p>An endpoint that GETs an attacker-supplied URL from inside a production network
 * is server-side request forgery, and it is worth being concrete about what that
 * buys an attacker here rather than treating it as an abstract lint. This process
 * runs with an IAM role and sits inside a private network. {@code
 * http://169.254.169.254/latest/meta-data/iam/security-credentials/} returns that
 * role's temporary credentials to anyone who can make the instance fetch it.
 * {@code http://localhost:5432}, the Postgres port, and every internal service that
 * trusts callers by network position are all reachable the same way. The response
 * body does not even have to come back to the attacker for this to be damaging -
 * a difference in timing or in the error logged is enough to port-scan a VPC one
 * request at a time. So this is a hard, allow-list check with no bypass flag, and
 * it runs before the URL is parsed for any other purpose.
 *
 * <h2>Allow-list, never a deny-list</h2>
 * The tempting version of this check is "reject 169.254.169.254, reject localhost,
 * reject RFC 1918". That approach loses, reliably and famously: {@code
 * http://[::ffff:169.254.169.254]}, {@code http://2852039166/} (the metadata
 * address as a decimal integer), {@code http://169.254.169.254.nip.io}, a DNS name
 * the attacker controls that resolves to a private address, and a redirect from a
 * public host to a private one all defeat it. Each is a separate patch and the list
 * is never finished.
 *
 * <p>The allow-list has none of that surface because it does not reason about where
 * a host resolves to at all. There is exactly one shape of URL AWS will ever put in
 * these fields, this class states that shape, and everything else is refused
 * without being inspected. Note the consequence for redirects too: the HTTP clients
 * that consume this guard are configured to follow none, because a redirect is the
 * one way a validated host can hand off to an unvalidated one.
 *
 * <h2>What is checked, and why each one</h2>
 * <ol>
 *   <li><strong>Scheme is exactly https.</strong> Not merely for confidentiality:
 *       {@code file:}, {@code gopher:}, {@code jar:} and {@code ftp:} are all
 *       things a URL library will happily open, and {@code file:///etc/passwd} needs
 *       no network at all.</li>
 *   <li><strong>No user-info component.</strong> {@code
 *       https://sns.us-east-1.amazonaws.com@evil.example/} has host {@code
 *       evil.example}, and reads to a human as the opposite. Java parses it
 *       correctly, but a later refactor onto a different URL type might not, and
 *       there is no legitimate reason for credentials in an AWS certificate URL.</li>
 *   <li><strong>No explicit port other than 443.</strong> Same reasoning - AWS never
 *       sends one, so a present port is a signal rather than a preference.</li>
 *   <li><strong>Host matches {@code sns.<region>.amazonaws.com} exactly.</strong>
 *       Anchored at both ends, which is what refuses {@code
 *       sns.us-east-1.amazonaws.com.evil.example} - the single most common way this
 *       check is written wrong.</li>
 *   <li><strong>If {@code app.email.sns.region} is set, the region must be that
 *       one.</strong> Narrows the allow-list from "any AWS region" to one host.</li>
 * </ol>
 *
 * <p>Deliberately NOT accepting {@code amazonaws.com.cn} (the China partition) or
 * {@code amazonaws-us-gov.com}. Adding them is a one-line change for a deployment
 * that needs one; guessing at partitions nobody here uses would widen the
 * allow-list for no benefit, which is the opposite of what an allow-list is for.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SnsEndpointGuard {

    /**
     * {@code sns.<region>.amazonaws.com}, anchored. The region character class is
     * deliberately narrow - AWS region codes are lowercase letters, digits and
     * hyphens - so that a host containing an escape, a dot or an encoded character
     * cannot slip through the middle segment.
     */
    private static final Pattern AWS_SNS_HOST = Pattern.compile("^sns\\.[a-z0-9-]{1,40}\\.amazonaws\\.com$");

    /**
     * AWS publishes signing certificates at a stable, boring path:
     * {@code /SimpleNotificationService-<hex>.pem}. Requiring it does two things.
     * It stops the certificate cache being an attacker-growable map - without it,
     * an unlimited number of distinct paths on the legitimate host are all valid
     * cache keys. And it means a message pointing at some other object on the SNS
     * host, whatever that might one day be, is refused rather than parsed as a
     * certificate.
     *
     * <p>The risk accepted is that AWS changes the filename convention and bounce
     * processing stops. That fails in the safe direction: signature verification
     * refuses everything, mail keeps flowing normally, and the {@code
     * ses_notification_events} table fills with refusals that say exactly why.
     */
    private static final Pattern AWS_SIGNING_CERT_PATH =
            Pattern.compile("^/SimpleNotificationService-[A-Za-z0-9_-]{1,120}\\.pem$");

    private final SesWebhookProperties properties;

    /**
     * The {@code SigningCertURL} check: an AWS SNS host, plus the documented
     * certificate path.
     *
     * @param url the raw value from the message body, entirely untrusted
     * @return the parsed URI, safe to fetch with a non-redirecting client
     * @throws SnsEndpointRefusedException on anything at all that does not match
     */
    public URI requireSigningCertificateUrl(String url) {
        URI uri = requireAwsSnsUrl(url, "SigningCertURL");
        String path = uri.getPath();
        if (path == null || !AWS_SIGNING_CERT_PATH.matcher(path).matches()) {
            throw refuse("SigningCertURL", url,
                    "the path is not an SNS signing certificate path (/SimpleNotificationService-*.pem)");
        }
        return uri;
    }

    /**
     * The {@code SubscribeURL} check: an AWS SNS host. The path is deliberately not
     * constrained - a subscribe URL is a query string over the topic root and its
     * shape is not documented as stable, so pinning it would be guessing. The host
     * check is the control that matters, and the request is only ever made after the
     * message carrying this URL has passed signature verification.
     *
     * @throws SnsEndpointRefusedException if the host is not an AWS SNS host
     */
    public URI requireSubscribeUrl(String url) {
        return requireAwsSnsUrl(url, "SubscribeURL");
    }

    private URI requireAwsSnsUrl(String url, String field) {
        if (url == null || url.isBlank()) {
            throw refuse(field, url, "it is missing");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            // The message is the parse failure, not the URL's own text, so a
            // deliberately malformed value cannot inject line noise into the log.
            throw refuse(field, url, "it is not a well-formed URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw refuse(field, url, "the scheme is not https");
        }
        if (uri.getUserInfo() != null) {
            throw refuse(field, url, "it carries a user-info component, which AWS never sends");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw refuse(field, url, "it names an explicit port, which AWS never sends");
        }

        String host = uri.getHost();
        if (host == null) {
            // Happens for authorities Java will not parse as a host at all - an
            // underscore, a bare IPv6 literal typo, an empty authority. Refusing is
            // the only safe reading: "no host" must never fall through to "any host".
            throw refuse(field, url, "it has no parseable host");
        }
        host = host.toLowerCase(Locale.ROOT);

        if (!AWS_SNS_HOST.matcher(host).matches()) {
            throw refuse(field, url, "the host '" + host + "' is not an AWS SNS host");
        }
        if (properties.hasPinnedRegion()) {
            String expected = "sns." + properties.region().trim().toLowerCase(Locale.ROOT) + ".amazonaws.com";
            if (!expected.equals(host)) {
                throw refuse(field, url,
                        "the host '" + host + "' is not the pinned region's SNS host ('" + expected + "')");
            }
        }
        return uri;
    }

    /**
     * One place that builds the refusal, so every path logs at the same level with
     * the same shape. WARN rather than ERROR: a refusal is this class working, and a
     * genuine SSRF attempt is not an outage. It is loud enough to alert on and quiet
     * enough that a probe in a loop cannot page anybody.
     */
    private SnsEndpointRefusedException refuse(String field, String url, String why) {
        log.warn("Refusing to fetch an SNS {} because {}. This is an SSRF guard and it has no bypass. "
                + "Value was: {}", field, why, url);
        return new SnsEndpointRefusedException("Refused to fetch " + field + ": " + why);
    }
}
