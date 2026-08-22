package com.procurepal_services.stock_bridge_api.entity;

/**
 * Why an address is on the suppression list. Stored as its name in
 * {@code email_suppressions.reason}.
 *
 * <h2>Why the reason is recorded at all, when the effect is identical</h2>
 * Every value here produces the same outcome - the address gets no mail - so it
 * would be possible to store nothing but the address. It is recorded because the
 * reasons differ in whether they are ever <em>reversible</em>, and that is a
 * question an operator has to answer by reading a row rather than by guessing. A
 * {@link #PERMANENT_BOUNCE} usually means the mailbox does not exist and lifting it
 * without a change of address just re-earns the bounce; a {@link #COMPLAINT} means a
 * human deliberately reported us and lifting it is a decision about that human, not
 * about the inbox; a {@link #MANUAL} entry was put there by us and is ours to remove.
 * Collapsing them would make "can this be un-suppressed" unanswerable.
 */
public enum EmailSuppressionReason {

    /**
     * SES reported {@code bounceType: "Permanent"} - the receiving server has said,
     * definitively, that this address does not accept mail. A non-existent mailbox,
     * a domain that no longer resolves, a recipient the destination has blocked.
     *
     * <p>Deliberately NOT used for a Transient bounce. A full mailbox and an
     * out-of-office auto-reply both arrive as bounces, and permanently killing a
     * customer's order receipts because their inbox was briefly full would be a far
     * worse outcome than the bounce it prevented. See {@code SesNotificationService}.
     */
    PERMANENT_BOUNCE,

    /**
     * The recipient pressed "mark as spam" and their provider relayed a feedback
     * loop report to SES. Distinct from an unsubscribe: an unsubscribe is a request
     * made to us through a mechanism we offered, a complaint is a report made
     * ABOUT us to a third party who scores our domain on it.
     */
    COMPLAINT,

    /**
     * Put there by an operator rather than by a provider notification - a known-bad
     * address, a role account that should never have been mailed, a customer who
     * asked by phone. Carries no {@code source_message_id}, which is how it is told
     * apart from the provider-driven rows.
     */
    MANUAL
}
