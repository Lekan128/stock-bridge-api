package com.procurepal_services.stock_bridge_api.email.webhook;

import com.procurepal_services.stock_bridge_api.entity.EmailSuppression;
import com.procurepal_services.stock_bridge_api.entity.EmailSuppressionReason;
import com.procurepal_services.stock_bridge_api.repository.EmailSuppressionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything that <em>changes state</em> when the mail provider tells us something
 * about an address: the suppression list, and the two user flags that go with it.
 *
 * <p>Separated from {@link SesNotificationService}, which does the parsing, the
 * signature checking and the auditing, because these are the only operations in the
 * module that touch customer data and they need to be readable on their own. It is
 * also a transaction boundary that has to exist: the notification service is
 * deliberately not transactional (it must be able to write an audit row for work
 * that failed), so the writes need their own bean to be proxied.
 *
 * <h2>Why the cross-tenant writes are EntityManager statements here</h2>
 * Straight from {@code UnsubscribeService}, which solved the identical problem for
 * the identical shape of write - "clear a flag on every user row holding this
 * address, in every tenant" - and whose javadoc carries the long form of the
 * argument. Three precedents existed and this is the one that fits:
 * <ul>
 *   <li>Module A put tenancy-independent <em>reads</em> as native queries on
 *       {@code UserRepository}. Right for reads on the send path, which is ordinary
 *       tenant-scoped application code calling into a shared interface.</li>
 *   <li>Module B used {@code findById} for a single already-loaded row. There is no
 *       row id here - the input is a bare address string.</li>
 *   <li>Module C used an {@code EntityManager} native {@code UPDATE} inside its own
 *       service for a bulk cross-tenant write keyed by address. That is exactly
 *       this, so that is what is followed.</li>
 * </ul>
 *
 * <p>Native, not JPQL, because {@code User} is a {@code TenantAwareEntity}: JPQL and
 * derived queries carry Hibernate's tenant filter whenever it happens to be enabled,
 * and it is disabled on this thread only because the endpoint is unauthenticated.
 * Depending on that would make these statements silently change scope the first time
 * anything authenticated called them. A native statement is never rewritten by the
 * filter, so it behaves identically from every thread. {@code TenantScopeExecutor}
 * is the wrong tool for the reason its own javadoc gives - it re-points the filter
 * at ONE named client, and there is no client to name: an address may be held by
 * users of several tenants at once, by a company's contact of record, and by nobody.
 *
 * <p>{@code updated_at} is not set and must not be: {@code trg_users_set_updated_at}
 * (V1) maintains it on every UPDATE, including these. The bulk-update caveat also
 * applies - these bypass the persistence context, so a {@code User} already loaded
 * in this session would keep the stale flag. Nothing is loaded on this path, and if
 * that ever changes the read must happen before the update or not at all.
 *
 * <h2>Idempotent by construction</h2>
 * SNS delivers at least once and {@link SesNotificationService} already refuses to
 * reprocess a message id, but nothing here relies on that. The suppression write is
 * an upsert onto a uniquely-constrained address, and both flag updates are
 * set-to-a-constant with a {@code WHERE flag = TRUE} guard, so running any of this
 * twice matches zero rows the second time and produces the same end state. Two
 * independent mechanisms for one property is right here: the dedupe is what makes
 * the audit log honest, and this is what makes the data correct even if the dedupe
 * is ever wrong.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailSuppressionService {

    /**
     * Matches {@code username} as well as {@code email} for the reason every address
     * lookup in this codebase does: a tenant's first user signs up with an email
     * address AS their username and {@code users.email} is frequently never filled
     * in, so matching {@code email} alone would miss the account holder of most
     * companies on the platform - who is precisely the person whose address a
     * company's mail goes to.
     */
    private static final String UNVERIFY_SQL =
            "UPDATE users SET is_email_verified = FALSE "
                    + "WHERE is_email_verified = TRUE "
                    + "AND (lower(email) = :address OR lower(username) = :address)";

    /**
     * The same flag {@code UnsubscribeService} clears, and that is not a
     * coincidence: pressing "report spam" and pressing "unsubscribe" are the same
     * request from the same human through two different channels. The only
     * difference is that one of them also told our mail provider.
     */
    private static final String OPT_OUT_SQL =
            "UPDATE users SET receive_promotional_email = FALSE "
                    + "WHERE receive_promotional_email = TRUE "
                    + "AND (lower(email) = :address OR lower(username) = :address)";

    @PersistenceContext
    private EntityManager entityManager;

    private final EmailSuppressionRepository suppressionRepository;
    private final SesWebhookProperties properties;

    /**
     * A hard bounce: SES reported {@code bounceType: "Permanent"}, meaning the
     * receiving server has said definitively that this address does not accept mail.
     *
     * <p>Does two things, and needs both. The suppression row is what stops mail to
     * an address with no user row behind it - a {@code clients.admin_contact_email}
     * pointed at a shared finance inbox, or {@code app.email.operator-address},
     * neither of which has a flag to clear. Clearing {@code is_email_verified} is
     * what stops mail to the addresses that do have user rows, and it matters
     * independently: if the suppression is ever lifted without the address being
     * re-verified, rule 3 in {@code EmailEligibility} still refuses it. Doing only
     * the first would leave a verified flag asserting something the provider has
     * just disproved.
     *
     * @param rawAddress the bounced recipient, in whatever case SES sent it
     * @param sourceMessageId the SNS MessageId, for the audit trail back to the
     *     notification
     * @param diagnostic SES's {@code diagnosticCode} - the remote server's own words
     * @return how many user rows were demoted from verified; zero is completely
     *     ordinary and means the address belongs to a company contact or the
     *     operator alias rather than to an account
     */
    @Transactional
    public int suppressPermanentBounce(String rawAddress, String sourceMessageId, String diagnostic) {
        String address = normalize(rawAddress);
        if (address == null) {
            return 0;
        }
        upsertSuppression(address, EmailSuppressionReason.PERMANENT_BOUNCE, sourceMessageId, diagnostic);
        int demoted = executeUpdate(UNVERIFY_SQL, address);
        log.info("Permanent bounce: suppressed an address and cleared is_email_verified on {} user row(s). "
                + "Diagnostic: {}", demoted, diagnostic);
        return demoted;
    }

    /**
     * A spam complaint: the recipient pressed "mark as spam" and their provider
     * relayed a feedback-loop report to SES.
     *
     * <h2>The judgement call, and why it defaults the way it does</h2>
     * Whether a complaint should stop <em>all</em> mail or only marketing is the one
     * genuinely contested decision in this module, so here is the argument in full.
     *
     * <p><strong>The case for promotional only</strong> is the one
     * {@code UnsubscribeService} makes, and it is a good one: a customer cannot opt
     * out of the receipt for goods they have paid for. That mail is part of the
     * transaction rather than an approach to them, in places it is the record of a
     * sale, and withholding it means somebody spends money and is told nothing.
     *
     * <p><strong>The case for all mail</strong> - which is what this defaults to -
     * turns on two differences from an unsubscribe. First, an unsubscribe is
     * <em>scoped by the mechanism it came through</em>: the button says "unsubscribe
     * from marketing", so honouring exactly that is honouring the request. A
     * complaint carries no such scope. The reader pressed a button that means "this
     * sender is spam", and nothing in the feedback report says which of our messages
     * they meant - SES commonly redacts the original entirely. Deciding on their
     * behalf that they only meant the marketing is us choosing the reading that
     * suits us. Second, and decisively, the two have different <em>victims</em> when
     * we get it wrong. An unsubscribe we honour too narrowly annoys one person. A
     * complaint we honour too narrowly is scored by AWS against a sending domain
     * that every tenant on this platform shares: SES puts an account under review
     * above roughly 0.1% complaints and suspends above 0.5%, and a suspension takes
     * order receipts, payment confirmations and account mail down for every customer
     * of every tenant at once. Continuing to mail somebody who has already reported
     * us is the single most reliable way to earn the next complaint from them.
     *
     * <p>So the default is the one whose failure mode is bounded: one customer must
     * ask an operator to lift a suppression, rather than every customer losing mail
     * because the domain was suspended. {@code
     * app.email.sns.complaints-suppress-all-mail=false} takes the other side, and is
     * a legitimate choice for a deployment whose transactional mail is
     * legally load-bearing - it is a decision to be made deliberately, which is why
     * it is a flag and not a constant.
     *
     * <h2>not-spam is not a complaint</h2>
     * Gmail sends a feedback report with {@code complaintFeedbackType: "not-spam"}
     * when a user moves one of our messages <em>out</em> of their spam folder. That
     * is the opposite signal, and treating it as a complaint would punish a sender
     * for a recipient's endorsement - silencing exactly the customers who went out
     * of their way to say the mail was wanted. Callers must not route it here; see
     * {@link SesNotificationService}, which filters it before this is reached. It is
     * guarded again here anyway, because a rule this counter-intuitive should not
     * depend on one caller remembering it.
     *
     * @param feedbackType SES's {@code complaintFeedbackType}, possibly null
     * @return how many user rows were opted out of promotional mail
     */
    @Transactional
    public int recordComplaint(String rawAddress, String sourceMessageId, String feedbackType) {
        String address = normalize(rawAddress);
        if (address == null) {
            return 0;
        }
        if (isNotSpamEndorsement(feedbackType)) {
            log.info("Ignoring an SES feedback report of type 'not-spam' - it means a reader rescued our mail "
                    + "from their spam folder, which is the opposite of a complaint.");
            return 0;
        }

        int optedOut = executeUpdate(OPT_OUT_SQL, address);

        if (properties.complaintsSuppressAllMail()) {
            upsertSuppression(address, EmailSuppressionReason.COMPLAINT, sourceMessageId,
                    "Spam complaint" + (feedbackType == null ? "" : " (" + feedbackType + ")"));
            log.info("Complaint: suppressed all mail to the address and opted {} user row(s) out of promotional "
                    + "email. Set app.email.sns.complaints-suppress-all-mail=false to keep sending "
                    + "transactional mail to complainants.", optedOut);
        } else {
            log.info("Complaint: opted {} user row(s) out of promotional email. Transactional mail is "
                    + "UNAFFECTED because app.email.sns.complaints-suppress-all-mail is false.", optedOut);
        }
        return optedOut;
    }

    /**
     * An operator putting an address on the list by hand - a known-bad domain, a
     * role account that should never have been mailed, a customer who asked by
     * phone.
     *
     * <p>Nothing calls this over HTTP, deliberately. An endpoint that suppresses an
     * arbitrary address is an endpoint for silencing an arbitrary customer, and it
     * would need an authorization model, an audit trail and a UI that this module
     * has no requirement for. It exists as a method because the alternative - an
     * operator hand-writing an INSERT - is how the {@code address = lower(address)}
     * CHECK gets violated at three in the morning, and because it is the natural
     * seam for a future admin screen to call.
     */
    @Transactional
    public void suppressManually(String rawAddress, String diagnostic) {
        String address = normalize(rawAddress);
        if (address == null) {
            return;
        }
        upsertSuppression(address, EmailSuppressionReason.MANUAL, null, diagnostic);
        log.info("An address was suppressed manually. Reason given: {}", diagnostic);
    }

    /**
     * Takes an address off the list.
     *
     * <h2>Why an un-suppress path exists at all</h2>
     * Because a suppression is evidence about an inbox at a moment in time, and
     * inboxes get fixed. A mailbox over quota gets emptied, a lapsed domain gets
     * renewed, a typo'd address gets corrected and re-verified, an operator
     * suppresses the wrong string. Without this, every one of those is permanent and
     * the only remedy is hand-written SQL against production - which is both the
     * riskiest way to do it and the one that leaves no trace.
     *
     * <h2>What it deliberately does NOT do</h2>
     * It does not restore {@code is_email_verified}. A permanent bounce cleared that
     * flag because the provider disproved the address, and lifting the suppression
     * does not re-prove it - only somebody clicking a fresh verification link does,
     * which is module B's flow and the correct route back. So an address that is
     * un-suppressed but not re-verified is still refused by rule 3 in
     * {@code EmailEligibility}, and that ordering is intentional: un-suppressing is
     * "stop treating this as dead", not "trust this again".
     *
     * @return true if a row was removed, false if the address was not suppressed.
     *     The two need different words in a log line and an operator asking why
     *     nothing changed deserves the second one.
     */
    @Transactional
    public boolean unsuppress(String rawAddress) {
        String address = normalize(rawAddress);
        if (address == null) {
            return false;
        }
        boolean removed = suppressionRepository.deleteByAddress(address) > 0;
        log.info("Un-suppress request: {}. Note this does NOT restore is_email_verified - the address must be "
                + "re-verified before ordinary mail resumes.", removed ? "an address was removed from the "
                + "suppression list" : "the address was not on the suppression list, nothing changed");
        return removed;
    }

    // ------------------------------------------------------------------------

    /**
     * Upsert against the unique constraint on {@code address}, read-then-write
     * rather than an {@code ON CONFLICT} statement.
     *
     * <p>The native upsert would be one round trip instead of two and is not worth
     * it: this runs once per bounced recipient on a webhook nobody is waiting on,
     * and the read-then-write form is the one that keeps {@code created_at} meaning
     * "when this address was FIRST suppressed" while letting the reason and
     * diagnostic move to whatever the provider most recently said. A blind upsert
     * would either churn {@code created_at} or need an explicit exclusion, and "how
     * long has this been dead" is the question that actually gets asked.
     *
     * <p>The unique constraint is still what guarantees one row per address; this
     * method is the ordinary path, not the enforcement.
     */
    private void upsertSuppression(
            String address, EmailSuppressionReason reason, String sourceMessageId, String diagnostic) {
        Optional<EmailSuppression> existing = suppressionRepository.findByAddress(address);
        EmailSuppression suppression = existing.orElseGet(
                () -> EmailSuppression.builder().address(address).build());
        suppression.setReason(reason);
        suppression.setSourceMessageId(sourceMessageId);
        suppression.setDiagnostic(diagnostic);
        suppressionRepository.save(suppression);
    }

    private int executeUpdate(String sql, String address) {
        return entityManager.createNativeQuery(sql).setParameter("address", address).executeUpdate();
    }

    /**
     * True for the one SES feedback type that means the opposite of a complaint.
     * Compared case-insensitively because it is a provider-controlled string and a
     * capitalisation change must not turn an endorsement into a suppression.
     */
    private static boolean isNotSpamEndorsement(String feedbackType) {
        return feedbackType != null && "not-spam".equalsIgnoreCase(feedbackType.trim());
    }

    /**
     * Lowercased and trimmed, or null when there is nothing usable.
     *
     * <p>Must produce byte-for-byte what {@code EmailEligibility} produces, because
     * this writes the key that class reads. A divergence here would be the worst
     * failure mode this table has: the row would be written, the suppression would
     * look applied, and every read would miss it. Two things defend that. The
     * database CHECK on {@code email_suppressions.address} turns a missed
     * normalisation into a constraint violation on write rather than a silent miss
     * on read; and the implementation is deliberately the same three lines used in
     * {@code EmailEligibility}, {@code EmailMessage}, {@code UnsubscribeTokenService}
     * and {@code EmailVerificationService} rather than a shared helper - which is
     * this codebase's established (if repetitive) choice, and not one this module
     * should quietly change while three other modules depend on the current shape.
     */
    private static String normalize(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return address.trim().toLowerCase(Locale.ROOT);
    }
}
