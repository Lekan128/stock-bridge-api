package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Drives the synchronization machinery by hand rather than standing up a database.
 * {@code initSynchronization()} is exactly what a real transaction manager does when
 * a transaction begins, so registering against it here exercises the same code path
 * a committing order does - and lets a test assert the one thing that matters most,
 * which is what happens when the commit never comes.
 *
 * <p>EmailEligibility is mocked permissive by default so the timing tests below stay
 * about timing. The gating tests at the bottom are the ones that make it say no, and
 * they are here rather than in EmailEligibilityTest on purpose: the policy is tested
 * there, and what is tested here is that the dispatcher consults it BEFORE the
 * afterCommit boundary - which is the property that cannot be recovered if it is
 * wrong, since past that boundary there is no database session to ask.
 */
class EmailDispatcherTest {

    private static final EmailMessage MESSAGE = new EmailMessage(
            List.of("buyer@example.com"), "Order PP-1 confirmed", "<p>Hi</p>", "Hi");

    private EmailSender emailSender;
    private EmailEligibility eligibility;
    private EmailDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        emailSender = mock(EmailSender.class);
        eligibility = mock(EmailEligibility.class);
        // Everyone is eligible unless a test says otherwise, so the timing tests
        // read as they did before per-recipient gating existed.
        when(eligibility.filterEligible(anyCollection(), any()))
                .thenAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
        dispatcher = new EmailDispatcher(emailSender, eligibility);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void holdsTheEmailUntilTheTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(MESSAGE);
        verify(emailSender, never()).sendAsync(any());

        commit();
        verify(emailSender).sendAsync(MESSAGE);
    }

    /**
     * The reason this class exists. A customer must never hold a receipt for an
     * order the database rolled back - and unlike an in-app notification, an email
     * cannot be taken back once SES has it.
     */
    @Test
    void neverSendsWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(MESSAGE);
        rollback();

        verify(emailSender, never()).sendAsync(any());
    }

    /**
     * A scheduled sweep or any other non-transactional caller has nothing to wait
     * for. Registering a synchronization there throws, so the branch matters.
     */
    @Test
    void sendsImmediatelyWhenThereIsNoTransaction() {
        dispatcher.dispatch(MESSAGE);

        verify(emailSender).sendAsync(MESSAGE);
    }

    @Test
    void dropsAMessageWithNobodyToSendTo() {
        EmailMessage nobody = new EmailMessage(List.of(), "Subject", "<p>Hi</p>", "Hi");

        dispatcher.dispatch(nobody);
        dispatcher.dispatch(null);

        verify(emailSender, never()).sendAsync(any());
    }

    /**
     * A template dereferencing something unexpected must cost the caller nothing -
     * the business work it is attached to has already succeeded by the time this
     * runs.
     */
    @Test
    void absorbsAFailureWhileRenderingRatherThanFailingTheCaller() {
        assertThatNoException().isThrownBy(() -> dispatcher.dispatchQuietly(() -> {
            throw new IllegalStateException("template blew up");
        }));

        verify(emailSender, never()).sendAsync(any());
    }

    @Test
    void dispatchesQuietlyOnTheHappyPath() {
        dispatcher.dispatchQuietly(() -> MESSAGE);

        verify(emailSender).sendAsync(MESSAGE);
    }

    /**
     * Guards the ordering promise: the send is registered to run after the commit,
     * not merely after the business method returns. If the callback were registered
     * as beforeCommit this would still pass the "holds until commit" test above and
     * would still be wrong.
     */
    @Test
    void registersExactlyOneAfterCommitCallback() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(MESSAGE);

        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).hasSize(1);

        registered.getFirst().beforeCommit(false);
        verify(emailSender, never()).sendAsync(any());

        registered.getFirst().afterCommit();
        verify(emailSender).sendAsync(MESSAGE);
    }

    /**
     * The gate itself. An ineligible sole recipient means the message never reaches
     * SES at all - not an empty send, not a send to nobody.
     */
    @Test
    void dropsAMessageWhoseOnlyRecipientIsIneligible() {
        when(eligibility.filterEligible(anyCollection(), any())).thenReturn(List.of());

        dispatcher.dispatch(MESSAGE);

        verify(emailSender, never()).sendAsync(any());
    }

    /**
     * Filters, rather than rejects. One unverified colleague copied on an order
     * email must not cost the company its receipt - the same failure EmailMessage's
     * address validation exists to prevent, arriving by a different route.
     */
    @Test
    void sendsToTheEligibleSubsetWhenOnlySomeRecipientsPass() {
        EmailMessage toBoth = new EmailMessage(
                List.of("finance@acme.test", "newhire@acme.test"), "Order PP-1 confirmed", "<p>Hi</p>", "Hi");
        when(eligibility.filterEligible(anyCollection(), any())).thenReturn(List.of("finance@acme.test"));

        dispatcher.dispatch(toBoth);

        verify(emailSender).sendAsync(toBoth.withRecipients(List.of("finance@acme.test")));
    }

    /**
     * The ordering promise that the whole design turns on. EmailSender.sendAsync
     * runs after the commit, on another thread, with no Hibernate session - so if
     * eligibility were consulted there it could not read the users table at all.
     * This asserts the decision is already made while the caller's transaction is
     * still open, before a single synchronization has fired.
     */
    @Test
    void decidesEligibilityBeforeTheAfterCommitBoundary() {
        TransactionSynchronizationManager.initSynchronization();

        dispatcher.dispatch(MESSAGE);

        verify(eligibility).filterEligible(anyCollection(), any());
        verify(emailSender, never()).sendAsync(any());

        commit();
        verify(emailSender).sendAsync(MESSAGE);
    }

    /**
     * An ineligible message must not register a synchronization either. Registering
     * one that does nothing would still be a leak of sorts - the callback list grows
     * per suppressed message - and, more importantly, would mean the dispatcher had
     * deferred the decision rather than made it.
     */
    @Test
    void registersNoCallbackAtAllForAMessageThatIsFullyGated() {
        TransactionSynchronizationManager.initSynchronization();
        when(eligibility.filterEligible(anyCollection(), any())).thenReturn(List.of());

        dispatcher.dispatch(MESSAGE);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    /**
     * The kind has to survive the trip, because it is the only thing that tells
     * EmailEligibility whether the verified check applies. A dispatcher that passed
     * a hardcoded kind would pass every other test in this file.
     */
    @Test
    void asksAboutTheMessagesOwnKindRatherThanAssumingOne() {
        EmailMessage verification = new EmailMessage(
                List.of("newhire@acme.test"), "Confirm your address", "<p>Hi</p>", "Hi",
                EmailKind.VERIFICATION);

        dispatcher.dispatch(verification);

        verify(eligibility).filterEligible(anyCollection(), eq(EmailKind.VERIFICATION));
    }

    private static void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private static void rollback() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCompletion(
                        TransactionSynchronization.STATUS_ROLLED_BACK));
    }
}
