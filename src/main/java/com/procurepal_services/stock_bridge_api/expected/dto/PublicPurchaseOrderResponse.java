package com.procurepal_services.stock_bridge_api.expected.dto;

import com.procurepal_services.stock_bridge_api.expected.PurchaseOrderSnapshot;
import java.time.LocalDate;
import java.util.List;

/**
 * What a supplier sees when they open the link from an email or a WhatsApp message - no account,
 * no login. Only what is already on the PDF: who is asking, what for, where and when, and - on an
 * order made since 2026-11-06 - its prices, delivery charge and total. No email addresses, nothing
 * about the buyer's other business.
 *
 * @param deliveryCharge / total only on a priced order; absent otherwise.
 *
 * @param calledOff the buyer called this order off; the page says so instead of showing a request
 *     that is no longer wanted.
 * @param pdfPath the API path of the PDF, relative to the API's own address.
 */
public record PublicPurchaseOrderResponse(
        String poNumber,
        int revision,
        boolean calledOff,
        LocalDate issuedOn,
        LocalDate neededBy,
        String note,
        Buyer buyer,
        Delivery deliverTo,
        List<Line> lines,
        String pdfPath,
        java.math.BigDecimal deliveryCharge,
        java.math.BigDecimal total) {

    public record Buyer(String name, String phone, String address, String logoUrl) {
    }

    public record Delivery(String name, String address, String phone) {
    }

    /** @param price / amount only on a priced order. */
    public record Line(
            String productName, String comesIn, java.math.BigDecimal quantity,
            java.math.BigDecimal price, java.math.BigDecimal amount) {
    }

    public static PublicPurchaseOrderResponse of(PurchaseOrderSnapshot order, boolean calledOff, String pdfPath) {
        boolean priced = order.isPriced();
        return new PublicPurchaseOrderResponse(
                order.poNumber(),
                order.revision(),
                calledOff,
                order.issuedOn(),
                order.neededBy(),
                order.note(),
                new Buyer(order.buyer().name(), order.buyer().phone(), order.buyer().address(), order.buyer().logoUrl()),
                order.deliverTo() == null
                        ? null
                        : new Delivery(order.deliverTo().name(), order.deliverTo().address(), order.deliverTo().phone()),
                order.lines().stream()
                        .map(line -> priced
                                ? new Line(line.productName(), line.comesIn(), line.quantity(), line.price(), line.amount())
                                : new Line(line.productName(), line.comesIn(), line.quantity(), null, null))
                        .toList(),
                pdfPath,
                priced ? order.deliveryCharge() : null,
                priced ? order.grandTotal() : null);
    }
}
