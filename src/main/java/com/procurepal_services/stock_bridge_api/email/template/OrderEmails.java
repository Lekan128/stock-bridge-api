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

    /** One seller's share of a checkout, as the buyer's receipt shows it. */
    public record SellerPart(Order order, List<OrderItem> items, String sellerName) {
    }

    /**
     * To the buyer, ONCE per checkout, the moment it becomes real - a pay-on-delivery
     * checkout, or a card payment that verified.
     *
     * <h2>One email, however many sellers</h2>
     * A basket from three sellers is three orders, but it was one press of one button and,
     * for a card checkout, one payment. Three receipts plus three "payment received" emails
     * would be six messages for one purchase, five of which nobody reads. So this is the
     * receipt AND, for a card checkout, the payment confirmation: one overarching email
     * with a section per seller (the multi-vendor pattern - each seller separately gets
     * only their own part, see {@link #newOrderForSeller}).
     *
     * <p>Not sent while an order merely awaits payment: a PENDING_PAYMENT order is a
     * shopping cart with a reference number, and confirming one to a customer who then
     * abandons checkout is a receipt for something they never bought.
     */
    public static EmailMessage checkoutConfirmedForBuyer(List<String> to, List<SellerPart> parts, String appBaseUrl) {
        Order first = parts.getFirst().order();
        boolean paid = first.getPaymentStatus() == com.procurepal_services.stock_bridge_api.entity.PaymentStatus.PAID;
        boolean split = parts.size() > 1;
        String currency = first.getCurrency();
        java.math.BigDecimal grandTotal = parts.stream()
                .map(part -> part.order().getTotal())
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        String opening = (paid
                        ? "Thank you - we have your payment of " + money(currency, grandTotal) + "."
                        : "Thank you - we have your order.")
                + (split
                        ? " Your basket came from " + parts.size() + " sellers, so it is " + parts.size()
                                + " orders, each delivered separately. Each seller is preparing their part."
                        : " " + sellerLabel(parts.getFirst()) + " is preparing it now.");

        List<Detail> summary = new ArrayList<>();
        summary.add(new Detail(split ? "Orders" : "Order number",
                String.join(", ", parts.stream().map(part -> part.order().getOrderNumber()).toList())));
        summary.add(new Detail("Placed", EmailLayout.timestamp(first.getPlacedAt())));
        summary.add(new Detail("Payment", paid
                ? "Paid - " + humanise(first.getPaymentMethod() == null ? null : first.getPaymentMethod().name())
                : "Pay on delivery - " + money(currency, grandTotal) + " due at the door"));

        StringBuilder body = new StringBuilder(paragraph(opening)).append(detailTable(summary));
        StringBuilder text = new StringBuilder(opening).append("\n\n");
        for (Detail detail : summary) {
            text.append(detail.label()).append(": ").append(detail.value()).append('\n');
        }
        text.append('\n');
        for (SellerPart part : parts) {
            Order order = part.order();
            if (split) {
                body.append(subheading(order.getOrderNumber() + " from " + sellerLabel(part)));
                text.append(order.getOrderNumber()).append(" from ").append(sellerLabel(part)).append('\n');
            }
            body.append(lineItems(toLineItems(part.items())));
            text.append(textLineItems(part.items()));
            if (order.getDeliveryFee() != null && order.getDeliveryFee().compareTo(ZERO) > 0) {
                body.append(detailTable(List.of(new Detail("Delivery fee", money(currency, order.getDeliveryFee())))));
            }
            body.append(total(split ? "Order total" : "Total", money(currency, order.getTotal())));
            text.append(split ? "Order total: " : "Total: ").append(money(currency, order.getTotal())).append("\n\n");
        }
        if (split) {
            body.append(total(paid ? "Total paid" : "Total due", money(currency, grandTotal)));
            text.append(paid ? "Total paid: " : "Total due: ").append(money(currency, grandTotal)).append('\n');
        }
        String closing = "We will email you when " + (split ? "each order" : "it")
                + " is out for delivery and when it arrives, so you can confirm receipt.";
        String url = split ? buyerOrdersUrl(appBaseUrl) : buyerOrderUrl(appBaseUrl, first);
        body.append(deliveryBlock(first, false))
                .append(paragraph(closing))
                .append(button(split ? "View your orders" : "View your order", url));
        text.append(textDelivery(first)).append('\n').append(closing).append('\n').append(textLink(url));

        String subject = split
                ? "Your " + parts.size() + " orders are confirmed"
                : "Order " + first.getOrderNumber() + " confirmed";
        String heading = split ? "Your orders are confirmed" : "Your order is confirmed";
        return new EmailMessage(to, subject, page(heading, opening, body.toString()), text.toString());
    }

    /**
     * To the SELLER of one order - ProcurePal for its own goods, the vendor for theirs -
     * the moment it becomes real. Exactly one per order: for a card checkout it already
     * says "paid", so there is no second "payment received" email for the same moment.
     *
     * <p>A work order rather than a receipt: who bought, whether the money is in, what to
     * pick and where it goes. Leads with the customer and the payment state because that
     * is what triages the queue.
     */
    public static EmailMessage newOrderForSeller(
            List<String> to, Order order, List<OrderItem> items, String buyerName, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String amount = money(order.getCurrency(), order.getTotal());
        boolean paid = order.getPaymentStatus() == com.procurepal_services.stock_bridge_api.entity.PaymentStatus.PAID;
        String state = paid
                ? "It is paid, so you can start preparing it."
                : "It is pay on delivery: collect " + amount + " at the door.";

        String body = paragraphHtml(bold(buyerName) + " placed an order worth " + bold(amount) + ". " + escape(state))
                + detailTable(operatorSummaryDetails(order, buyerName))
                + lineItems(toLineItems(items))
                + total("Order total", amount)
                + deliveryBlock(order, true)
                + button("Open the order", operatorOrderUrl(appBaseUrl, order));

        String text = buyerName + " placed an order worth " + amount + ". " + state + "\n\n"
                + textSummary(order)
                + "Customer: " + buyerName + "\n\n"
                + textLineItems(items)
                + "Total: " + amount + "\n"
                + textDelivery(order)
                + textLink(operatorOrderUrl(appBaseUrl, order));

        return new EmailMessage(
                to,
                "New order " + orderNumber + " from " + buyerName + (paid ? " (paid)" : ""),
                page("New order " + orderNumber, buyerName + " ordered " + amount + ". " + state, body),
                text);
    }

    /**
     * To the seller, when a BUYER cancels. Without it a seller - a vendor especially, who
     * has nobody at ProcurePal watching their queue for them - can pick and dispatch goods
     * for an order that no longer exists. The buyer is not emailed: they pressed the button.
     */
    public static EmailMessage orderCancelledByBuyerForSeller(
            List<String> to, Order order, String buyerName, String reason, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String opening = buyerName + " cancelled order " + orderNumber + ". Do not prepare or dispatch it.";
        String body = paragraph(opening)
                + (reason == null || reason.isBlank() ? "" : callout("Reason given: " + reason.trim()))
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Customer", buyerName),
                        new Detail("Total", money(order.getCurrency(), order.getTotal()))))
                + button("Open the order", operatorOrderUrl(appBaseUrl, order));
        String text = opening + "\n"
                + (reason == null || reason.isBlank() ? "" : "\nReason given: " + reason.trim() + "\n")
                + textLink(operatorOrderUrl(appBaseUrl, order));
        return new EmailMessage(to, "Order " + orderNumber + " was cancelled by the customer",
                page("Order " + orderNumber + " was cancelled", opening, body), text);
    }
    /**
     * To the buyer, when the seller moves the order to a step the buyer acts on or waits
     * for: out for delivery, delivered, cancelled. The internal steps (confirmed,
     * processing) are in-app only - see {@code OrderLifecycleService.EMAILED_STATUSES}.
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
                opening = "Your order is on its way to you.";
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

    /**
     * To the buyer, when money arrives for orders that were ALREADY placed - a
     * pay-on-delivery order settled by card, or a payment that landed after the seller
     * moved the order on. A normal card checkout never sends this: its receipt
     * ({@link #checkoutConfirmedForBuyer}) already says "paid". One per payment, however
     * many orders it covered.
     */
    public static EmailMessage paymentReceivedForBuyer(List<String> to, List<Order> orders, String appBaseUrl) {
        Order first = orders.getFirst();
        java.math.BigDecimal amount = orders.stream()
                .map(Order::getTotal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        String numbers = String.join(", ", orders.stream().map(Order::getOrderNumber).toList());
        String paidLine = money(first.getCurrency(), amount);
        String url = orders.size() > 1 ? buyerOrdersUrl(appBaseUrl) : buyerOrderUrl(appBaseUrl, first);
        String body = paragraphHtml("We have received your payment of " + bold(paidLine) + " for "
                        + (orders.size() > 1 ? "orders " : "order ") + bold(numbers) + ".")
                + detailTable(List.of(
                        new Detail(orders.size() > 1 ? "Orders" : "Order", numbers),
                        new Detail("Amount paid", paidLine),
                        new Detail("Payment method",
                                humanise(first.getPaymentMethod() == null ? null : first.getPaymentMethod().name()))))
                + button(orders.size() > 1 ? "View your orders" : "View your order", url);
        String text = "We have received your payment of " + paidLine + " for " + numbers + ".\n" + textLink(url);
        return new EmailMessage(to, "Payment received for " + summariseNumbers(orders),
                page("Payment received", "Payment of " + paidLine + " received.", body), text);
    }

    /** To the seller of one order, same event - the money arrived after the order was already placed. */
    public static EmailMessage paymentReceivedForSeller(
            List<String> to, Order order, String buyerName, String appBaseUrl) {
        String orderNumber = order.getOrderNumber();
        String body = paragraphHtml(bold(buyerName) + " paid "
                        + bold(money(order.getCurrency(), order.getTotal())) + " for order "
                        + bold(orderNumber) + ".")
                + detailTable(List.of(
                        new Detail("Order", orderNumber),
                        new Detail("Customer", buyerName),
                        new Detail("Amount", money(order.getCurrency(), order.getTotal())),
                        new Detail("Payment method",
                                humanise(order.getPaymentMethod() == null ? null : order.getPaymentMethod().name()))))
                + button("Open the order", operatorOrderUrl(appBaseUrl, order));

        String text = buyerName + " paid " + money(order.getCurrency(), order.getTotal())
                + " for order " + orderNumber + ".\n" + textLink(operatorOrderUrl(appBaseUrl, order));

        return new EmailMessage(to, "Payment received for " + orderNumber,
                page("Payment received for " + orderNumber,
                        buyerName + " paid " + money(order.getCurrency(), order.getTotal()) + ".", body),
                text);
    }

    /**
     * To the buyer, when a card payment is DECLINED or comes up short - once per attempt,
     * however many orders it covered. Not sent when the buyer simply closed the payment
     * page: they know they did not pay, and the order page shows the pay-by deadline.
     * The order still exists and is still payable, which is the whole message - a failed
     * payment reads like a lost order unless it explicitly says otherwise.
     */
    public static EmailMessage paymentFailedForBuyer(
            List<String> to, List<Order> orders, String reason, String appBaseUrl) {
        Order first = orders.getFirst();
        java.math.BigDecimal due = orders.stream()
                .map(Order::getTotal)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        String numbers = String.join(", ", orders.stream().map(Order::getOrderNumber).toList());
        String explanation = reason == null || reason.isBlank()
                ? "The payment attempt did not complete."
                : reason.trim();
        String url = buyerOrderUrl(appBaseUrl, first);
        String body = paragraph(explanation)
                + callout("Your order is still waiting for you - nothing has been lost. You can try paying "
                        + "again from the order page.")
                + detailTable(List.of(
                        new Detail(orders.size() > 1 ? "Orders" : "Order", numbers),
                        new Detail("Amount due", money(first.getCurrency(), due))))
                + button("Try paying again", url);

        String text = explanation + "\n\nYour order is still waiting - nothing has been lost, and you can "
                + "try paying again.\n\n" + (orders.size() > 1 ? "Orders: " : "Order: ") + numbers
                + "\nAmount due: " + money(first.getCurrency(), due) + "\n"
                + textLink(url);

        return new EmailMessage(to, "Payment did not go through for " + summariseNumbers(orders),
                page("Payment did not go through", explanation, body), text);
    }

    private static String summariseNumbers(List<Order> orders) {
        return orders.size() == 1
                ? orders.getFirst().getOrderNumber()
                : orders.getFirst().getOrderNumber() + " and " + (orders.size() - 1) + " more";
    }

    private static String sellerLabel(SellerPart part) {
        return part.sellerName() == null || part.sellerName().isBlank() ? "The seller" : part.sellerName();
    }

    private static String subheading(String text) {
        return "<h2 style=\"margin:20px 0 6px; font-size:15px; font-weight:650; color:#1f2937;\">"
                + escape(text) + "</h2>";
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

    /**
     * Rendered for both audiences: the buyer checks it, the seller delivers to it. The fee
     * is left out of the buyer's checkout receipt, which shows each order's fee in its own
     * section - one address, but not one fee.
     */
    private static String deliveryBlock(Order order, boolean includeFee) {
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
        if (includeFee && order.getDeliveryFee() != null && order.getDeliveryFee().compareTo(ZERO) > 0) {
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

    private static String buyerOrdersUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/app/orders";
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
