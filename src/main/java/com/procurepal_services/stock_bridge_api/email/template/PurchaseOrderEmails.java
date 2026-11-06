package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.escape;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.expected.PurchaseOrderSnapshot;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The email a buyer sends a supplier with a purchase order (PURCHASE_ORDER_PLAN.md phase 3).
 *
 * <p>The supplier has no account here, so the message is the buyer's: it names the buyer in the
 * header and the From line, replies go to the buyer, and the footer says plainly why it arrived
 * rather than claiming the supplier has an account. It says what is wanted and asks for a price;
 * it carries no prices of its own. The PDF is attached, and the order is repeated in the body so
 * it can be read in a preview without opening anything.
 */
public final class PurchaseOrderEmails {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private PurchaseOrderEmails() {
    }

    /**
     * @param pdf the order as a PDF, attached as {@code <PO number> <supplier>.pdf}
     * @param buyerEmail where the supplier's reply should go; may be null
     */
    public static EmailMessage send(PurchaseOrderSnapshot order, String to, byte[] pdf, String buyerEmail) {
        String buyer = order.buyer().name();
        String number = order.poNumber();
        boolean revised = order.revision() > 1;
        String subject = (revised ? "Updated purchase order " : "Purchase order ") + number + " from " + buyer;

        List<Detail> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        text.append(buyer).append(revised ? " has changed purchase order " : " would like to order, on purchase order ")
                .append(number).append(":\n\n");
        for (PurchaseOrderSnapshot.Line line : order.lines()) {
            String quantity = quantity(line);
            rows.add(new Detail(line.productName(), quantity));
            text.append("  ").append(line.productName()).append(" - ").append(quantity).append('\n');
        }
        text.append('\n');
        if (order.deliverTo() != null) {
            String where = joinNonBlank(order.deliverTo().name(), order.deliverTo().address());
            rows.add(new Detail("Deliver to", where));
            text.append("Deliver to: ").append(where).append('\n');
        }
        rows.add(new Detail("Needed by", order.neededBy() == null ? "As soon as you can" : order.neededBy().format(DATE)));
        text.append("Needed by: ")
                .append(order.neededBy() == null ? "As soon as you can" : order.neededBy().format(DATE)).append('\n');
        if (order.note() != null && !order.note().isBlank()) {
            rows.add(new Detail("Note", order.note()));
            text.append("Note: ").append(order.note()).append('\n');
        }
        text.append("\nThe full order is attached as a PDF. Please reply with your price and when we can expect delivery.\n");
        if (order.buyer().phone() != null) {
            text.append("Questions: ").append(order.buyer().phone()).append('\n');
        }
        text.append("\nSent through Procure Paddy on behalf of ").append(buyer).append(".\n");

        String body = paragraph(buyer + (revised
                        ? " has changed this order. This replaces the earlier version."
                        : " would like to order the following."))
                + detailTable(rows)
                + paragraph("The full order is attached as a PDF. Please reply with your price and when we can "
                        + "expect delivery.")
                + (order.buyer().phone() == null ? "" : paragraph("Questions? Call " + order.buyer().phone() + "."));

        String footer = "Sent through Procure Paddy on behalf of " + escape(buyer) + ", who typed in your email "
                + "address. Replying to this email goes to them.";
        String html = page(buyer, (revised ? "Updated purchase order " : "Purchase order ") + number,
                buyer + " would like to order from you.", body, footer);

        return new EmailMessage(
                List.of(to), subject, html, text.toString(), EmailKind.SUPPLIER_DOCUMENT,
                List.of(new EmailMessage.Attachment(number + " " + order.supplier().name() + ".pdf", "application/pdf", pdf)),
                buyerEmail, buyer + " via Procure Paddy");
    }

    private static String quantity(PurchaseOrderSnapshot.Line line) {
        String amount = line.quantity().stripTrailingZeros().toPlainString();
        return amount + " × " + line.comesIn();
    }

    private static String joinNonBlank(String... parts) {
        return EmailLayout.joinNonBlank(", ", parts);
    }
}
