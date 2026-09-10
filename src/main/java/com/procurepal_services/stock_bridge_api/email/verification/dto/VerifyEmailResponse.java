package com.procurepal_services.stock_bridge_api.email.verification.dto;

/**
 * What a successful redemption tells the browser. Deliberately almost nothing.
 *
 * <h2>Why there is no user, no email address and no company in here</h2>
 * This is the response of an endpoint that requires no authentication. Anyone
 * holding a token gets this body, and a token can reach an inbox that has been
 * forwarded, archived, or read by somebody who should not have it. Returning the
 * username or the address would turn "I have a link" into "I know who this account
 * belongs to", and returning the tenant would identify the company. None of it is
 * needed: the frontend's verify-email page shows a confirmation and a link to sign
 * in, and if the user is already signed in it refetches {@code /api/me}, which is
 * authenticated and can safely say everything.
 *
 * <p>{@code verified} is always true on the wire - a failure is an
 * {@code ApiError} with a 400, not this record with false. It is a field rather
 * than an implied 200 so the frontend has something explicit to branch on and so
 * the shape stays stable if a future "already verified, nothing to do" case ever
 * wants to be a success.
 */
public record VerifyEmailResponse(boolean verified, String message) {

    /**
     * Named {@code confirmed} for the same reason {@code Verdict.allow} is not
     * {@code allowed}: {@code verified()} is already this record's accessor for its
     * {@code verified} component, so a static factory cannot share the name.
     */
    public static VerifyEmailResponse confirmed() {
        return new VerifyEmailResponse(
                true, "Your email address is confirmed. ProcurePal can now send you order updates.");
    }
}
