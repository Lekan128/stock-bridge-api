package com.procurepal_services.stock_bridge_api.founding.dto;

import java.time.LocalDate;

/**
 * The live numbers the landing page shows beside the offer (conversion rule 7).
 *
 * @param open           founding places remain and the offer hasn't ended
 * @param bookedThisWeek founding setups booked into the current week (Monday to Sunday, Lagos)
 */
public record FoundingOfferStatus(
        int total, int left, int weeklyCapacity, int bookedThisWeek, LocalDate endsOn, boolean open) {
}
