package com.procurepal_services.stock_bridge_api.founding.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The team moving a setup along. Every field is optional; absent means "leave it".
 *
 * @param status    the new status. The first move out of NEW also counts as the first reply.
 * @param contacted true records the first reply without changing the status: a shop that sent its
 *                  list from the app (LIST_RECEIVED) before anybody had answered it
 * @param note      replaces the note
 */
public record UpdateSetupRequest(
        @Pattern(
                regexp = "NEW|CONTACTED|LIST_RECEIVED|LOADED|RUNNING|NOT_A_FIT",
                message = "Unknown status.") String status,
        Boolean contacted,
        @Size(max = 500, message = "Keep the note under 500 characters.") String note) {

    /** Status and note, as before the contacted flag. */
    public UpdateSetupRequest(String status, String note) {
        this(status, null, note);
    }
}
