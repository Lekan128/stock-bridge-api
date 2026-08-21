package com.procurepal_services.stock_bridge_api.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Same shape and same discipline as {@link
 * com.procurepal_services.stock_bridge_api.storage.AwsProperties}: every field is
 * optional at the binding level (application.yml defaults the unset ones to blank
 * rather than failing placeholder resolution), and {@link #isConfigured()} is the
 * single source of truth for whether email can actually be sent. See
 * {@link EmailSender} for how that gets enforced.
 *
 * <h2>Why credentials are not here</h2>
 * SES borrows the credential chain {@code app.aws} already configures - the same
 * IAM user, or the same assumed role, that S3 uses. Duplicating access keys under
 * a second prefix would mean two secrets to rotate for one AWS account. The only
 * thing SES needs of its own is a region, because the bucket and the verified
 * sending identity do not have to live in the same one. See {@link SesClientConfig}.
 */
@ConfigurationProperties(prefix = "app.email")
public record EmailProperties(
        boolean enabled,
        String region,
        String fromAddress,
        String fromName,
        String replyToAddress,
        String configurationSet,
        String appBaseUrl,
        String operatorAddress) {

    /**
     * Both conditions matter, and they fail for different reasons.
     *
     * <p>{@code enabled} is the deliberate off switch: a deploy that has perfectly
     * good SES credentials but must not send mail (a restored production database
     * running in staging, say, whose orders would otherwise email real customers
     * about orders that are being replayed) sets this false and everything else
     * keeps working.
     *
     * <p>{@code fromAddress} is the accidental one. SES will not accept a send from
     * an unverified identity, so a blank From is not a send that fails at AWS - it
     * is a send that cannot be attempted. Treating it as "email unavailable"
     * instead of an error is what lets the whole application run locally with no
     * AWS account at all.
     *
     * <p>configurationSet, replyToAddress, fromName and operatorAddress are
     * deliberately excluded: each is a refinement of a message that would send
     * correctly without it.
     */
    public boolean isConfigured() {
        return enabled && notBlank(fromAddress);
    }

    /**
     * What SES puts in the From header. A display name is optional, and when it is
     * absent the bare address is itself a valid From - so this never fails, it just
     * produces a less friendly inbox line.
     *
     * <p>The name is quoted because it routinely contains a character RFC 5322 does
     * not allow bare in a display name; "ProcurePal, Ltd" would otherwise parse as
     * two addresses.
     */
    public String formattedFrom() {
        if (!notBlank(fromName)) {
            return fromAddress;
        }
        return "\"" + fromName.replace("\"", "'") + "\" <" + fromAddress + ">";
    }

    /**
     * Base URL the links in an email point at - the FRONTEND, not this API, since
     * every link in every template is an in-app route the React router serves.
     *
     * <p>Trailing slashes are stripped here rather than at each call site, because
     * every template concatenates a path that already starts with one and
     * {@code https://app.example.com//app/orders/...} is a link some mail clients
     * will not follow.
     */
    public String normalizedAppBaseUrl() {
        if (!notBlank(appBaseUrl)) {
            return "";
        }
        String trimmed = appBaseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
