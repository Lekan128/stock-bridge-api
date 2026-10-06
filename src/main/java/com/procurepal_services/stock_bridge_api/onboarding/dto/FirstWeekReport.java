package com.procurepal_services.stock_bridge_api.onboarding.dto;

import java.util.List;

/**
 * The first-week list and the funnel over the same window (plan §4, "what we measure"):
 * setup requests → accounts → products loaded → activated (first stock change within 72 hours)
 * → habit (5 of the first 7 days).
 *
 * @param days         the window: shops that signed up in the last this-many days
 * @param habitEligible shops at least 7 days old, the only ones a habit can be judged on
 * @param shops        one page of the window's shops (or of those matching the search), newest
 *                     first; the funnel always counts the whole window
 */
public record FirstWeekReport(
        int days,
        int page,
        int totalPages,
        int setupRequests,
        int signups,
        int productsLoaded,
        int activated,
        int habitEligible,
        int habit,
        List<FirstWeekShop> shops) {
}
