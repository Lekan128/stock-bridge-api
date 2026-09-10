package com.procurepal_services.stock_bridge_api.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.procurepal_services.stock_bridge_api.repository.VendorWaitlistApplicationRepository;
import com.procurepal_services.stock_bridge_api.vendor.waitlist.VendorWaitlistProperties;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The one bound on an unauthenticated endpoint that makes this server insert a row
 * and send mail to a caller-chosen address.
 *
 * <h2>Why every request here uses the JDK HTTP client and not TestRestTemplate</h2>
 * This is a genuine trap rather than a preference, and it is the reason an earlier
 * version of this file ran for seventy minutes and then failed asserting 429 but
 * getting 202.
 *
 * <p>{@code TestRestTemplate} is backed by Apache HttpClient 5, whose default retry
 * strategy treats 429 as retryable and <em>sleeps for the duration in the
 * {@code Retry-After} header</em> before trying again. The header this endpoint
 * sends is honest - roughly the configured window, so about an hour with the
 * default - so the client slept for that hour, retried, found the rolling window
 * had moved on, and got a perfectly correct 202. Two symptoms, one cause: the run
 * time was the sleep, and the wrong status was the retry succeeding.
 *
 * <p>{@code EmailVerificationIntegrationTest} hit exactly this and documents it on
 * {@code resendWithoutClientRetries}; {@code VendorWaitlistExceptionHandler}'s
 * Javadoc names it as the first thing to check "if an integration ever hangs on
 * apply". The JDK client performs no automatic retries and reports the response as
 * given, so the whole class uses it - not only the assertion that expects a 429,
 * because a client that silently retries any of these would make the budget
 * arithmetic mean something different from what it reads.
 *
 * <h2>Why this is a class of its own, and what that costs</h2>
 * {@link com.procurepal_services.stock_bridge_api.vendor.waitlist.VendorWaitlistRateLimiter}
 * keys partly on the caller's remote address, which from a test is the same address
 * for every request in the JVM, and it is in-process state on a singleton bean. A
 * test that deliberately exhausts that budget would leave every later submission in
 * the same application context refused. The {@code @TestPropertySource} below buys a
 * separate context and therefore a separate limiter, so the exhaustion stops at this
 * file's edge. The cost is one extra Spring context per suite run, which is seconds.
 *
 * <p>The window is an hour and the limiter reads {@code Instant.now()} directly, so
 * a test cannot wait for a slot to free up. This asserts what is assertable in one
 * pass: the limit binds, the refusal is a 429 carrying a usable {@code Retry-After},
 * and - the part that actually matters - a refused submission wrote nothing. The
 * rolling-window arithmetic itself is covered by {@link VendorWaitlistRateLimiterTest}
 * with no server involved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestPropertySource(properties = {"app.vendor-waitlist.submit-limit=2", "app.vendor-waitlist.submit-window=1h"})
class VendorWaitlistRateLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private VendorWaitlistApplicationRepository applicationRepository;

    @Autowired
    private VendorWaitlistProperties properties;

    /**
     * Asserted first and on its own, because without it the interesting test below
     * fails in a way that looks like a broken rate limiter.
     *
     * <p>{@link VendorWaitlistProperties}' compact constructor substitutes a default
     * for any absent, zero or negative value - which is right in production (a
     * fat-fingered "0" must not close the public form) and treacherous in a test: a
     * property that failed to bind produces "the limit is 3", not an error. The
     * previous version of this file had no such check, so a binding failure and a
     * client-side retry were indistinguishable from each other at the assertion.
     */
    @Test
    void theTestPropertiesActuallyBind() {
        assertThat(properties.submitLimit())
                .as("a silently-defaulted 3 would make the budget arithmetic below wrong rather than failing")
                .isEqualTo(2);
        assertThat(properties.submitWindow()).isEqualTo(Duration.ofHours(1));
    }

    /**
     * Two through, the third refused - even though it is a different business with a
     * different address, because the source is the same and the source is the abuse
     * vector this limit exists for.
     *
     * <p>The row count assertion is the important one. A 429 that still inserted the
     * application and still sent the mail would be a rate limit in name only, and the
     * status code alone cannot tell the two apart.
     */
    @Test
    void aThirdSubmissionFromTheSameSourceIsRefusedWithARetryAfterAndWritesNothing() {
        String third = "throttled-" + UUID.randomUUID() + "@example.com";

        assertThat(submit("first-" + UUID.randomUUID() + "@example.com", "First Applicant Ltd").statusCode())
                .isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(submit("second-" + UUID.randomUUID() + "@example.com", "Second Applicant Ltd").statusCode())
                .isEqualTo(HttpStatus.ACCEPTED.value());

        HttpResponse<String> refused = submit(third, "Third Applicant Ltd");

        assertThat(refused.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(refused.body())
                .as("one message for both the IP budget and the email budget - saying which ran out "
                        + "would answer \"has this address applied recently\" for any address a stranger types")
                .contains("Too many applications");

        String retryAfter = refused.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse(null);
        assertThat(retryAfter)
                .as("RFC 9110 Retry-After, in seconds - the frontend disables its button for exactly "
                        + "this long rather than guessing")
                .isNotNull();
        assertThat(Long.parseLong(retryAfter))
                .as("rounded up with a floor of one, because Retry-After: 0 reads as retry immediately")
                .isGreaterThanOrEqualTo(1);

        assertThat(applicationRepository.findAllByEmailIgnoreCaseOrderByCreatedAtDesc(third))
                .as("a refused submission is refused BEFORE the insert and before either email")
                .isEmpty();
    }

    /**
     * The form is posted as JSON by hand rather than through a serialising client,
     * because switching to the JDK's HTTP client is the whole point of this file and
     * a body builder would be the one place a convenience wrapper crept back in.
     * Only the three required fields are sent; the optional ones are covered in
     * VendorOnboardingIntegrationTest.
     */
    private HttpResponse<String> submit(String email, String businessName) {
        String body = """
                {"businessName":"%s","email":"%s","contactPhone":"0803 111 2222"}"""
                .formatted(businessName, email);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/vendor-waitlist"))
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not call the vendor waitlist endpoint", e);
        }
    }
}
