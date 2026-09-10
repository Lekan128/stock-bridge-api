package com.procurepal_services.stock_bridge_api.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.EmailSuppressionRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain Mockito over the two repositories, no Spring context - the same shape as
 * EmailSenderTest and for a related reason: EmailEligibility's contract is that it
 * always returns a decision and never throws, so every case here asserts on a
 * boolean and exactly one deliberately makes the database blow up to prove it stays
 * a boolean.
 *
 * <p>Mocking the repositories rather than standing up Postgres is the right level
 * here because what is being tested is the POLICY - which rule wins, in what order,
 * and what happens when none of them match. Whether the SQL finds the rows is a
 * different question and belongs to a query test; conflating them would mean the
 * interesting cases (an address that is nobody's, a company contact with no user
 * behind it) each need a fixture to exist before they can be asked about.
 */
class EmailEligibilityTest {

    private static final String ADDRESS = "buyer@acme.test";
    private static final String OPERATOR_ADDRESS = "ops@procurepal.test";
    private static final String VENDOR_WAITLIST_ADDRESS = "vendors@procurepal.test";

    private static final EmailProperties PROPERTIES = new EmailProperties(
            true, "us-east-1", "no-reply@procurepal.test", "ProcurePal",
            null, null, "https://app.procurepal.test", OPERATOR_ADDRESS,
            // The vendor-waitlist inbox, which rule 6 treats exactly like the
            // operator alias beside it. Given an explicit value here rather than
            // left null, because null would take EmailProperties' real default
            // (support@procurepaddy.com) and quietly make one production address
            // eligible in every test in this class.
            VENDOR_WAITLIST_ADDRESS);

