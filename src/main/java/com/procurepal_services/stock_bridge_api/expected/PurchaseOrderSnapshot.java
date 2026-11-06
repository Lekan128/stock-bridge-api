package com.procurepal_services.stock_bridge_api.expected;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything a purchase order shows the supplier, frozen at the moment it was made
 * (PURCHASE_ORDER_PLAN.md section 4.1). Stored as JSON on the order; the PDF and the public page
 * are built from this and never from live rows, so a product renamed or a supplier edited next
 * month does not change a page that has already been sent.
 *
 * <p>Since 2026-11-06 (plan section 11.4) each line carries its price per unit as ordered, and the
 * order an optional delivery charge. An order frozen before that has neither, and reads as it
 * always did ("Please reply with your price").
 *
 * @param logoUrl the buyer's logo when it had one (a later phase fills it); the file is never
 *     deleted when a logo is replaced, so an old order still draws.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderSnapshot(
        String poNumber,
        int revision,
        LocalDate issuedOn,
        Party buyer,
        Party supplier,
        Delivery deliverTo,
        LocalDate neededBy,
        String note,
        List<Line> lines,
        java.math.BigDecimal deliveryCharge) {

    /** The shape before prices. */
    public PurchaseOrderSnapshot(
            String poNumber, int revision, LocalDate issuedOn, Party buyer, Party supplier, Delivery deliverTo,
            LocalDate neededBy, String note, List<Line> lines) {
        this(poNumber, revision, issuedOn, buyer, supplier, deliverTo, neededBy, note, lines, null);
    }

    /** Every line has a price: the order says what it costs, not "reply with your price". */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isPriced() {
        return !lines.isEmpty() && lines.stream().allMatch(line -> line.price() != null);
    }

    /** The lines' amounts added up; zero when none has a price. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.math.BigDecimal itemsTotal() {
        return lines.stream()
                .map(Line::amount)
                .filter(java.util.Objects::nonNull)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    /** "₦45,000", or "₦45,000.50" when there are kobo. One way of writing money on paper, the page and the message. */
    public static String naira(java.math.BigDecimal amount) {
        java.math.BigDecimal value = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        String whole = String.format(java.util.Locale.ENGLISH, "%,d", value.longValue());
        int kobo = value.remainder(java.math.BigDecimal.ONE).movePointRight(2).abs().intValue();
        return "₦" + whole + (kobo == 0 ? "" : String.format(java.util.Locale.ENGLISH, ".%02d", kobo));
    }

    /** The lines plus delivery. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.math.BigDecimal grandTotal() {
        return deliveryCharge == null ? itemsTotal() : itemsTotal().add(deliveryCharge);
    }

    /** A company or a supplier as it should read on paper. Every field but the name is optional. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Party(String name, String phone, String email, String address, String logoUrl) {
    }

    /** Where the goods are to be brought: the branch's name, address and phone. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String name, String address, String phone) {
    }

    /**
     * @param comesIn the words for the unit - "Bag · 50 kg" - not the key.
     * @param price what one of that unit costs; absent on an order made before prices.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(
            String productName, String sku, String comesIn, java.math.BigDecimal quantity, java.math.BigDecimal price) {

        public Line(String productName, String sku, String comesIn, java.math.BigDecimal quantity) {
            this(productName, sku, comesIn, quantity, null);
        }

        /** Quantity times price, to the kobo; absent without a price. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public java.math.BigDecimal amount() {
            return price == null
                    ? null
                    : price.multiply(quantity).setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
}
