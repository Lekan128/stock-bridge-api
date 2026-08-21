package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.LineItem;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.bold;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.callout;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.escape;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.humanise;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.joinNonBlank;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.lineItems;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.money;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraphHtml;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.total;

import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import com.procurepal_services.stock_bridge_api.entity.Order;
import com.procurepal_services.stock_bridge_api.entity.OrderItem;
import com.procurepal_services.stock_bridge_api.entity.OrderStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * The marketplace emails: everything that happens to an order between a buyer
 * paying for it and receiving it.
 *
 * <h2>Two audiences, deliberately different</h2>
 * The buyer's mail is a receipt - it repeats what they bought and where it is
 * going, because it is the copy they will search their inbox for six weeks later.
 * ProcurePal's mail is a work order - who bought, how much, what to pick - because
 * its reader is deciding what to do next, not remembering what they did.
 *
 * <h2>Why every method takes items rather than reading them</h2>
 * There is no {@code items} association on {@link Order}; lines are fetched through
 * OrderItemRepository, which the caller has already done. Passing them in also
 * keeps this class free of Spring and of the database, which is what makes each
 * template a pure function that a test can assert on directly.
 */
public final class OrderEmails {

    /**
     * Delivery is a flat fee, not a per-line one, so an order with no delivery
     * charge should say nothing rather than "NGN 0.00" - which reads like a mistake
     * the customer is about to be billed for.
     */
    private static final java.math.BigDecimal ZERO = java.math.BigDecimal.ZERO;

    private OrderEmails() {
    }

    /**
     * To the buyer, the moment the order becomes real - a COD checkout, or a card
     * payment that verified. Not sent when the order is merely created awaiting
     * payment: a PENDING_PAYMENT order is a shopping cart with a reference number,
     * and confirming one to a customer who then abandons checkout is a receipt for
     * something they never bought.
     */
    public static EmailMessage orderPlacedForBuyer(
            List<String> to, Order order, List<OrderItem> items, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String body = paragraph("Thank you - we have your order and ProcurePal is preparing it now.")
                + detailTable(orderSummaryDetails(order))
                + lineItems(toLineItems(items))
                + total("Total", money(order.getCurrency(), order.getTotal()))
                + deliveryBlock(order)
                + paragraph("We will email you as your order moves through fulfilment, and again when it "
                        + "is delivered so you can confirm receipt.")
                + button("View your order", buyerOrderUrl(appBaseUrl, order));

        String text = "Thank you - we have your order and ProcurePal is preparing it now.\n\n"
                + textSummary(order)
                + textLineItems(items)
                + "Total: " + money(order.getCurrency(), order.getTotal()) + "\n"
                + textDelivery(order)
                + "\nWe will email you as your order moves through fulfilment.\n"
                + textLink(buyerOrderUrl(appBaseUrl, order));

        return new EmailMessage(
                to,
                "Order " + orderNumber + " confirmed",
                page("Your order is confirmed", "We have order " + orderNumber + " and are preparing it.", body),
                text);
    }

    /**
     * To ProcurePal ops, the same moment. Leads with the customer and the money
     * because that is what triages the queue; the lines are there so a picker can
     * start without opening the app.
     */
    public static EmailMessage newOrderForOperator(
            List<String> to, Order order, List<OrderItem> items, String buyerName, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String body = paragraphHtml(bold(escape(buyerName)) + " placed an order worth "
                        + bold(money(order.getCurrency(), order.getTotal())) + ".")
                + detailTable(operatorSummaryDetails(order, buyerName))
                + lineItems(toLineItems(items))
                + total("Order total", money(order.getCurrency(), order.getTotal()))
                + deliveryBlock(order)
                + button("Open in fulfilment queue", operatorOrderUrl(appBaseUrl, order));

        String text = buyerName + " placed an order worth " + money(order.getCurrency(), order.getTotal()) + ".\n\n"
                + textSummary(order)
                + "Customer: " + buyerName + "\n"
                + "Payment: " + humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name())
                + " (" + humanise(order.getPaymentStatus() == null ? null : order.getPaymentStatus().name()) + ")\n\n"
                + textLineItems(items)
                + "Total: " + money(order.getCurrency(), order.getTotal()) + "\n"
                + textDelivery(order)
                + textLink(operatorOrderUrl(appBaseUrl, order));

