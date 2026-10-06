package com.procurepal_services.stock_bridge_api.founding.dto;

/**
 * The queue's tab counts, and how fast the team has been replying.
 *
 * @param waiting                 NEW: nobody has replied yet
 * @param inProgress              CONTACTED, LIST_RECEIVED or LOADED
 * @param medianMinutesToContact  over the last 30 days' requests that have been contacted; null
 *                                until there is one. Counted in plain minutes, staffed hours or not,
 *                                so an evening request pulls it up: that is a fact worth seeing.
 */
public record SetupRequestCounts(
        int waiting,
        int inProgress,
        int running,
        int notAFit,
        int all,
        Integer medianMinutesToContact) {
}
