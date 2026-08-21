package com.procurepal_services.stock_bridge_api.email.preferences;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for RFC 8058 one-click unsubscribe: the key that signs an
 * unsubscribe token, and the public origin of <em>this API</em> that the signed
 * link points at.
 *
 * <h2>Why a dedicated secret instead of reusing app.jwt.secret</h2>
 * Both are HMAC keys and the temptation to have one is real, so this needs
 * answering properly. Three reasons, and the third is the decisive one.
 *
 * <p><strong>Blast radius.</strong> {@code app.jwt.secret} signs access tokens
 * that grant the bearer a user's entire session. An unsubscribe token is mailed
 * out in a header, travels through Google's and Microsoft's infrastructure, sits
 * in an archived message forever, and is worth exactly one boolean. Giving two
 * artefacts with such different value the same key means the cheap one sets the
 * handling standard for the expensive one.
 *
 * <p><strong>Lifetime.</strong> These keys want opposite rotation policies.
 * Rotating the JWT secret is routine and nearly free - every access token dies,
 * every user signs in again, and an hour later nobody remembers. Rotating a key
 * that signs unsubscribe links is <em>not</em> free: see {@link
 * UnsubscribeTokenService} for why those links must keep working indefinitely.
 * Sharing one key forces the expensive policy onto the cheap key or, far more
 * likely, the cheap policy onto the expensive one - and the day somebody rotates
 * JWT_SECRET during an incident, every unsubscribe link ProcurePal has ever mailed
 * turns into a 400, and the people clicking them press "report spam" instead.
 *
 * <p><strong>Degradation direction.</strong> {@code app.jwt.secret} has no default
 * and the application <em>cannot start</em> without it, because an API that cannot
 * verify a token cannot serve a single authenticated request. This one is the
 * opposite: it is an optional integration in the sense {@code app.aws} and {@code
 * app.monnify} are, and a deploy that has not set it must come up and keep taking
 * orders. Two settings with opposite startup semantics cannot be the same setting.
 *
 * <h2>NOTHING HERE MAY EVER GET A DEFAULT</h2>
 * Same rule, for the same reason, as {@code app.super-admin} in application.yml -
 * read the comment there. That file applies to every profile including prod, so a
 * default secret written into it is a production secret published in this
 * repository, and a signing key everyone can read is a signing key that signs
 * nothing. The blank default in application.yml exists only so an unset
 * environment variable resolves to empty rather than failing placeholder
 * resolution at startup; blank means "unconfigured", and unconfigured is a state
 * this feature handles rather than a state it crashes on.
 *
 * <h2>Why issuing and verifying are separate questions</h2>
 * {@link #canIssueLinks()} needs both fields; {@link #canVerifyTokens()} needs only
 * the secret. That asymmetry is not tidiness, it is a real operational case: an
 * environment that once mailed promotional messages and has since had {@code
 * api-base-url} cleared must still honour the links already sitting in people's
 * inboxes. Requiring both to verify would silently start rejecting genuine
 * unsubscribes - which is precisely the failure that produces spam complaints.
 *
 * @param secret HMAC-SHA256 key for unsubscribe tokens. No default; blank disables
 *     promotional mail entirely (see {@link
 *     com.procurepal_services.stock_bridge_api.email.EmailSender}). Should be a
 *     long random string, and must be identical across every instance of a deploy -
 *     a per-instance secret means a link only works on the node that minted it.
 * @param apiBaseUrl public origin of THIS API - {@code https://api.example.com} -
 *     not the frontend. This is the one URL in the entire email package that is not
 *     a frontend route: {@code EmailProperties.appBaseUrl} is where a human clicks,
 *     whereas the address here is POSTed by Gmail's servers with no browser
 *     involved, so it has to resolve to the endpoint that does the work.
 */
@ConfigurationProperties(prefix = "app.email.unsubscribe")
public record UnsubscribeProperties(String secret, String apiBaseUrl) {

    /** True when a signed link can be built - both a key to sign with and a place to point. */
    public boolean canIssueLinks() {
        return canVerifyTokens() && notBlank(apiBaseUrl);
    }

    /** True when a presented token can be checked. See the class doc for why this is the weaker test. */
    public boolean canVerifyTokens() {
        return notBlank(secret);
    }

    /**
     * Trailing slash stripped, for the same reason {@code
     * EmailProperties.normalizedAppBaseUrl()} strips it: the path concatenated onto
     * this already begins with one, and {@code https://api.example.com//api/email/
     * unsubscribe} is a URL some mail providers normalise differently to others -
     * which would turn one-click unsubscribe into a 404 for a subset of recipients
     * that is impossible to reproduce locally.
     */
    public String normalizedApiBaseUrl() {
        if (!notBlank(apiBaseUrl)) {
            return "";
        }
        String trimmed = apiBaseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
