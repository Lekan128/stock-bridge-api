package com.procurepal_services.stock_bridge_api.email.verification;

/**
 * Every way a verification link can fail, collapsed into one exception with one
 * message. That collapse is the entire purpose of the class.
 *
 * <h2>Why the caller is never told which failure it was</h2>
 * The distinguishable outcomes are: no such token, expired, already used,
 * superseded by a newer one, the user has since been deleted, and the address on
 * the account no longer matches the address the link was sent to. Six states, and
 * an endpoint that names them is an oracle.
 *
 * <p>The concrete leaks, in order of how bad they are:
 * <ul>
 *   <li>"Expired" versus "unknown" tells an attacker enumerating random tokens
 *       when they have guessed a real one. The guess is astronomically unlikely to
 *       succeed on its own, but a truthful "that token existed" turns a blind
 *       search into a search with feedback, and feedback is what makes brute force
 *       tractable.</li>
 *   <li>"Already used" tells the holder of a leaked link - from a forwarded mail,
 *       a shared screenshot, a proxy log - that the account is real and that
 *       somebody has already acted on it. That is an account-existence signal
 *       obtained without ever having an account.</li>
 *   <li>"The address on this account has changed" is the worst of the three,
 *       because it confirms both that the account exists and that a specific
 *       address is no longer on it.</li>
 * </ul>
 *
 * <h2>What is given up, and where the information actually lives</h2>
 * A user who clicks their own link twice gets a message that does not admit the
 * first click worked, which is genuinely worse UX than the truth would be. That is
 * paid for deliberately, and the copy below is written to make it survivable: it
 * tells the reader what to DO ("request a new one") rather than what happened, so
 * the unhelpful answer still leads somewhere.
 *
 * <p>The distinction is not lost, it is moved somewhere it cannot be probed. The
 * token row keeps {@code consumed_at} and {@code superseded_at} apart precisely so
 * support can answer "did I already click this?" from the database, and the server
 * logs the specific reason at debug. Nothing crosses the wire.
 *
 * <h2>Why one exception rather than a hierarchy</h2>
 * Subtypes would be a standing invitation to handle them separately, which is one
 * careless {@code @ExceptionHandler} away from re-creating the oracle. Making the
 * indistinguishability structural - there is only one type, and it carries only
 * one message - means a future reader has to work at leaking rather than merely
 * forget not to.
 */
public class InvalidVerificationTokenException extends RuntimeException {

    private static final String MESSAGE =
            "This verification link is not valid. It may have expired or already been used - "
                    + "sign in and request a new one.";

    /**
     * @param reason recorded for the server's own logs and never surfaced. It is a
     *     constructor argument rather than a log statement at each throw site so
     *     that the reason and the refusal cannot drift apart, and so a reader can
     *     see at a glance that no throw site is smuggling it into the message.
     */
    public InvalidVerificationTokenException(String reason) {
        super(MESSAGE);
        this.reason = reason;
    }

    private final String reason;

    /** For logging only. Never put this in a response body. */
    public String getReason() {
        return reason;
    }
}
