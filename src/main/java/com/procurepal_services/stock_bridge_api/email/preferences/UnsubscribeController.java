package com.procurepal_services.stock_bridge_api.email.preferences;

import com.procurepal_services.stock_bridge_api.email.preferences.dto.UnsubscribeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The RFC 8058 one-click unsubscribe endpoint. Its caller is not a person and not
 * a browser: it is Gmail's or Yahoo's infrastructure, POSTing the {@code
 * List-Unsubscribe} URL on the reader's behalf the moment they press the
 * "Unsubscribe" link the provider renders beside the sender name.
 *
 * <h2>What that means for how this is written</h2>
 * There is no session, no cookie, no bearer token and no opportunity to ask the
 * caller anything. The provider sends one POST with the body {@code
 * List-Unsubscribe=One-Click} and reads the status code; it will not follow a
 * redirect to a login page, will not render a confirmation screen, and treats a
 * non-2xx as "this sender's unsubscribe is broken". So the endpoint must do the
 * work and answer 200 on the very first request, with the token in the URL as its
 * only credential. It is registered permit-all in {@code SecurityConfig} for
 * exactly this reason.
 *
 * <p>CSRF needs no exemption here and must not be given one: it is disabled
 * application-wide in {@code SecurityConfig}, which is sound for a stateless
 * bearer-token API that keeps no cookies - there is no ambient credential for a
 * cross-site POST to borrow. The same property is what already lets the Monnify
 * webhook work.
 *
 * <h2>Why there is no GET, and why that is a security decision</h2>
 * A GET on this path returns 405, deliberately. Unsubscribing is a state change,
 * and a state change behind a GET is fetched by things that are not the user:
 * Gmail's image proxy, corporate link scanners, antivirus URL rewriters,
 * Outlook's Safe Links, and the browser's own prefetcher all follow links in mail
 * without anybody clicking. A GET here would silently unsubscribe a fraction of
 * every campaign's recipients and there would be no way to tell those apart from
 * genuine clicks. RFC 8058 requires the {@code List-Unsubscribe-Post} header
 * precisely so providers use POST and this class of accident stops happening.
 *
 * <p>The human-facing route is therefore a frontend page, not this endpoint: a
 * promotional body that wants a visible "unsubscribe" link points it at {@code
 * EmailProperties.appBaseUrl} carrying the same token, and that page POSTs here
 * after the person confirms. Nothing generates promotional mail yet, so no such
 * page exists yet either; when one is built it needs no change on this side.
 *
 * <h2>One response, always</h2>
 * A valid token always produces 200 and the same body, whether it silenced fifty
 * user rows, one, or none at all. That is the anti-enumeration requirement and it
 * is the reason nothing about the outcome reaches the response - see {@link
 * UnsubscribeService}. Only a token that fails to verify is answered differently
 * (400, via {@link EmailPreferenceExceptionHandler}), and that reveals nothing
 * about any address: it is a statement about the token, which the caller supplied.
 */
@RestController
@RequiredArgsConstructor
public class UnsubscribeController {

    private final UnsubscribeService unsubscribeService;

    /**
     * <pre>
     * POST /api/email/unsubscribe?token=...
     * Content-Type: application/x-www-form-urlencoded
     *
     * List-Unsubscribe=One-Click
     * </pre>
     *
     * <p>The body is read and ignored. RFC 8058 specifies it, so providers send it,
     * but it carries no information - it is a constant whose only job is to prove
     * the POST came from a provider implementing the spec rather than from a
     * prefetch. Nothing is bound to it and no {@code consumes} is declared, because
     * providers vary: some send no body, some send it as {@code text/plain}, and
     * refusing those would fail the unsubscribe for whole mail platforms.
     *
     * @param token from the query string. Optional at the binding level so that a
     *     missing one produces the same clean 400 as a forged one, rather than
     *     Spring's own 400 with a different body - {@link UnsubscribeService}
     *     rejects null.
     * @return 200 with a fixed confirmation message. 400 only when the token does
     *     not verify.
     */
    @PostMapping("/api/email/unsubscribe")
    public UnsubscribeResponse unsubscribe(@RequestParam(name = "token", required = false) String token) {
        unsubscribeService.unsubscribe(token);
        return UnsubscribeResponse.confirmed();
    }
}
