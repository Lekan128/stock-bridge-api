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
 * <p>There are no prices or totals: a purchase order says what is wanted, and the supplier
 * answers with the price.
 *
 * @param logoUrl the buyer's logo when it had one (a later phase fills it); the file is never
 *     deleted when a logo is replaced, so an old order still draws.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PurchaseOrderSnapshot(
        String poNumber,
        int revision,
        Party buyer,
        Party supplier,
        Delivery deliverTo,
        LocalDate neededBy,
        String note,
        List<Line> lines) {

    /** A company or a supplier as it should read on paper. Every field but the name is optional. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Party(String name, String phone, String email, String address, String logoUrl) {
    }

    /** Where the goods are to be brought: the branch's name, address and phone. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delivery(String name, String address, String phone) {
    }

    /** @param comesIn the words for the unit - "Bag · 50 kg" - not the key. */
    public record Line(String productName, String sku, String comesIn, java.math.BigDecimal quantity) {
    }
}
