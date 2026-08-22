package com.procurepal_services.stock_bridge_api.vendor.waitlist.dto;

/**
 * What a stranger gets back for submitting the form: one sentence, and nothing
 * else.
 *
 * <h2>Why there is no id, no status and no email in here</h2>
 * This response is IDENTICAL for every accepted submission, and that is the
 * feature. The endpoint is unauthenticated, so anything it varies on is something
 * an anonymous caller can enumerate. Returning the row's id would hand out a
 * handle to a record only super admins may read; returning "you have already
 * applied" - which is the helpful-sounding thing, and the thing the absence of a
 * unique index on {@code email} makes tempting - would turn the form into an
 * oracle answering "does this business deal with ProcurePaddy", one address at a
 * time, for anybody who cares to ask about a competitor.
 *
 * <p>So a repeat application is accepted exactly like a first one, creates a row
 * exactly like a first one (de-duplication is a reviewer's judgement - see
 * {@code VendorWaitlistApplicationRepository}), and produces this same sentence.
 * A caller cannot tell the two apart, because there is nothing in here that
 * differs.
 *
 * <p>The rate limiter's refusal is the one crack in that, and it is deliberately
 * papered over rather than left open: {@code VendorWaitlistThrottledException}
 * carries one message for both the IP and the email key, so a 429 does not reveal
 * WHICH budget ran out.
 */
public record VendorWaitlistApplicationResponse(String message) {

    /**
     * The single sentence. A constant rather than a value built per call, so
     * nothing about the request can leak into it by accident later.
     *
     * <p>It says "waitlist" and promises no timeframe, matching
     * {@code VendorEmails.applicationReceived} word for word in substance - the
     * screen and the email must not disagree about what just happened.
     */
    public static VendorWaitlistApplicationResponse accepted() {
        return new VendorWaitlistApplicationResponse(
                "Thanks - your application is on our vendor waitlist. Our team reviews every application "
                        + "and will email you either way.");
    }
}
