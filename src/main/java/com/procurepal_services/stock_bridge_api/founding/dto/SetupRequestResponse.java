package com.procurepal_services.stock_bridge_api.founding.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What the page tells the shop after it asks.
 *
 * @param founding         whether it took one of the founding places (false once they're gone
 *                         or the offer has ended: still a lead, on the regular plan)
 * @param alreadyRequested this WhatsApp number had already asked recently; nothing new was booked
 * @param setupWeekStarts  the Monday of the week their setup is booked into (later weeks once a
 *                         week's capacity is taken)
 */
public record SetupRequestResponse(UUID id, boolean founding, boolean alreadyRequested, LocalDate setupWeekStarts) {
}