        return new EmailMessage(
                to,
                "New order " + orderNumber + " from " + buyerName,
                page("New order " + orderNumber, buyerName + " ordered "
                        + money(order.getCurrency(), order.getTotal()) + ".", body),
                text);
    }

    /**
     * To the buyer, on every ProcurePal-driven status change.
     *
     * <p>DELIVERED is the one that carries an instruction rather than an update, and
     * it is the reason this email exists at all: goods sit in the buyer's INCOMING
     * stock until somebody confirms receipt, and an order nobody confirms is stock
     * the buyer cannot sell. The callout says so in as many words. CANCELLED
     * likewise leads with the reason, because "why" is the only question a
     * cancellation raises.
     */
    public static EmailMessage orderStatusChangedForBuyer(
            List<String> to, Order order, OrderStatus target, String note, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String heading;
        String opening;
        String extra = "";
        String textExtra = "";

        switch (target) {
            case DELIVERED -> {
                heading = "Order " + orderNumber + " was delivered";
                opening = "Your order has been delivered.";
                extra = callout("Confirm receipt in the app to move these items from incoming stock into "
                        + "your usable stock. Until you do, they will not count towards what you can sell.");
                textExtra = "\nConfirm receipt in the app to move these items from incoming stock into your "
                        + "usable stock.\n";
            }
            case CANCELLED -> {
                heading = "Order " + orderNumber + " was cancelled";
                opening = "This order has been cancelled and will not be delivered.";
                if (note != null && !note.isBlank()) {
                    extra = callout("Reason: " + note.trim());
                    textExtra = "\nReason: " + note.trim() + "\n";
                }
            }
            case OUT_FOR_DELIVERY -> {
                heading = "Order " + orderNumber + " is on its way";
                opening = "Your order has left our warehouse and is out for delivery.";
            }
            default -> {
                heading = "Order " + orderNumber + " is now " + humanise(target.name()).toLowerCase();
                opening = "There is an update on your order.";
                if (note != null && !note.isBlank()) {
                    extra = callout(note.trim());
                    textExtra = "\n" + note.trim() + "\n";
                }
            }
        }

        String body = paragraph(opening)
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Status", humanise(target.name())),
                        new Detail("Total", money(order.getCurrency(), order.getTotal()))))
                + extra
                + button("View your order", buyerOrderUrl(appBaseUrl, order));

        String text = opening + "\n\n"
                + "Order: " + orderNumber + "\n"
                + "Status: " + humanise(target.name()) + "\n"
                + "Total: " + money(order.getCurrency(), order.getTotal()) + "\n"
                + textExtra
                + textLink(buyerOrderUrl(appBaseUrl, order));

        return new EmailMessage(to, heading, page(heading, opening, body), text);
    }

    /** To the buyer, when a card payment verifies. Separate from the placed email: a card order gets both. */
    public static EmailMessage paymentReceivedForBuyer(List<String> to, Order order, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String body = paragraphHtml("We have received your payment of "
                        + bold(money(order.getCurrency(), order.getTotal())) + " for order "
                        + bold(orderNumber) + ".")
                + paragraph("ProcurePal will start preparing your order.")
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Amount paid", money(order.getCurrency(), order.getTotal())),
                        new Detail("Payment method",
                                humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name()))))
                + button("View your order", buyerOrderUrl(appBaseUrl, order));

        String text = "We have received your payment of " + money(order.getCurrency(), order.getTotal())
                + " for order " + orderNumber + ".\n\nProcurePal will start preparing your order.\n"
                + textLink(buyerOrderUrl(appBaseUrl, order));

        return new EmailMessage(to, "Payment confirmed for " + orderNumber,
                page("Payment received", "Payment of " + money(order.getCurrency(), order.getTotal())
                        + " confirmed for " + orderNumber + ".", body),
                text);
    }

    /** To ProcurePal ops, same event - the signal that an order is now safe to pick. */
    public static EmailMessage paymentReceivedForOperator(
            List<String> to, Order order, String buyerName, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String body = paragraphHtml(bold(escape(buyerName)) + " paid "
                        + bold(money(order.getCurrency(), order.getTotal())) + " for order "
                        + bold(orderNumber) + ".")
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Customer", buyerName),
                        new Detail("Amount", money(order.getCurrency(), order.getTotal())),
                        new Detail("Payment method",
                                humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name()))))
                + button("Open in fulfilment queue", operatorOrderUrl(appBaseUrl, order));

        String text = buyerName + " paid " + money(order.getCurrency(), order.getTotal())
                + " for order " + orderNumber + ".\n" + textLink(operatorOrderUrl(appBaseUrl, order));

        return new EmailMessage(to, "Payment received for " + orderNumber,
                page("Payment received for " + orderNumber,
                        buyerName + " paid " + money(order.getCurrency(), order.getTotal()) + ".", body),
                text);
    }

    /**
     * To the buyer, when a card payment does not complete. The order still exists
     * and is still payable, which is the whole message - a failed payment reads like
     * a lost order unless it explicitly says otherwise.
     */
    public static EmailMessage paymentFailedForBuyer(
            List<String> to, Order order, String reason, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String explanation = reason == null || reason.isBlank()
                ? "The payment attempt did not complete."
                : reason.trim();
        String body = paragraph(explanation)
                + callout("Your order is still waiting for you - nothing has been lost. You can try paying "
                        + "again from the order page.")
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Amount due", money(order.getCurrency(), order.getTotal()))))
                + button("Try paying again", buyerOrderUrl(appBaseUrl, order));

        String text = explanation + "\n\nYour order is still waiting - nothing has been lost, and you can "
                + "try paying again.\n\nOrder: " + orderNumber + "\nAmount due: "
                + money(order.getCurrency(), order.getTotal()) + "\n"
                + textLink(buyerOrderUrl(appBaseUrl, order));

        return new EmailMessage(to, "Payment did not go through for " + orderNumber,
                page("Payment did not go through", explanation, body), text);
    }

    private static List<Detail> orderSummaryDetails(Order order) {
        List<Detail> details = new ArrayList<>();
        details.add(new Detail("Order number", order.getOrderNumber()));
        details.add(new Detail("Placed", EmailLayout.timestamp(order.getPlacedAt())));
        details.add(new Detail("Payment",
                humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name())));
        return details;
    }

    private static List<Detail> operatorSummaryDetails(Order order, String buyerName) {
        List<Detail> details = new ArrayList<>();
        details.add(new Detail("Order number", order.getOrderNumber()));
        details.add(new Detail("Customer", buyerName));
        details.add(new Detail("Placed", EmailLayout.timestamp(order.getPlacedAt())));
        details.add(new Detail("Payment",
                humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name())
                        + " - " + humanise(order.getPaymentStatus() == null ? null
                                : order.getPaymentStatus().name())));
        return details;
    }

    private static List<LineItem> toLineItems(List<OrderItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(item -> new LineItem(
                        item.getProductName(),
                        item.getQuantity() + " "
                                + (item.getUnitOfMeasure() == null || item.getUnitOfMeasure().isBlank()
                                        ? "x" : item.getUnitOfMeasure())
                                + " at " + money(null, item.getUnitPrice()),
                        money(null, item.getLineTotal())))
                .toList();
    }

    /** Rendered for both audiences: the buyer checks it, ProcurePal delivers to it. */
    private static String deliveryBlock(Order order) {
        String address = joinNonBlank(", ",
                order.getDeliveryAddressLine1(),
                order.getDeliveryAddressLine2(),
                order.getDeliveryCity(),
                order.getDeliveryState());
        if (address.isBlank() && (order.getDeliveryContactName() == null)) {
            return "";
        }
        List<Detail> details = new ArrayList<>();
        details.add(new Detail("Deliver to", order.getDeliveryContactName()));
        details.add(new Detail("Phone", order.getDeliveryContactPhone()));
        details.add(new Detail("Address", address));
        details.add(new Detail("Landmark", order.getDeliveryLandmark()));
        details.add(new Detail("Delivery notes", order.getDeliveryNotes()));
        if (order.getDeliveryFee() != null && order.getDeliveryFee().compareTo(ZERO) > 0) {
            details.add(new Detail("Delivery fee", money(order.getCurrency(), order.getDeliveryFee())));
        }
        return "<h2 style=\"margin:20px 0 6px; font-size:15px; font-weight:650; color:#1f2937;\">Delivery</h2>"
                + detailTable(details);
    }

    private static String textSummary(Order order) {
        return "Order: " + order.getOrderNumber() + "\n"
                + "Placed: " + EmailLayout.timestamp(order.getPlacedAt()) + "\n\n";
    }

    private static String textLineItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Items:\n");
        for (OrderItem item : items) {
            builder.append("  - ").append(item.getProductName())
                    .append(" x").append(item.getQuantity())
                    .append("  ").append(money(null, item.getLineTotal()))
                    .append('\n');
        }
        return builder.append('\n').toString();
    }

    private static String textDelivery(Order order) {
        String address = joinNonBlank(", ",
                order.getDeliveryAddressLine1(),
                order.getDeliveryAddressLine2(),
                order.getDeliveryCity(),
                order.getDeliveryState());
        if (address.isBlank()) {
            return "";
        }
        return "\nDelivery:\n  " + joinNonBlank("\n  ", order.getDeliveryContactName(),
                order.getDeliveryContactPhone(), address) + "\n";
    }

    /**
     * The buyer's route and ProcurePal's route to the same order are different pages
     * in the frontend - one is "my orders", the other is the fulfilment queue - and
     * sending either audience the other's link lands them on a 403.
     */
    private static String buyerOrderUrl(String appBaseUrl, Order order) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/app/orders/" + order.getId();
    }

    private static String operatorOrderUrl(String appBaseUrl, Order order) {
        return appBaseUrl == null || appBaseUrl.isBlank()
                ? "" : appBaseUrl + "/app/marketplace/orders/" + order.getId();
    }

    /** Text bodies get a bare URL; there is no anchor to hide it behind. */
    private static String textLink(String url) {
        return url == null || url.isBlank() ? "" : "\n" + url + "\n";
    }
}
