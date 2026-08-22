package com.procurepal_services.stock_bridge_api.email.preferences.dto;

import com.procurepal_services.stock_bridge_api.entity.User;

/**
 * The caller's marketing-email setting after a {@code PUT /api/me/email-preferences}.
 *
 * <h2>Why the write returns the resulting state</h2>
 * So the UI never has to guess, and never has to re-read. A toggle that fires a
 * request and then optimistically renders what it asked for will show the wrong
 * thing the first time the server disagrees with it; returning the persisted value
 * makes the response the single source of truth for the switch.
 *
 * <h2>Why there is no matching GET</h2>
 * Reading this value belongs to {@code /api/me} - it is one field of "who am I",
 * and {@code ProfileResponse} carries it read-only. Publishing a second read of the
 * same flag would create two endpoints that can disagree after a change lands in
 * one of them, for no gain: the app has already loaded the profile by the time it
 * can render a settings screen. Writes live here rather than on the profile
 * endpoint because {@code PUT /api/me} replaces the profile fields wholesale, and
 * folding consent into that would mean any screen that edits a phone number also
 * has to remember to send back the marketing flag - forget it once and the user is
 * unsubscribed by a form that never mentioned email.
 */
public record EmailPreferencesResponse(boolean receivePromotionalEmail) {

    public static EmailPreferencesResponse from(User user) {
        return new EmailPreferencesResponse(user.isReceivePromotionalEmail());
    }
}
