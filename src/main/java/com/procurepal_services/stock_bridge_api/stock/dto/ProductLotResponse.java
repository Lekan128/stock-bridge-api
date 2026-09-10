package com.procurepal_services.stock_bridge_api.stock.dto;

import com.procurepal_services.stock_bridge_api.entity.StockMovement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * One delivery still on the shelf - {@code GET /api/products/{productId}/lots}, pinned by
 * UNIT_UX_CONTRACT.md section 4.
 *
 * <h2>Why this endpoint had to exist</h2>
 * UNIT_UX_REMEDIATION_PLAN.md section 3, P1-5: the stock-out lot picker was reading the first 50
 * rows of movement <em>history</em> and filtering to {@code IN}. That shows fully-consumed lots
 * as available, shows no remaining quantity for any of them, and silently misses every lot past
 * page 50 - so the user picked blind and was answered with a 409. History is a log; a lot picker
 * needs a balance, and a balance is derived (an {@code IN} movement's quantity minus the
 * allocations against it - MULTI_VENDOR_INVENTORY_DESIGN.md section 5.2a) which no page of
 * history carries. This record is that derivation, done once, on the server.
 *
 * <h2>Every number here is in the product's stock unit</h2>
 * {@code quantity} and {@code remaining} are base units; {@code unitPriceAtTime} is money per
 * ONE base unit. That is what the ledger stores (contract section 3.2) and this response does
 * not convert - the caller knows the product's unit set and converts for display if it wants to
 * show bags. Publishing a half-converted lot list would recreate the mixed-basis comparison that
 * section 3.2 exists to end.
 *
 * @param inMovementId the lot - the {@code IN} {@link StockMovement} itself. An allocation on a
 *     stock-out request addresses this id.
 * @param occurredAt when the delivery ARRIVED, not when it was typed in. The FIFO sort key, and
 *     the date {@link #label} names.
 * @param companyVendorId the supplier, null for a lot recorded before any supplier was on file.
 * @param companyVendorName that supplier's name, for display; null with the id.
 * @param quantity base units received on this delivery.
 * @param remaining base units of it not yet consumed by any stock-out. Never negative - see
 *     {@code StockManagementService.lots}.
 * @param unitPriceAtTime what was paid per base unit, frozen at receipt. Null when the delivery
 *     was recorded without a price.
 * @param label the human name of this lot - <b>composed here, never string-built by a caller</b>.
 *     Contract section 4 says so in as many words, and the reason is non-negotiable 6: the id
 *     above is the only unambiguous handle on a lot, so a UI left to label a lot itself will
 *     eventually print the UUID. Giving it a sentence removes the temptation and the need.
 */
public record ProductLotResponse(
        UUID inMovementId,
        OffsetDateTime occurredAt,
        UUID companyVendorId,
        String companyVendorName,
        int quantity,
        int remaining,
        BigDecimal unitPriceAtTime,
        String label) {

    /**
     * {@code "3 Jan 2026"} - short month, and the year always present. A stock ledger routinely
     * spans a year boundary, and "3 Jan" alone in a picker of open lots is genuinely ambiguous
     * about which January it means.
     */
    private static final DateTimeFormatter LOT_DATE =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /**
     * The one composer of a lot's user-facing name: {@code "3 Jan 2026 &middot; Dangote Nigeria
     * Plc"}, or just the date when the lot predates any supplier being recorded. Static and
     * public so the stock-out error copy names a lot exactly the way the picker did - a message
     * that referred to the same delivery by a different phrase than the row the user clicked
     * would read as being about some other delivery.
     *
     * <p>Rendered at UTC deliberately. {@code occurredAt} for a backdated delivery originates in
     * a spreadsheet DATE cell, i.e. midnight in whatever zone parsed it; formatting at the
     * server's own zone would shift a share of those onto the previous day and make a lot
     * labelled "2 Jan" sort after one labelled "3 Jan".
     */
    public static String label(OffsetDateTime occurredAt, String companyVendorName) {
        String date = occurredAt == null ? "" : occurredAt.atZoneSameInstant(ZoneOffset.UTC).format(LOT_DATE);
        if (companyVendorName == null || companyVendorName.isBlank()) {
            return date;
        }
        return date.isEmpty() ? companyVendorName : date + " · " + companyVendorName;
    }

    /**
     * The date half of {@link #label} on its own, for a sentence that supplies its own
     * preposition: <em>"Only 12 kg left from the <b>3 Jan 2026</b> delivery from Dangote Nigeria
     * Plc"</em>. Same formatter, same zone, so the two always agree about which day a lot is.
     */
    public static String labelDate(OffsetDateTime occurredAt) {
        return occurredAt == null ? "" : occurredAt.atZoneSameInstant(ZoneOffset.UTC).format(LOT_DATE);
    }
}
