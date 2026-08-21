package com.procurepal_services.stock_bridge_api.email;

/**
 * What an email <em>is for</em>, which is what decides whether a given address is
 * allowed to receive it. Every {@link EmailMessage} carries one, and
 * {@link EmailEligibility} does nothing but map (address, kind) to a yes or a no.
 *
 * <h2>Why this is a property of the message and not of the recipient</h2>
 * Eligibility is a two-sided question - "has this person agreed to hear from us"
 * crossed with "is this the sort of thing they agreed to". A user who unsubscribed
 * from marketing has not thereby cancelled their order receipts, and an address
 * that has never been verified still has to be able to receive the mail that
 * verifies it. Neither fact can be read off the user row alone, so the message has
 * to say what it is.
 *
 * <h2>Why four values and not a boolean</h2>
 * The obvious modelling is {@code promotional: true/false}, and it is wrong in
 * exactly one place, which happens to be the place that matters most: the
 * verification email itself. Under a boolean it would be non-promotional, would
 * therefore require a verified address, and could only ever be sent to someone who
 * no longer needs it. That deadlock - you must be verified to receive the mail that
 * verifies you - is the reason {@link #VERIFICATION} exists as its own value rather
 * than as a flag somebody remembers to set.
 *
 * <p>{@link #SECURITY} is the same argument one step out. Telling someone their
 * password was changed is not a courtesy that can be withheld pending an unrelated
 * opt-in; if the change was not theirs, that message is the only warning they get,
 * and the account most likely to be attacked is the one whose owner never finished
 * setting it up. Folding it into TRANSACTIONAL would mean the compromised-account
 * notice is silently suppressed for precisely the unverified accounts that are
 * easiest to compromise.
 *
 * <h2>The cost of the two bypassing values</h2>
 * VERIFICATION and SECURITY can be sent to an address nobody has ever confirmed
 * exists, so they can bounce, and bounces are what damage a sending domain's
 * reputation with SES. That is accepted deliberately and is bounded: both are
 * low-volume, both are triggered by a human action on the account (a signup, an
 * admin resetting a password) rather than by a list, and neither is ever sent in
 * bulk. TRANSACTIONAL and PROMOTIONAL - the two that scale with order volume and
 * with marketing - are both gated.
 */
public enum EmailKind {

    /**
     * Mail about something that just happened to an account that asked for it: an
     * order receipt, a fulfilment update, a payment outcome, a suspension notice.
     *
     * <p><strong>Requires a verified address.</strong> There is no consent flag for
     * this and there should not be one - a company cannot opt out of being told
     * what happened to its own order and still expect the order to work. Verified
     * is the only gate.
     */
    TRANSACTIONAL,

    /**
     * Marketing: campaigns, offers, catalogue announcements. Nothing in the
     * application sends this yet; the value exists so the unsubscribe flag has a
     * meaning to enforce before there is anything to enforce it against, and so the
     * first sender that appears cannot be written without deciding this question.
     *
     * <p><strong>Requires a verified address AND {@code receive_promotional_email}
     * on every user row holding that address.</strong> Every message of this kind
     * must also carry RFC 8058 one-click unsubscribe headers - that is module C's
     * job, not this enum's, but a PROMOTIONAL message without them is a bug.
     */
    PROMOTIONAL,

    /**
     * Mail whose purpose is to establish that the address works at all: the
     * verification link, and the welcome/invitation mail that carries one.
     *
     * <p><strong>Bypasses every check.</strong> See the class doc - gating this
     * would make the flag unsettable. Note the consequence for the welcome and
     * invitation emails: a brand-new user is unverified by definition (the column
     * defaults to FALSE - see {@code V8__email_eligibility.sql}), so if those were
     * TRANSACTIONAL a new tenant would be greeted with silence and would never
     * learn there was anything to verify.
     */
    VERIFICATION,

    /**
     * Mail about the security of the account itself: a password an administrator
     * reset, and anything later added of that shape.
     *
     * <p><strong>Bypasses every check</strong>, on the reasoning in the class doc:
     * withholding it does not protect the recipient from anything, and the case
     * where it is most needed is the case where it would be suppressed.
     */
    SECURITY;

    /**
     * True for the kinds that may reach an address nobody has verified.
     *
     * <p>Exists so the rule is stated once, here, rather than as a set membership
     * test spelled out at each site that needs it - there are only two such sites
     * today and both would drift.
     */
    public boolean bypassesVerification() {
        return this == VERIFICATION || this == SECURITY;
    }

    /** True only for the kind that must additionally honour an unsubscribe. */
    public boolean requiresPromotionalConsent() {
        return this == PROMOTIONAL;
    }
}
