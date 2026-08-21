package com.procurepal_services.stock_bridge_api.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Decides <em>when</em> an email leaves: after the caller's transaction commits,
 * and on another thread.
 *
 * <h2>Why this is the opposite rule to NotificationService</h2>
 * {@link com.procurepal_services.stock_bridge_api.notification.NotificationService}
 * deliberately joins the caller's transaction, so a rolled-back order takes its
 * in-app notification down with it - a bell entry pointing at an order that does
 * not exist is worse than no bell entry. Email cannot be run that way, because it
 * is not transactional: once SES accepts a message it is gone, and no rollback
 * un-sends it. Emailing a customer "your order is confirmed" and then failing the
 * commit would leave them holding a receipt for an order the database has never
 * heard of, and a support call nobody can reconstruct.
 *
 * <p>So the rule inverts: the notification must not outlive a rollback, and the
 * email must not precede a commit. {@code afterCommit} is exactly that guarantee -
 * it runs only on the success path, and never at all if the transaction rolls back.
 *
 * <h2>Why it also moves off the request thread</h2>
 * A synchronization callback still runs on the thread that committed, so a slow SES
 * response would be latency added to a request whose work is already finished and
 * whose response is already determined. {@link EmailSender#sendAsync} hands it to
 * the email executor; from the caller's point of view dispatching is a few field
 * reads and a queue push.
 *
 * <h2>The no-transaction case</h2>
 * Not every caller is transactional - a scheduled sweep or a controller-level flow
 * may dispatch with no transaction bound. There is nothing to wait for there, so
 * the message goes straight to the executor. Checking
 * {@code isSynchronizationActive} rather than assuming one exists matters: on a
 * non-transactional thread {@code registerSynchronization} throws, which would turn
 * a courtesy email into a failed request - precisely what this class exists to
 * prevent.
 *
 * <h2>Why per-recipient eligibility is applied HERE, and nowhere else</h2>
 * There were three candidate chokepoints and only one of them works.
 *
 * <p>{@link EmailSender} is the tempting one - it is the last thing before SES, so
 * nothing could slip past it - and it is disqualified outright.
 * {@link EmailSender#sendAsync} runs after the commit, on the email executor: no
 * Hibernate session, no open-in-view, no {@code TenantContext}, no request. Reading
 * the users table from there is a {@code LazyInitializationException} or a
 * connection acquired outside any transaction, on a thread nobody is watching, for
 * a message that is already too late to stop. Eligibility needs the database, so it
 * must happen strictly before that boundary - and this method is the last code that
 * runs before it.
 *
 * <p>{@link EmailNotificationService} is on the right side of the boundary but is
 * nine methods rather than one, so gating there means nine places to forget and a
 * tenth the day someone adds a message type. It also does not have the message in
 * hand at the point it resolves recipients, so it would have to gate the address
 * list before knowing what the message is - and the kind is precisely what the
 * decision turns on.
 *
 * <p>{@link EmailRecipients} fails for a sharper version of the same reason: it
 * answers "who is this addressed to", a question that has nothing to do with what
 * is being sent, and it never sees an {@link EmailKind} at all. Gating there would
 * mean either passing the kind into every recipient method or picking one policy for
 * all mail, and the second is exactly the mistake {@link EmailKind} exists to
 * prevent.
 *
 * <p>So it lands here, on the synchronous half of a class whose entire job is
 * already deciding when - and whether - a message leaves. Everything funnels through
 * {@link #dispatch}, including {@link #dispatchQuietly}, so there is one place to
 * read and one place to get wrong.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailDispatcher {

    private final EmailSender emailSender;
    private final EmailEligibility eligibility;

    public void dispatch(EmailMessage message) {
        if (message == null || !message.hasRecipients()) {
            // Ordinary: a company with no email addresses on file. See
            // EmailMessage.hasRecipients for why this is not worth a log line.
            return;
        }

        // Synchronous, on the caller's thread, inside the caller's transaction -
        // which is the only place it can be. See the class doc.
        EmailMessage deliverable = message.withRecipients(
                eligibility.filterEligible(message.to(), message.kind()));
        if (!deliverable.hasRecipients()) {
            // Info rather than debug, and unlike the empty-recipients case above it
            // IS worth a line: "the customer says they never got the receipt" has to
            // be answerable, and the answer is usually here. The per-address reason
            // is logged by EmailEligibility; this records that the message died as a
            // whole, and the subject is enough to identify which one.
            log.info("Dropped a {} email \"{}\" - none of its {} recipient(s) are eligible.",
                    message.kind(), message.subject(), message.to().size());
            return;
        }
        if (deliverable.to().size() < message.to().size()) {
            log.debug("Sending a {} email \"{}\" to {} of {} recipient(s); the rest are ineligible.",
                    message.kind(), message.subject(), deliverable.to().size(), message.to().size());
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            emailSender.sendAsync(deliverable);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailSender.sendAsync(deliverable);
            }
        });
    }

    /**
     * Belt and braces around the one thing this class must never do, which is break
     * its caller. Every dispatch site sits in the middle of a business method that
     * has already done the real work, and the rendering step just before it reads
     * entity state that a template could, one refactor from now, dereference
     * carelessly. Wrapping the call in the caller's method would put that
     * try/catch in nine places; putting it here puts it in one.
     *
     * <p>{@link EmailSender#send} already swallows everything SES can throw. This
     * catches what happens <em>before</em> SES - a template with a null it did not
     * expect - which is the failure mode that would otherwise reach a customer as a
     * 500 on a checkout that had already succeeded.
     */
    public void dispatchQuietly(java.util.function.Supplier<EmailMessage> render) {
        try {
            dispatch(render.get());
        } catch (Exception e) {
            log.warn("Failed to render or dispatch an email: {}", e.getMessage(), e);
        }
    }
}
