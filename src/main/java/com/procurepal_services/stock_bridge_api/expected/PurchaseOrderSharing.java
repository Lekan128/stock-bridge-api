package com.procurepal_services.stock_bridge_api.expected;

import com.procurepal_services.stock_bridge_api.email.EmailProperties;
import com.procurepal_services.stock_bridge_api.entity.ExpectedDelivery;
import com.procurepal_services.stock_bridge_api.expected.dto.ExpectedDeliveryResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What a buyer needs to send a purchase order themselves (PURCHASE_ORDER_PLAN.md phase 5): the
 * link, the message that carries it, and the WhatsApp address that opens with the message typed.
 *
 * <p>The message is written once, here, in the buyer's voice - so "Send on WhatsApp" and "Copy
 * message" can never say different things, and a screen never assembles it.
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderSharing {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EmailProperties emailProperties;

    /** 192 bits from a secure source, URL-safe: long enough that guessing one is not a thing. */
    static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The public page for a token, or null when the app's address is not configured. */
    public String publicUrl(String token) {
        String base = emailProperties.normalizedAppBaseUrl();
        return base.isEmpty() || token == null ? null : base + "/o/" + token;
    }

    public ExpectedDeliveryResponse.Share build(ExpectedDelivery expected, PurchaseOrderSnapshot order, String livePhone) {
        if (expected.getShareToken() == null) {
            return null;
        }
        String url = publicUrl(expected.getShareToken());
        String message = message(order, url);
        String phone = WhatsAppNumbers.normalize(livePhone != null ? livePhone : order.supplier().phone());
        String whatsappUrl = phone == null
                ? null
                : "https://wa.me/" + phone + "?text=" + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
        return new ExpectedDeliveryResponse.Share(
                expected.getShareToken(), url, message, whatsappUrl,
                expected.getWhatsappOpenedAt(), expected.getMessageCopiedAt());
    }

    static String message(PurchaseOrderSnapshot order, String url) {
        StringBuilder text = new StringBuilder();
        text.append("Hello ").append(order.supplier().name()).append(", this is purchase order ")
                .append(order.poNumber()).append(" from ").append(order.buyer().name()).append(".\n\n");
        text.append(order.revision() > 1 ? "We have changed it. We would now like:\n" : "We would like:\n");
        for (PurchaseOrderSnapshot.Line line : order.lines()) {
            text.append("• ").append(line.productName()).append(": ")
                    .append(line.quantity().stripTrailingZeros().toPlainString()).append(" × ").append(line.comesIn())
                    .append('\n');
        }
        text.append('\n');
        StringBuilder when = new StringBuilder();
        if (order.neededBy() != null) {
            when.append("Needed by ").append(order.neededBy().format(DATE)).append(". ");
        }
        if (order.deliverTo() != null && order.deliverTo().name() != null) {
            when.append("Deliver to ").append(order.deliverTo().name()).append(". ");
        }
        if (!when.isEmpty()) {
            text.append(when.toString().trim()).append('\n');
        }
        text.append("Please reply with your price.");
        if (order.note() != null && !order.note().isBlank()) {
            text.append("\n\nNote: ").append(order.note().trim());
        }
        if (url != null) {
            text.append("\n\nFull order: ").append(url);
        }
        return text.toString();
    }
}
