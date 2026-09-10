package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The address list is the only logic on this record, and all of it exists to stop
 * one bad entry costing every other recipient their copy - SES rejects the whole
 * SendEmail call, not just the offending destination.
 */
class EmailMessageTest {

    private static EmailMessage withRecipients(String... addresses) {
        return new EmailMessage(Arrays.asList(addresses), "Subject", "<p>Body</p>", "Body");
    }

    @Test
    void normalisesCaseAndWhitespace() {
        assertThat(withRecipients("  Ops@Example.COM  ").to()).containsExactly("ops@example.com");
    }

    @Test
    void collapsesDuplicatesThatDifferOnlyByCaseOrPadding() {
        assertThat(withRecipients("ops@example.com", "OPS@example.com", " ops@example.com ").to())
                .containsExactly("ops@example.com");
    }

    @Test
    void dropsImplausibleAddressesButKeepsTheRest() {
        assertThat(withRecipients("good@example.com", "not-an-address", "", "  ", "also@example.co.uk").to())
                .containsExactly("good@example.com", "also@example.co.uk");
    }

    /**
     * A username is used as a fallback address when a user has no contact email, and
     * sub-user usernames need not be addresses at all - so "warehouse-lead" reaching
     * this constructor is expected traffic, not a bug.
     */
    @Test
    void dropsAUsernameThatIsNotAnAddress() {
        assertThat(withRecipients("warehouse-lead").hasRecipients()).isFalse();
    }

    @Test
    void toleratesNullEntriesAndANullList() {
        assertThat(withRecipients("good@example.com", null).to()).containsExactly("good@example.com");
        assertThat(new EmailMessage(null, "Subject", null, null).hasRecipients()).isFalse();
    }

    @Test
    void reportsHavingNoRecipientsWhenEveryAddressWasDiscarded() {
        assertThat(withRecipients("nope", "also-nope").hasRecipients()).isFalse();
        assertThat(new EmailMessage(List.of(), "Subject", null, null).hasRecipients()).isFalse();
    }

    /**
     * The four-argument constructor is what every template and test written before
     * per-recipient eligibility uses, and it has to keep meaning what those call
     * sites already meant. TRANSACTIONAL is also the fail-safe direction: it is
     * gated, so a message that never states its purpose is checked rather than
     * waved through.
     */
    @Test
    void defaultsToTransactionalWhenNoKindIsStated() {
        assertThat(withRecipients("ops@example.com").kind()).isEqualTo(EmailKind.TRANSACTIONAL);
        assertThat(new EmailMessage(List.of(), "Subject", null, null, null).kind())
                .isEqualTo(EmailKind.TRANSACTIONAL);
    }

    /**
     * Narrowing the audience must not quietly change what the message IS - if the
     * kind were lost here, a verification email that had one of two recipients
     * filtered would come out the other side gated as transactional and be dropped
     * by the very check it exists to satisfy.
     */
    @Test
    void carriesTheKindThroughARecipientNarrowing() {
        EmailMessage verification = new EmailMessage(
                List.of("a@example.com", "b@example.com"), "Confirm your address", "<p>Hi</p>", "Hi",
                EmailKind.VERIFICATION);

        EmailMessage narrowed = verification.withRecipients(List.of("a@example.com"));

        assertThat(narrowed.to()).containsExactly("a@example.com");
        assertThat(narrowed.kind()).isEqualTo(EmailKind.VERIFICATION);
        assertThat(narrowed.subject()).isEqualTo(verification.subject());
    }

    /** No copy when nothing was removed, so the common path allocates nothing. */
    @Test
    void returnsItselfWhenNoRecipientWasRemoved() {
        EmailMessage message = withRecipients("ops@example.com");

        assertThat(message.withRecipients(message.to())).isSameAs(message);
        assertThat(message.withRecipients(null).to()).isEmpty();
    }
}
