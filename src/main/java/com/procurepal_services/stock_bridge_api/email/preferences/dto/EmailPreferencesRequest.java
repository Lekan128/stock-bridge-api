package com.procurepal_services.stock_bridge_api.email.preferences.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/me/email-preferences}.
 *
 * <h2>Why this is a boxed Boolean with @NotNull and not a primitive</h2>
 * This is the load-bearing detail in the file. A {@code boolean} component
 * deserialises a missing or null JSON field to {@code false} without complaint - so
 * a frontend bug, a typo in the field name, or an empty {@code {}} body would
 * quietly unsubscribe the caller from marketing and report success. The user would
 * have no idea, and the only evidence would be mail that stopped arriving, which is
 * exactly the kind of failure nobody reports and nobody can reproduce.
 *
 * <p>Boxed plus {@code @NotNull} turns all of those into a 400 naming the field.
 * The general rule this is an instance of: when both values of a flag are
 * meaningful and one of them is destructive, "absent" must not be a synonym for
 * either.
 *
 * @param receivePromotionalEmail true to opt in to marketing, false to opt out.
 *     Note the sense is the same as the database column, not inverted into an
 *     "unsubscribed" flag - one name and one direction all the way from the JSON to
 *     {@code users.receive_promotional_email}, so nobody has to remember where the
 *     negation happens.
 */
public record EmailPreferencesRequest(
        @NotNull(message = "must be true or false") Boolean receivePromotionalEmail) {
}
