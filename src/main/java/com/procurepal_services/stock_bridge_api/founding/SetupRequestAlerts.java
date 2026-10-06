package com.procurepal_services.stock_bridge_api.founding;

import com.procurepal_services.stock_bridge_api.email.EmailNotificationService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the team a shop has booked a setup, the moment it happens (LANDING_PAGE_PLAN.md, rule 5:
 * a WhatsApp reply within 5 minutes in staffed hours).
 *
 * <p>Two channels. An email to the support inbox, always. And, when
 * {@code app.founding.alert-webhook-url} is set, a chat message (Slack, Google Chat, Mattermost),
 * because a phone notification is what actually gets a reply inside 5 minutes. Both go after the
 * request commits, so nobody is told about a request that rolled back, and neither can fail the
 * shop's request: an alert that doesn't arrive is logged, and the queue in the admin still has it.
 */
@Slf4j
@Component
public class SetupRequestAlerts {

    private final EmailNotificationService emails;
    private final FoundingOfferProperties properties;
    private final Executor executor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public SetupRequestAlerts(
            EmailNotificationService emails,
            FoundingOfferProperties properties,
            @Qualifier("emailExecutor") Executor executor) {
        this.emails = emails;
        this.properties = properties;
        this.executor = executor;
    }

    public void newRequest(
            String businessName, String whatsapp, String source, boolean founding,
            LocalDate setupWeekStarts, OffsetDateTime requestedAt) {
        String replyUrl = replyUrl(whatsapp, businessName);
        emails.setupRequested(businessName, whatsapp, source, founding, setupWeekStarts, requestedAt, replyUrl);

        chat("New setup request: *" + businessName + "* (" + whatsapp + "), "
                + (founding ? "founding" : "regular plan") + ", from " + source + ". Reply on WhatsApp now: " + replyUrl);
    }

    /** A shop sent its product list from the app (step 5): load it within 24 hours. */
    public void listReceived(String businessName, String companyId, String whatsapp, int files) {
        emails.productListReceived(businessName, companyId, whatsapp, files);
        chat("Product list received: *" + businessName + "* (" + companyId + ") sent " + files
                + (files == 1 ? " file" : " files") + ". Load it within 24 hours from the setup queue.");
    }

    private void chat(String text) {
        String webhook = properties.alertWebhookUrl();
        if (webhook == null) {
            return;
        }
        Runnable post = () -> post(webhook, text);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(post);
                }
            });
        } else {
            executor.execute(post);
        }
    }

    /**
     * A wa.me link that opens a chat with the shop, the first message already typed. The admin
     * queue builds the same message (stock-bridge-ui, setupRequestReplies.ts); keep the two alike.
     */
    static String replyUrl(String whatsapp, String businessName) {
        String message = "Hello " + businessName + ", this is Procurepaddy. Thank you for booking your setup. "
                + "To load your products, send your product list here: an Excel file, a CSV, or clear photos of your "
                + "stock book. We will have it in within 24 hours.";
        return "https://wa.me/" + whatsapp.replace("+", "") + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private void post(String url, String text) {
        try {
            String body = "{\"text\":\"" + jsonEscape(text) + "\"}";
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                log.warn("The setup-request alert webhook answered {}", response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not post the setup-request alert: {}", e.getMessage());
        }
    }
}
