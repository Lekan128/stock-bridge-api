package com.procurepal_services.stock_bridge_api.email;

import com.procurepal_services.stock_bridge_api.repository.ClientRepository;
import com.procurepal_services.stock_bridge_api.repository.EmailSuppressionRepository;
import com.procurepal_services.stock_bridge_api.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Answers one question and only one: <em>may this address receive mail of this
 * kind?</em> Everything about who a message is addressed to lives in
 * {@link EmailRecipients}; everything about whether those addresses are allowed to
 * hear from us lives here. The two are separate because they change for different
 * reasons - adding a recipient to order mail is a product decision, and suppressing
 * a bounced address is a deliverability one, and neither should be able to quietly
 * become the other.
 *
 * <h2>What this is defending against</h2>
 * SES scores a sending domain on bounce and complaint rate, and an account that
 * crosses the threshold is suspended. A suspension does not degrade gracefully: it
 * takes order receipts, payment confirmations and account mail down together, for
 * every tenant at once, and getting reinstated is a conversation with AWS rather
 * than a deploy. So the rule is that an address gets ordinary mail only once
 * somebody has demonstrated they can read it.
 *
 * <h2>THE RULE, in order. First match wins.</h2>
 * <ol>
 *   <li><strong>The address is suppressed.</strong> If it is on the
 *       {@code email_suppressions} list, nothing is ever sent to it - not
 *       transactional mail, not a verification link, not a security notice. This
 *       rule is first, <em>ahead of the bypass below</em>, and that ordering is
 *       argued for in its own section further down.</li>
 *   <li><strong>Nothing to send to.</strong> A null, blank or implausible address
 *       is ineligible. Not a policy decision - there is no inbox.</li>
 *   <li><strong>The kind bypasses the check.</strong> {@link EmailKind#VERIFICATION}
 *       and {@link EmailKind#SECURITY} are eligible for every address, full stop.
 *       See {@link EmailKind} for why; the short version is that gating the mail
 *       that sets the verified flag makes the flag unsettable, and withholding a
 *       "your password was changed" notice protects nobody.</li>
 *   <li><strong>A user row owns the address.</strong> If any {@code users} row
 *       carries this address - as {@code email} or as {@code username}, since a
 *       tenant's first user signs up with an address AS their username - then those
 *       rows are <em>authoritative</em> and no later rule is consulted.
 *       TRANSACTIONAL needs at least one of them verified; PROMOTIONAL needs that
 *       and additionally that none of them has unsubscribed. Asymmetric on
 *       purpose: verification is a claim about the inbox and one holder proving it
 *       proves it for everyone, whereas an unsubscribe is a request from the human
 *       reading that inbox and the human does not stop objecting because a second
 *       account shares their address.</li>
 *   <li><strong>A client's {@code admin_contact_email} owns it.</strong> Eligible
 *       for TRANSACTIONAL, never for PROMOTIONAL. This is the interesting case and
 *       it has its own section below.</li>
 *   <li><strong>A configured operator address.</strong> {@code
 *       app.email.operator-address} and {@code app.email.vendor-waitlist-address}
 *       are eligible for TRANSACTIONAL, never for PROMOTIONAL.</li>
 *   <li><strong>Anything else is ineligible</strong>, for every gated kind.</li>
 * </ol>
 *
 * <h2>clients.admin_contact_email - the seam this feature lives or dies on</h2>
 * Most email this application sends does not go to a user at all. {@code
 * EmailRecipients.forOrderBuyer}, {@code forClient} and {@code forOperator} all
 * resolve primarily to {@code clients.admin_contact_email} - the company's address
 * of record. That column has no verified flag, no consent flag, and critically no
 * <em>person</em>: nobody logs in as {@code accounts@acme.com}, so no verification
 * flow can ever be pointed at it. A feature that only understands user rows would
 * therefore silence the majority of ProcurePal's outgoing mail on the day it
 * shipped, which is a far worse outcome than the bounces it was written to prevent.
 *
 * <p>Note first that the common case never reaches rule 5 at all. At signup the
 * company's contact address and the account holder's username are the same string
 * (see {@code ClientSignupService}), so rule 4 finds the root user's row and that
 * user's flags decide - verify the account holder and the company's mail flows;
 * don't and it doesn't. That is the behaviour you would want, and it falls out of
 * ordering rule 4 before rule 5 rather than out of any special handling.
 *
 * <p>Rule 5 exists for the case where the two have been <em>decoupled</em>: an
 * OWNER with {@code MANAGE_COMPANY_PROFILE} has pointed the contact address at a
 * shared finance or operations inbox that belongs to no user. Such an address is
 * treated as eligible for transactional mail, and the justification is that it is
 * not an unknown address - it is one an authenticated account holder deliberately
 * nominated as the place this company's business correspondence should go. That is
 * a weaker signal than a clicked verification link and it is knowingly accepted,
 * because the alternative is refusing to send a company its own order receipts on
 * the grounds that it routes them to a shared mailbox, which is the arrangement
 * most real businesses use.
 *
 * <p>It is never eligible for promotional mail, and that asymmetry is the price of
 * the concession above. An address with no user row has no {@code
 * receive_promotional_email} to consult and therefore no way to opt out; marketing
 * to an inbox that cannot unsubscribe is precisely what RFC 8058 exists to stop,
 * and doing it would put the sending domain at exactly the risk this class is
 * supposed to reduce. Transactional mail carries no such duty - a company cannot
 * unsubscribe from being told what happened to an order it placed.
 *
 * <p>Two consequences worth knowing rather than discovering. First, the SES
 * bounce/complaint webhook can only demote addresses that have a user row to
 * demote; if a client's contact address starts hard-bouncing, flipping user flags
 * will not suppress it. <strong>That gap is now closed by rule 1.</strong> The SES
 * webhook writes to a standalone suppression list keyed by the lowercased address
 * itself rather than to a column on {@code clients}, precisely so that one mechanism
 * covers all three kinds of address this class knows about - a user's, a company's
 * contact of record, and the configured operator alias - including the two that have
 * no row to carry a flag. See {@code V10__email_suppression.sql} for why that shape
 * was chosen over adding columns, and {@code EmailSuppression} for what it costs.
 * Second, if a company ever nominates an
 * address that IS some other company's user's address, rule 4 wins and that user's
 * flags apply; the human at the keyboard is the same human either way, so the
 * stronger evidence should govern.
 *
 * <h2>Why suppression beats even VERIFICATION and SECURITY</h2>
 * Rule 1 sits in front of the bypass in rule 3, which means a suppressed address
 * does not get a password-change notice and cannot be sent a verification link. That
 * is the one place in this class where a rule overrides {@link EmailKind}'s promise
 * that those two always go out, so it has to be argued rather than asserted.
 *
 * <p>The bypass exists because gating verification mail makes the verified flag
 * unsettable, and because withholding a security notice protects nobody. Both
 * arguments assume something that a suppression has specifically disproved:
 * <em>that there is somebody at the other end</em>. A hard bounce is the receiving
 * server stating that the mailbox does not exist; a complaint is its owner asking us
 * to stop. Sending a verification link to an address that does not exist cannot
 * verify anything - it just earns a second bounce - and sending a security notice
 * there warns nobody. The bypass buys nothing on a suppressed address, so it gives
 * up nothing by yielding.
 *
 * <p>What it costs, on the other side, is the entire feature. Continuing to mail an
 * address the provider has declared permanently dead is the single most direct way
 * to damage a sending domain's reputation, and it is what SES measures. If a bypass
 * could override a suppression, then every module that sends VERIFICATION or
 * SECURITY mail would quietly reopen the hole this one exists to close, and the
 * suppression list would be advisory. Evidence from the provider outranks a policy
 * of ours, because the provider is the one keeping score.
 *
 * <p>The recovery path is deliberate rather than absent: an address gets off the
 * list through {@code EmailSuppressionService.unsuppress}, an operator action, and
 * being un-suppressed does not restore {@code is_email_verified} - the account
 * holder still has to prove the inbox works. A customer who fixes their mailbox and
 * asks support to re-enable mail is a support conversation with a clean answer,
 * which is a far better failure mode than a domain-wide SES suspension.
 *
 * <p><strong>One exception, and it is about not knowing rather than about
 * policy.</strong> If the suppression lookup itself fails - the database is briefly
 * unreachable - the answer depends on the kind. A gated kind is refused, matching
 * everything else in this class. A bypassing kind is allowed through, because rule 3
 * was written to keep working when the rest of the system is broken (that is what
 * lets a locked-out user learn their password changed during an incident), and
 * putting a database read in front of it must not quietly take that away. Note the
 * asymmetry precisely: known-suppressed always loses, unknown-because-broken loses
 * only for mail that was gated anyway.
 *
 * <h2>Why an unrecognised address is refused rather than waved through</h2>
 * Rule 7 is fail-closed, which is the opposite of the graceful degradation the rest
 * of the email package practises, so it needs justifying. Every address this
 * application legitimately sends to is accounted for by rules 4 to 6: it is a
 * user's, a company's, or the operator alias out of configuration. There is no
 * fourth legitimate source. An address that matches none of them therefore arrived
 * from something unintended - a stale render, a copy-paste into a template, a
 * future feature that forgot to register itself - and mailing strangers is the one
 * failure mode with no recovery path, because the damage lands on the sending
 * domain's reputation and is paid by every other tenant. Degrading gracefully here
 * would mean degrading into spam.
 *
 * <p>The operator address is called out explicitly in rule 6 for this reason: it is
 * a config value with no row anywhere, so under rule 7 alone ProcurePal's own ops
 * mail would have gone dark the day this deployed. An operator typing their own
 * inbox into their own environment is consent, and there is nobody else to ask.
 *
 * <h2>Why no method here is @Transactional</h2>
 * Identical hazard to {@link EmailRecipients}, and worth restating because this
 * class is newer and the temptation is fresher. Every call runs inside a caller's
 * transaction - an order being placed, a payment being applied. Annotating anything
 * here would put a Spring transaction boundary between that caller and this code,
 * and an exception crossing such a boundary marks the whole transaction
 * <em>rollback-only</em> permanently: {@link EmailDispatcher} would still absorb
 * the exception, the business method would still return successfully, and the
 * commit would then fail with {@code UnexpectedRollbackException}. A user with a
 * malformed address would fail somebody's payment. That has happened here before,
 * for exactly this reason, and the fix was removing the annotation.
 *
 * <p>The reads work fine without it - Spring Data repositories carry their own
 * transaction and join the caller's - so the annotation would buy nothing and cost
 * that.
 *
 * <h2>Never throws</h2>
 * Every public method catches. An unresolvable address, a database that is briefly
 * unavailable, anything at all: the result is a <em>decision</em>, and the decision
 * on failure is ineligible. Refusing to send is a lost notification; propagating
 * would be a lost transaction, per the section above. Note this means a database
 * blip suppresses mail rather than queueing it, which is the correct trade for a
 * channel where nothing downstream retries anyway.
 *
 * <h2>Cost</h2>
 * Between two and four indexed queries per recipient per message, run
 * synchronously on the caller's thread - one of them the suppression lookup, which
 * is a single-row existence check on a unique index and is now paid on every send
 * including the bypassing kinds that previously touched no database at all. Order mail has two recipients and the
 * common path is a single query (a verified user matches rule 4 immediately), so
 * this is single-digit milliseconds added to a checkout. It is on that path
 * deliberately: the alternative is doing it after the commit, on the async sender's
 * thread, where there is no Hibernate session to read from at all - see
 * {@link EmailDispatcher} for why the boundary is where it is.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailEligibility {

    private final UserRepository userRepository;
    private final ClientRepository clientRepository;
    private final EmailProperties emailProperties;

    /**
     * The suppression list, read directly rather than through
     * {@code EmailSuppressionService}.
     *
     * <p>That is not a layering slip, it is the same hazard the class doc describes
     * under "Why no method here is @Transactional", arriving by a different route.
     * Every write method on that service is {@code @Transactional}, so calling one
     * from here would join the caller's business transaction and put a Spring
     * transaction boundary between an order being placed and this code - and an
     * exception crossing it marks the whole thing rollback-only, failing somebody's
     * payment over an email lookup. The repository read has no such boundary: Spring
     * Data's own transaction simply joins the caller's. So this class depends on the
     * repository, on purpose, and must keep doing so.
     */
    private final EmailSuppressionRepository suppressionRepository;

    /**
     * The whole policy, for one address.
     *
     * @param address a recipient address; case and surrounding whitespace do not
     *     matter, and null or blank is simply ineligible
     * @param kind what the message is for; null is read as
     *     {@link EmailKind#TRANSACTIONAL}, matching {@link EmailMessage}'s own
     *     default so the two cannot disagree
     * @return true if the message may be addressed to this address
     */
    public boolean isEligible(String address, EmailKind kind) {
        EmailKind effectiveKind = kind == null ? EmailKind.TRANSACTIONAL : kind;
        String normalized = normalize(address);
        if (normalized == null) {
            return false;
        }

        // Rule 1, and deliberately ahead of the bypass below rather than beside the
        // other database rules. The provider has told us this address is dead or its
        // owner has reported us; no kind of message overrides that. See the class
        // doc for the full argument, including why this is the one rule that can
        // withhold a security notice.
        if (isSuppressed(normalized, effectiveKind)) {
            return false;
        }

        if (effectiveKind.bypassesVerification()) {
            // Rule 3. Still before any *policy* lookup: this is the branch
            // that must keep working when everything else is broken, because it is
            // the branch that lets a locked-out user find out their password
            // changed.
            return true;
        }

        try {
            return decide(normalized, effectiveKind);
        } catch (Exception e) {
            // See the class doc: a decision, never an exception. Warn rather than
            // error - a suppressed notification is not an incident, and this fires
            // once per recipient so an outage would page in a loop.
            log.warn("Could not resolve email eligibility for a {} message; treating the address as "
                    + "ineligible: {}", effectiveKind, e.getMessage());
            return false;
        }
    }

    /**
     * Rule 1: has the mail provider told us to stop sending here?
     *
     * <p>Separate from {@link #decide} because it runs for every kind, including the
     * two that never reach {@code decide} at all, and because its failure behaviour
     * is not {@code decide}'s. One indexed existence check on a unique column.
     *
     * <h2>The two failure directions, and why they differ</h2>
     * A database blip must not become a decision that harms somebody, but "harm"
     * points opposite ways for the two families of message:
     * <ul>
     *   <li>For a <strong>gated</strong> kind the safe answer is "suppressed", which
     *       withholds an order receipt. Consistent with every other read in this
     *       class - a database that cannot answer means no ordinary mail goes out.</li>
     *   <li>For a <strong>bypassing</strong> kind the safe answer is "not
     *       suppressed", which lets the verification or security message through.
     *       Rule 3 was written to keep working when everything else is broken, and
     *       the whole point of it is that a locked-out user finds out their password
     *       changed <em>during</em> an incident. Adding a database read in front of
     *       it and then failing closed on that read would silently repeal it.</li>
     * </ul>
     *
     * <p>Note what this asymmetry does <em>not</em> do: it never lets a
     * known-suppressed address receive anything. Suppression that we can see always
     * wins. This only governs the case where we cannot see, and there the cost of
     * withholding a security warning exceeds the cost of one more message to an
     * address that is probably fine.
     *
     * @return true if the address must receive nothing
     */
    private boolean isSuppressed(String address, EmailKind kind) {
        try {
            if (!suppressionRepository.existsByAddress(address)) {
                return false;
            }
            // INFO rather than DEBUG, unlike the other suppressing branches below:
            // this one can withhold a security notice, and "why did they never get
            // the password-reset email" needs an answer in the log.
            log.info("Suppressing a {} email: the address is on the suppression list, which overrides every "
                    + "kind including the bypassing ones. See email_suppressions for the reason.", kind);
            return true;
        } catch (Exception e) {
            boolean bypassing = kind.bypassesVerification();
            log.warn("Could not read the email suppression list; treating a {} message as {}: {}",
                    kind, bypassing ? "NOT suppressed" : "suppressed", e.getMessage());
            return !bypassing;
        }
    }

    /**
     * The same policy over a list, which is how {@link EmailDispatcher} uses it.
     *
     * <p>Filters rather than rejects: one ineligible address on a message must not
     * cost the eligible ones their copy. An order email addressed to a verified
     * company contact and an unverified colleague still reaches the company.
     *
     * @return the addresses that may be written into a {@code To:} header, in the
     *     order given, never null
     */
    public List<String> filterEligible(Collection<String> addresses, EmailKind kind) {
        if (addresses == null || addresses.isEmpty()) {
            return List.of();
        }
        return addresses.stream().filter(address -> isEligible(address, kind)).toList();
    }

    /**
     * Rules 4 through 7, in order, on an already-normalised address that is known
     * not to be suppressed, and a kind that is known to be gated.
     */
    private boolean decide(String address, EmailKind kind) {
        // Rule 4, asked in the order that answers most messages in one query: the
        // overwhelmingly common case is a verified user, and that is a single
        // indexed count.
        if (userRepository.countVerifiedMatchingEmailAddress(address) > 0) {
            if (!kind.requiresPromotionalConsent()) {
                return true;
            }
            // Verified, so the only remaining question is consent. Counted as
            // opt-outs so that ANY objection silences the address - see the
            // repository method for why that direction is the safe one.
            boolean optedOut = userRepository.countPromotionalOptOutsMatchingEmailAddress(address) > 0;
            if (optedOut) {
                log.debug("Suppressing a {} email: the address is verified but has unsubscribed.", kind);
            }
            return !optedOut;
        }

        // No verified user matched. Before falling through to the client rules, find
        // out whether an UNVERIFIED user owns this address - because if one does,
        // rule 4 is authoritative and the answer is no. Skipping this check would
        // let an unverified user whose company happens to use their address as its
        // contact of record receive mail anyway, which would make the verified flag
        // mean nothing for exactly the population it is aimed at: new signups.
        if (userRepository.countMatchingEmailAddress(address) > 0) {
            log.debug("Suppressing a {} email: the address belongs to a user who has not verified it.", kind);
            return false;
        }

        // Promotional mail stops here regardless of what follows. Rules 5 and 6 both
        // describe addresses with no user row, and therefore no
        // receive_promotional_email and no way to unsubscribe. See the class doc.
        if (kind.requiresPromotionalConsent()) {
            log.debug("Suppressing a {} email: the address has no user row and so no way to opt out.", kind);
            return false;
        }

        // Rule 5: a company's contact of record.
        if (clientRepository.countByLowercasedAdminContactEmail(address) > 0) {
            return true;
        }

        // Rule 6: an operator alias from configuration. Two of them now - the
        // fulfilment inbox copied on order and payment mail, and the vendor
        // waitlist inbox that new applications are announced to. Neither has a
        // users row or a clients row, so without this they would fall through to
        // rule 7 and ProcurePal would silently stop mailing itself. The waitlist
        // one matters more than it looks: it always has a value (see
        // EmailProperties), so omitting it here would drop every application
        // notification on every deploy rather than only on a misconfigured one.
        if (address.equals(normalize(emailProperties.operatorAddress()))
                || address.equals(normalize(emailProperties.vendorWaitlistAddress()))) {
            return true;
        }

        // Rule 7.
        log.info("Suppressing a {} email: the address matches no user, no company contact and no "
                + "configured operator address, so nothing in this system asked to mail it.", kind);
        return false;
    }

    /**
     * Lowercased and trimmed, or null when there is nothing usable. Matches what
     * {@link EmailMessage}'s compact constructor already does to every address, so
     * the string compared here is the string SES would be handed - if the two
     * normalisations ever diverged, this class would be making decisions about an
     * address other than the one being sent to.
     */
    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
