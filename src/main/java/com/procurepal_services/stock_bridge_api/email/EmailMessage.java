package com.procurepal_services.stock_bridge_api.email;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One email, fully rendered and ready to hand to SES.
 *
 * <h2>Why this is a value object and not a builder over entities</h2>
 * Email is sent after the business transaction commits, on another thread (see
 * {@link EmailDispatcher}). By then there is no Hibernate session, no
 * {@code TenantContext}, and no request - so touching a lazy association at send
 * time would blow up with a {@code LazyInitializationException} on a thread nobody
 * is watching. Every message is therefore rendered to plain strings while the
 * caller still holds its entities, and this record is what crosses the thread
 * boundary. Nothing downstream of it can reach the database.
 *
 * <h2>Both bodies, always</h2>
 * Sending HTML alone is what gets mail filed as spam, and some corporate clients
 * strip it outright. Every template produces a text alternative, so
 * {@code textBody} is not optional in practice even though nothing here forbids a
 * null one.
 *
 * <h2>Why the kind travels with the message</h2>
 * {@link EmailKind} is what {@link EmailEligibility} needs in order to answer
 * anything at all, and by the time a message reaches {@link EmailDispatcher} the
 * only thing left describing it is this record. Passing the kind alongside the
 * message as a second argument was the alternative, and it fails on the same
 * grounds the record itself exists for: there would then be two things to keep in
 * step across a thread boundary, and a caller who forgot the second one would get
 * silently wrong gating rather than a compile error.
 */
public record EmailMessage(
        List<String> to, String subject, String htmlBody, String textBody, EmailKind kind) {

    /**
     * Deliberately loose. This is not validating an address a user typed - the form
     * layer already did that - it is refusing to hand SES something that is
     * obviously not an address, because SES rejects the whole {@code SendEmail}
     * call when any one destination is malformed. One bad row in a company's user
     * list would otherwise cost every other recipient their copy.
     */
    private static final Pattern PLAUSIBLE_ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    /**
     * Normalises on the way in, so the de-duplication below actually works and so
     * no caller has to remember to trim. Addresses that survive are lowercased: the
     * local part is technically case-sensitive, but no mail provider this
     * application will meet treats it that way, and not lowercasing means
     * Ops@example.com and ops@example.com arrive as two copies of one mail.
     */
    public EmailMessage {
        // TRANSACTIONAL is the safe default for a null, not merely the common one:
        // it is the most restrictive kind that is actually sent today (PROMOTIONAL
        // is stricter but nothing produces it yet), so a message that arrives here
        // without a stated purpose is gated rather than waved through. A message
        // that must bypass the verified check has to say so explicitly.
        kind = kind == null ? EmailKind.TRANSACTIONAL : kind;
        to = to == null
                ? List.of()
                : to.stream()
                        .filter(address -> address != null && !address.isBlank())
                        .map(address -> address.trim().toLowerCase(Locale.ROOT))
                        .filter(address -> PLAUSIBLE_ADDRESS.matcher(address).matches())
                        .distinct()
                        .toList();
    }

    /**
     * The four-argument form every template and test predating per-recipient
     * eligibility was written against, kept for exactly the reason
     * {@link com.procurepal_services.stock_bridge_api.client.dto.ClientSignupRequest}
     * keeps its five-argument one: appending a component to a record otherwise
     * breaks every construction site at once, for a field almost none of them have
     * an opinion about.
     *
     * <p>It means TRANSACTIONAL, which is what all of those call sites already
     * were. The handful that are not - the verification-bearing and security
     * messages in {@code AccountEmails} - name their kind explicitly through the
     * five-argument form, and that asymmetry is deliberate: the unusual case should
     * be the one that has to say something.
     */
    public EmailMessage(List<String> to, String subject, String htmlBody, String textBody) {
        this(to, subject, htmlBody, textBody, EmailKind.TRANSACTIONAL);
    }

    /**
     * The same message to a narrower audience. This is how {@link EmailDispatcher}
     * applies an eligibility decision: dropping addresses rather than dropping the
     * message, so that one unverified copied-in recipient does not cost the other
     * recipients their mail - which is the same failure the address validation in
     * the compact constructor above exists to prevent, arriving by a different
     * route.
     *
     * <p>Returns {@code this} when nothing was removed, so the common case creates
     * no garbage and stays reference-identical for anyone comparing.
     */
    public EmailMessage withRecipients(List<String> recipients) {
        List<String> narrowed = recipients == null ? List.of() : recipients;
        if (narrowed.equals(to)) {
            return this;
        }
        return new EmailMessage(narrowed, subject, htmlBody, textBody, kind);
    }

    /**
     * False is a completely ordinary outcome, not an error: a company whose users
     * have never filled in an email address has nowhere to be told anything. The
     * dispatcher drops these silently rather than logging a warning per event,
     * which would be noise proportional to order volume.
     */
    public boolean hasRecipients() {
        return !to.isEmpty();
    }
}
