package com.procurepal_services.stock_bridge_api.email.webhook;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fetches SNS signing certificates over HTTPS and caches the public keys.
 *
 * <h2>The fetch is the trust anchor, so it is deliberately narrow</h2>
 * Three properties together are what make it safe to fetch a URL that arrived in an
 * untrusted request body:
 * <ul>
 *   <li>{@link SnsEndpointGuard} has allow-listed the host and the certificate path
 *       first. Nothing is opened before that returns.</li>
 *   <li><strong>Redirects are never followed.</strong> This is not a default worth
 *       relying on implicitly, so it is set explicitly. A redirect is the one way a
 *       host that passed the allow-list can hand the request to one that did not -
 *       validating {@code sns.eu-west-1.amazonaws.com} buys nothing if AWS's
 *       response can bounce us to {@code 169.254.169.254}. With redirects off, the
 *       only host ever contacted is the one that was checked.</li>
 *   <li>It is HTTPS to a pinned hostname, so TLS certificate validation is doing the
 *       real work of proving the bytes came from AWS. That is why the X.509
 *       certificate itself is not chain-validated against a bundled Amazon root
 *       below: the transport already authenticated the origin, and a second,
 *       hand-rolled trust store would be one more thing to expire unnoticed.</li>
 * </ul>
 *
 * <h2>Why the cache is not optional</h2>
 * Without it, every notification costs an outbound TLS handshake to AWS on the
 * request thread, and SNS gives us 15 seconds before it gives up and retries -
 * which produces more notifications. Worse, the fetch happens <em>before</em>
 * signature verification, so an attacker POSTing rubbish in a loop would turn this
 * endpoint into an outbound request amplifier. Caching by URL means the real AWS
 * certificate is fetched roughly once per process lifetime.
 *
 * <p>Only successes are cached. A failed fetch must stay retryable - AWS having a
 * bad thirty seconds must not permanently disable bounce handling for the life of
 * the process - and caching failures would also let one badly-timed request poison
 * the entry that every later message depends on.
 *
 * <h2>Why the cache is capped</h2>
 * The cache key is attacker-influenced: within the allow-list, the path still varies
 * (that is what certificate rotation looks like), so a determined caller can ask for
 * many distinct URLs. Only ones that genuinely return a parseable certificate from
 * AWS get stored, which already bounds it tightly, but an unbounded map keyed by
 * anything a stranger can influence is a memory leak waiting to be found. AWS keeps
 * a small handful of certificates live at a time; 32 is far more than that and still
 * nothing. On overflow the map is cleared rather than evicted cleverly - the cost of
 * being wrong is one re-fetch, which does not justify an LRU.
 */
@Component
@Slf4j
public class HttpSnsCertificateLoader implements SnsCertificateLoader {

    private static final int MAX_CACHED_CERTIFICATES = 32;

    /**
     * A certificate is a few kilobytes; anything larger is not one, and reading it
     * would be reading whatever an unexpected response happens to contain.
     */
    private static final int MAX_CERTIFICATE_BYTES = 16 * 1024;

    private final SnsEndpointGuard endpointGuard;
    private final HttpClient httpClient;
    private final Duration readTimeout;
    private final Map<String, PublicKey> cache = new ConcurrentHashMap<>();

    public HttpSnsCertificateLoader(SnsEndpointGuard endpointGuard, SesWebhookProperties properties) {
        this.endpointGuard = endpointGuard;
        this.httpClient = HttpClient.newBuilder()
                // Explicit, load-bearing, and the first thing to check if this is
                // ever refactored onto another client. See the class doc.
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
        this.readTimeout = properties.readTimeout();
    }

    @Override
    public PublicKey publicKeyFor(String signingCertUrl) {
        // Validate BEFORE anything else, including before touching the cache: a
        // cache lookup on an unvalidated key would let a refused URL become a hit
        // if it ever collided with a stored one, and more importantly it puts the
        // guard on every path rather than on most of them.
        URI uri = endpointGuard.requireSigningCertificateUrl(signingCertUrl);
        String key = uri.toString();

        PublicKey cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        PublicKey publicKey = fetch(uri);
        if (cache.size() >= MAX_CACHED_CERTIFICATES) {
            log.warn("SNS signing certificate cache exceeded {} entries and was cleared. Either AWS is "
                    + "rotating certificates unusually often, or something is probing this endpoint.",
                    MAX_CACHED_CERTIFICATES);
            cache.clear();
        }
        cache.put(key, publicKey);
        log.info("Fetched and cached an SNS signing certificate from {}", key);
        return publicKey;
    }

    private PublicKey fetch(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(readTimeout)
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("SNS returned HTTP " + response.statusCode()
                        + " for its signing certificate");
            }
            byte[] body = response.body();
            if (body == null || body.length == 0 || body.length > MAX_CERTIFICATE_BYTES) {
                throw new IllegalStateException("SNS signing certificate response was empty or implausibly large");
            }
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(body));
            // Cheap, and catches the one failure TLS does not: a certificate that is
            // genuinely AWS's and genuinely expired. Without it an expired
            // certificate would verify signatures happily for as long as the key
            // stayed cached.
            certificate.checkValidity();
            return certificate.getPublicKey();
        } catch (SnsEndpointRefusedException e) {
            throw e;
        } catch (Exception e) {
            // Wrapped rather than propagated raw so the verifier has one exception
            // type to treat as "could not verify", which it must resolve to
            // "invalid" rather than to a 500. See SnsSignatureVerifier.
            throw new IllegalStateException(
                    "Could not load the SNS signing certificate from " + uri + ": " + e.getMessage(), e);
        }
    }
}