    private UserRepository userRepository;
    private ClientRepository clientRepository;
    private EmailSuppressionRepository suppressionRepository;
    private EmailEligibility eligibility;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        clientRepository = mock(ClientRepository.class);
        suppressionRepository = mock(EmailSuppressionRepository.class);
        eligibility = new EmailEligibility(
                userRepository, clientRepository, PROPERTIES, suppressionRepository);
    }

    // ========================================================================
    // Rule 4: a user row owns the address, and is authoritative.
    // ========================================================================

    @Test
    void transactionalMailReachesAVerifiedUser() {
        givenUsersMatching(ADDRESS, 1, 1);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isTrue();
    }

    /**
     * The point of the whole feature. A user row exists but nobody has proved they
     * can read the address, so ordinary mail stops - and stops at rule 4, without
     * ever consulting the client rules below, which is what "authoritative" means.
     */
    @Test
    void transactionalMailIsRefusedForAnUnverifiedUser() {
        givenUsersMatching(ADDRESS, 1, 0);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
        verifyNoInteractions(clientRepository);
    }

    /**
     * Verification is a claim about the inbox: one holder proving they can read it
     * proves it for every account that shares it. Hence "any verified", not "all
     * verified" - the opposite direction to the unsubscribe test below, and the
     * asymmetry is deliberate.
     */
    @Test
    void oneVerifiedHolderIsEnoughEvenWhenOtherAccountsShareTheAddress() {
        givenUsersMatching(ADDRESS, 3, 1);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isTrue();
    }

    /** Case and whitespace are noise; the address is the same inbox either way. */
    @Test
    void normalisesTheAddressBeforeAskingAboutIt() {
        givenUsersMatching(ADDRESS, 1, 1);

        assertThat(eligibility.isEligible("  BUYER@Acme.TEST  ", EmailKind.TRANSACTIONAL)).isTrue();
        verify(userRepository).countVerifiedMatchingEmailAddress(ADDRESS);
    }

    // ========================================================================
    // Promotional consent.
    // ========================================================================

    @Test
    void promotionalMailReachesAVerifiedUserWhoHasNotUnsubscribed() {
        givenUsersMatching(ADDRESS, 1, 1);
        when(userRepository.countPromotionalOptOutsMatchingEmailAddress(ADDRESS)).thenReturn(0L);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.PROMOTIONAL)).isTrue();
    }

    /**
     * An unsubscribe is a request from the human reading the inbox, and that human
     * does not stop objecting because a second account happens to share the address.
     * So a single opt-out silences promotional mail even though two other verified
     * accounts on the same address never opted out.
     */
    @Test
    void oneUnsubscribeSilencesPromotionalMailForTheWholeAddress() {
        givenUsersMatching(ADDRESS, 3, 3);
        when(userRepository.countPromotionalOptOutsMatchingEmailAddress(ADDRESS)).thenReturn(1L);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.PROMOTIONAL)).isFalse();
    }

    /**
     * Verified AND opted-in, not either. A grandfathered row that never verified
     * still carries receive_promotional_email = TRUE from V8's column default, so
     * consent alone must not be enough or the migration would have opted the entire
     * existing customer base into marketing.
     */
    @Test
    void promotionalMailStillRequiresVerificationAndNotJustConsent() {
        givenUsersMatching(ADDRESS, 1, 0);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.PROMOTIONAL)).isFalse();
    }

    // ========================================================================
    // Rule 3: the kinds that bypass.
    // ========================================================================

    /**
     * The deadlock this enum exists to break: an address must be able to receive the
     * mail that verifies it, or the flag can never be set on anyone. Note the
     * repositories are never even touched - this branch has to work when the
     * database does not.
     */
    @Test
    void verificationMailReachesAnUnverifiedAddress() {
        assertThat(eligibility.isEligible(ADDRESS, EmailKind.VERIFICATION)).isTrue();

        verifyNoInteractions(userRepository);
        verifyNoInteractions(clientRepository);
    }

    /**
     * "Your password was changed" is worth sending precisely when it is unwelcome,
     * and a half-set-up unverified account is if anything the likelier one to have
     * been taken over. Gating it would withhold the only warning from the users most
     * likely to need it.
     */
    @Test
    void securityMailReachesAnUnverifiedAddress() {
        assertThat(eligibility.isEligible(ADDRESS, EmailKind.SECURITY)).isTrue();

        verifyNoInteractions(userRepository);
    }

    /** A bypassing kind still cannot conjure an inbox out of nothing. */
    @Test
    void evenBypassingKindsNeedSomewhereToGo() {
        assertThat(eligibility.isEligible(null, EmailKind.VERIFICATION)).isFalse();
        assertThat(eligibility.isEligible("   ", EmailKind.SECURITY)).isFalse();
    }

    // ========================================================================
    // Rule 5: clients.admin_contact_email - the seam.
    // ========================================================================

    /**
     * The case the whole class is built around. A company has pointed its contact of
     * record at a shared finance inbox that belongs to no user, so there is no
     * verified flag to read and no person who could ever click a link. Refusing this
     * would take order receipts dark for the arrangement most real businesses use;
     * an authenticated OWNER nominating the address is the weaker evidence that is
     * knowingly accepted in its place.
     */
    @Test
    void transactionalMailReachesACompanyContactAddressWithNoUserBehindIt() {
        String sharedInbox = "accounts@acme.test";
        givenUsersMatching(sharedInbox, 0, 0);
        when(clientRepository.countByLowercasedAdminContactEmail(sharedInbox)).thenReturn(1L);

        assertThat(eligibility.isEligible(sharedInbox, EmailKind.TRANSACTIONAL)).isTrue();
    }

    /**
     * The price of the concession above. That address has no
     * receive_promotional_email and therefore no way to unsubscribe, and marketing
     * to an inbox that cannot opt out is what RFC 8058 exists to prevent - so the
     * company contact seam is transactional-only, permanently.
     */
    @Test
    void promotionalMailNeverReachesACompanyContactAddress() {
        String sharedInbox = "accounts@acme.test";
        givenUsersMatching(sharedInbox, 0, 0);

        assertThat(eligibility.isEligible(sharedInbox, EmailKind.PROMOTIONAL)).isFalse();
        // Not even asked: the answer cannot change the outcome.
        verify(clientRepository, never()).countByLowercasedAdminContactEmail(anyString());
    }

    /**
     * Ordering, which is where the common case actually lives. At signup a company's
     * contact address IS its account holder's username, so an unverified root user
     * must silence the company's mail even though the clients table would happily
     * claim the same string. If rule 5 were consulted first, the verified flag would
     * mean nothing for precisely the population it targets - new signups.
     */
    @Test
    void anUnverifiedUserBeatsAMatchingCompanyContactAddress() {
        givenUsersMatching(ADDRESS, 1, 0);
        when(clientRepository.countByLowercasedAdminContactEmail(ADDRESS)).thenReturn(1L);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
    }

    // ========================================================================
    // Rules 6 and 7.
    // ========================================================================

    /**
     * app.email.operator-address is a config value with no row anywhere, so under
     * rule 7 alone ProcurePal's own ops mail would have gone dark the day this
     * shipped. An operator typing their own inbox into their own environment is
     * consent, and there is nobody else to ask.
     */
    @Test
    void theConfiguredOperatorAddressIsEligibleWithoutAnyRow() {
        givenUsersMatching(OPERATOR_ADDRESS, 0, 0);
        when(clientRepository.countByLowercasedAdminContactEmail(OPERATOR_ADDRESS)).thenReturn(0L);

        assertThat(eligibility.isEligible(OPERATOR_ADDRESS, EmailKind.TRANSACTIONAL)).isTrue();
    }

    /**
     * Fail-closed, which is the opposite of the graceful degradation the rest of the
     * email package practises and is deliberate. Every address this application
     * legitimately sends to is a user's, a company's, or the operator alias; one
     * matching none of them arrived from something unintended, and mailing strangers
     * is the one failure whose cost lands on every other tenant's deliverability.
     */
    @Test
    void anAddressThatMatchesNothingAtAllIsRefused() {
        String stranger = "someone@nowhere.test";
        givenUsersMatching(stranger, 0, 0);
        when(clientRepository.countByLowercasedAdminContactEmail(stranger)).thenReturn(0L);

        assertThat(eligibility.isEligible(stranger, EmailKind.TRANSACTIONAL)).isFalse();
        assertThat(eligibility.isEligible(stranger, EmailKind.PROMOTIONAL)).isFalse();
    }

    // ========================================================================
    // The never-throws contract.
    // ========================================================================

    /**
     * The single most important test here. This runs inside a caller's transaction -
     * an order being placed, a payment being applied - and an exception escaping it
     * would mark that transaction rollback-only, turning a database blip into a
     * failed payment. That is not hypothetical: it is what a @Transactional
     * EmailRecipients did here once. Ineligible is the safe answer, and it is an
     * answer rather than a throw.
     */
    @Test
    void degradesToIneligibleRatherThanThrowingWhenTheDatabaseFails() {
        when(userRepository.countVerifiedMatchingEmailAddress(anyString()))
                .thenThrow(new RuntimeException("connection pool exhausted"));

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /** Null means TRANSACTIONAL, matching EmailMessage's own default so the two cannot disagree. */
    @Test
    void treatsAnAbsentKindAsTransactional() {
        givenUsersMatching(ADDRESS, 1, 0);

        assertThat(eligibility.isEligible(ADDRESS, null)).isFalse();
        verify(userRepository).countVerifiedMatchingEmailAddress(ADDRESS);
    }

    // ========================================================================
    // filterEligible: the form EmailDispatcher actually calls.
    // ========================================================================

    /**
     * Filters rather than rejects. One unverified colleague copied on an order email
     * must not cost the company its receipt - which is the same reasoning
     * EmailMessage applies to a malformed address, arriving by a different route.
     */
    @Test
    void keepsTheEligibleRecipientsAndDropsTheRest() {
        givenUsersMatching("finance@acme.test", 1, 1);
        givenUsersMatching("newhire@acme.test", 1, 0);

        List<String> kept = eligibility.filterEligible(
                List.of("finance@acme.test", "newhire@acme.test"), EmailKind.TRANSACTIONAL);

        assertThat(kept).containsExactly("finance@acme.test");
    }

    @Test
    void returnsAnEmptyListRatherThanNullForNothingToFilter() {
        assertThat(eligibility.filterEligible(null, EmailKind.TRANSACTIONAL)).isEmpty();
        assertThat(eligibility.filterEligible(List.of(), EmailKind.TRANSACTIONAL)).isEmpty();
        assertThat(eligibility.filterEligible(Arrays.asList((String) null), EmailKind.TRANSACTIONAL))
                .isEmpty();
    }

    /**
     * @param matching how many user rows carry the address at all
     * @param verified how many of those have verified it
     */
    private void givenUsersMatching(String address, long matching, long verified) {
        when(userRepository.countMatchingEmailAddress(eq(address))).thenReturn(matching);
        when(userRepository.countVerifiedMatchingEmailAddress(eq(address))).thenReturn(verified);
    }

    // ========================================================================
    // Rule 1: the suppression list. Module D.
    // ========================================================================

    /**
     * The ordinary case: the provider told us this address is dead, so no ordinary
     * mail goes to it. Note nothing else is consulted - suppression is checked before
     * any of the user or client rules, so a suppressed address costs one query and
     * stops.
     */
    @Test
    void suppressedAddressesGetNoTransactionalMail() {
        givenSuppressed(ADDRESS);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
        verifyNoInteractions(userRepository);
        verifyNoInteractions(clientRepository);
    }

    @Test
    void suppressedAddressesGetNoPromotionalMail() {
        givenSuppressed(ADDRESS);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.PROMOTIONAL)).isFalse();
    }

    /**
     * The ordering decision this module had to make, and the one most worth guarding
     * with a test. VERIFICATION and SECURITY bypass every other check in this class -
     * deliberately, so that the flag is settable and so that a locked-out user learns
     * their password changed. Suppression still beats both.
     *
     * <p>The justification is that the bypass assumes something a suppression has
     * specifically disproved: that somebody is at the other end. A verification link
     * sent to a mailbox that does not exist cannot verify anything, it just earns a
     * second bounce - and bounces to known-dead addresses are exactly what costs a
     * sending domain its reputation, which is the shared asset every tenant here
     * depends on. Evidence from the provider outranks a policy of ours.
     */
    @Test
    void suppressionBeatsEvenTheBypassingKinds() {
        givenSuppressed(ADDRESS);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.VERIFICATION)).isFalse();
        assertThat(eligibility.isEligible(ADDRESS, EmailKind.SECURITY)).isFalse();
    }

    /**
     * A verified user is still refused while their address is suppressed. Without
     * this ordering the flags on the users row - which say "this address worked when
     * we last checked" - would override the provider telling us it has since stopped
     * working, and the suppression list would be advisory.
     */
    @Test
    void suppressionBeatsAVerifiedUserRow() {
        givenSuppressed(ADDRESS);
        givenUsersMatching(ADDRESS, 1, 1);

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /**
     * The gap this whole module exists to close, asserted from the eligibility side.
     * A company's contact of record is a shared inbox with no users row behind it, so
     * there is no verified flag to clear and no person who could ever click a link -
     * which is why V8 left it as a known gap and why the fix had to be a list keyed by
     * the address itself rather than a column on any table. Rule 5 would otherwise
     * wave this address through for transactional mail forever, however hard it
     * bounces.
     */
    @Test
    void suppressionCoversACompanyContactAddressWithNoUserRow() {
        String sharedInbox = "accounts@acme.test";
        givenUsersMatching(sharedInbox, 0, 0);
        when(clientRepository.countByLowercasedAdminContactEmail(sharedInbox)).thenReturn(1L);

        // Before suppression: eligible, on the strength of an OWNER having nominated it.
        assertThat(eligibility.isEligible(sharedInbox, EmailKind.TRANSACTIONAL)).isTrue();

        givenSuppressed(sharedInbox);

        assertThat(eligibility.isEligible(sharedInbox, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /** The configured operator alias has no row anywhere either, and is covered the same way. */
    @Test
    void suppressionCoversTheConfiguredOperatorAddress() {
        givenSuppressed(OPERATOR_ADDRESS);

        assertThat(eligibility.isEligible(OPERATOR_ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
    }

    /** The list is keyed by the normalised address, so the lookup must normalise too. */
    @Test
    void looksUpTheSuppressionListWithANormalisedAddress() {
        givenSuppressed(ADDRESS);

        assertThat(eligibility.isEligible("  BUYER@Acme.TEST  ", EmailKind.TRANSACTIONAL)).isFalse();
        verify(suppressionRepository).existsByAddress(ADDRESS);
    }

    /**
     * A database blip must not silence a security notice. Rule 3 was written to keep
     * working when everything else is broken - that is what lets a locked-out user
     * find out their password changed during an incident - and putting a suppression
     * read in front of it must not quietly repeal that.
     *
     * <p>Note the asymmetry against the test below: known-suppressed always loses,
     * unknown-because-broken loses only for mail that was gated anyway.
     */
    @Test
    void anUnreadableSuppressionListStillLetsSecurityMailThrough() {
        when(suppressionRepository.existsByAddress(anyString()))
                .thenThrow(new RuntimeException("connection pool exhausted"));

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.SECURITY)).isTrue();
        assertThat(eligibility.isEligible(ADDRESS, EmailKind.VERIFICATION)).isTrue();
    }

    /** ...while a gated kind fails closed, consistent with every other read in the class. */
    @Test
    void anUnreadableSuppressionListRefusesOrdinaryMail() {
        when(suppressionRepository.existsByAddress(anyString()))
                .thenThrow(new RuntimeException("connection pool exhausted"));

        assertThat(eligibility.isEligible(ADDRESS, EmailKind.TRANSACTIONAL)).isFalse();
        assertThat(eligibility.isEligible(ADDRESS, EmailKind.PROMOTIONAL)).isFalse();
    }

    /** filterEligible drops the suppressed address and keeps the rest. */
    @Test
    void filteringRemovesOnlyTheSuppressedAddresses() {
        String healthy = "healthy@acme.test";
        givenSuppressed(ADDRESS);
        givenUsersMatching(healthy, 1, 1);

        assertThat(eligibility.filterEligible(List.of(ADDRESS, healthy), EmailKind.TRANSACTIONAL))
                .containsExactly(healthy);
    }

    private void givenSuppressed(String address) {
        when(suppressionRepository.existsByAddress(address)).thenReturn(true);
    }
}
