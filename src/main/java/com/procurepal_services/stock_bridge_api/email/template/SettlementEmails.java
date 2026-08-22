package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.bold;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.callout;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraphHtml;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.timestamp;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Settlement policy changes, announced to the people who can make them.
 *
 * <h2>Why this is SECURITY and not TRANSACTIONAL, in two independent arguments</h2>
 * {@link EmailKind}'s class doc warns that TRANSACTIONAL is the kind every instinct
 * reaches for and describes the trap; {@code VendorEmails} then documents the same
 * trap arriving through a different door. This is a third door, and both halves of
 * the case for SECURITY stand on their own.
 *
 * <p><b>One: TRANSACTIONAL would simply never arrive.</b> Work a super admin's
 * address through {@code EmailEligibility}'s rules in order. There is no
 * {@code users} row (rule 4) - a {@code super_admins} row is an entirely separate
 * identity from a tenant user and always has been (V1). There is no {@code clients}
 * row whose {@code admin_contact_email} it is (rule 5), because a super admin belongs
 * to no company. Unless it happens to equal the configured operator alias, rule 6
 * misses too, and rule 7 is fail-closed: a TRANSACTIONAL message would be dropped
 * before SES ever saw it, with one INFO line in a log nobody is reading. The owner's
 * requirement - "an email should be sent to the super admins" - would silently not
 * happen, and it would look exactly like a working feature.
 *
 * <p><b>Two: this is the message SECURITY exists for, on its own terms.</b>
 * {@link EmailKind#SECURITY} is defined as mail about the security of the account
 * itself, whose value is "entirely in the case where the recipient did not expect
 * it". That is this message precisely. The change it announces required a super
 * admin's password thirty seconds ago; if the recipient did not make it and nobody
 * told them one of them did, the first evidence of a compromised operator account
 * would be a vendor being paid on the wrong day months later. It is the direct
 * analogue of {@code AccountEmails.passwordChanged} - a privileged credential was
 * used, here is what it did, tell someone if it was not you - with money rather than
 * a login on the other side of it.
 *
 * <p>The cost SECURITY carries is the one {@link EmailKind} already prices and
 * bounds: it can reach an address nobody has verified, so it can bounce. Every
 * condition that doc sets is met - a handful of recipients, triggered by a human
 * action on the account rather than by a list, never sent in bulk, and a few times a
 * year at most. Rule 1 (suppression) still runs ahead of the bypass, so an address
 * SES has already declared dead is not mailed again.
 *
 * <h2>Everybody, including the person who did it</h2>
 * The actor is not filtered out of the recipients. Their own copy is the receipt -
 * it is how they find out the change actually landed and what the system now
 * believes, rather than trusting a toast that has already faded. And an actor who is
 * NOT told is an actor whose account can be used to change this without their
 * account ever seeing evidence of it, which is most of the point.
 *
 * <h2>What is deliberately not in this email</h2>
 * No password, obviously, and no link that changes anything - the button goes to the
 * settings screen, which will demand a password of its own. And no vendor names or
 * amounts: this is a policy notice, not a settlement report, and a mail that listed
 * who was affected would be a mail that must not be forwarded.
 */
public final class SettlementEmails {

    private SettlementEmails() {
    }

    /**
     * To every super admin: the escrow hold changed, from what to what, by whom.
     *
     * <p>The retroactivity sentence is in the body rather than in a footnote because
     * it is the fact a reader is most likely to assume the opposite of, and because
     * an operator reading this mail is often the person who will have to answer a
     * vendor about it. The hold that applies to a sale is the hold that was in force
     * when its buyer confirmed it; money already accrued does not move.
     *
     * <p>{@code changedAt} is rendered rather than left to the mail client's
     * received-at header. The two can differ by however long SES took, and "when
     * exactly did this change" is the question this record exists to answer.
     *
     * @param reason the operator's own words, or null/blank if they gave none. A
     *     blank renders no note block at all rather than a manufactured explanation -
     *     the same convention {@code VendorEmails} follows for its review notes.
     * @param payoutPeriodDays the cadence, so the mail can flag the one consequence
     *     that is genuinely easy to miss: a hold longer than a payout period pushes
     *     every vendor a whole cycle later. Passed in rather than written here, so
     *     the number has exactly one definition ({@code PayoutCadence}).
     */
    public static EmailMessage escrowHoldChanged(
            List<String> to,
            int previousHoldDays,
            int newHoldDays,
            String changedByUsername,
            OffsetDateTime changedAt,
            String reason,
            int payoutPeriodDays,
            String appBaseUrl) {

        String headline = "The vendor escrow hold changed from " + describe(previousHoldDays) + " to "
                + describe(newHoldDays) + ".";

        String body = paragraphHtml("The vendor escrow hold - how long a vendor's money waits after a buyer "
                        + "confirms receipt before it can be paid out - was changed from "
                        + bold(describe(previousHoldDays)) + " to " + bold(describe(newHoldDays)) + ".")
                + detailTable(List.of(
                        new Detail("Previous hold", describe(previousHoldDays)),
                        new Detail("New hold", describe(newHoldDays)),
                        new Detail("Changed by", changedByUsername),
                        new Detail("Changed at", timestamp(changedAt))))
                + reasonBlockHtml(reason)
                + callout("This applies to money confirmed FROM NOW ON. Anything a buyer has already "
                        + "confirmed keeps the hold it was confirmed under, so no payment date that a "
                        + "vendor has already been shown moves.")
                + cadenceWarningHtml(newHoldDays, payoutPeriodDays)
                + callout("If this was not you, or you did not expect it, treat it as a compromised super "
                        + "admin account and change that password now - this setting decides when real "
                        + "money leaves the business.")
                + button("Open settlement settings", settingsUrl(appBaseUrl));

        String text = headline + "\n\n"
                + "Previous hold: " + describe(previousHoldDays) + "\n"
                + "New hold: " + describe(newHoldDays) + "\n"
                + "Changed by: " + changedByUsername + "\n"
                + "Changed at: " + timestamp(changedAt) + "\n"
                + reasonBlockText(reason)
                + "\nThis applies to money confirmed from now on. Anything a buyer has already confirmed "
                + "keeps the hold it was confirmed under.\n"
                + cadenceWarningText(newHoldDays, payoutPeriodDays)
                + "\nIf this was not you, treat it as a compromised super admin account and change that "
                + "password now.\n"
                + textLink(settingsUrl(appBaseUrl));

        return new EmailMessage(
                to,
                "Vendor escrow hold changed to " + describe(newHoldDays),
                page("Vendor escrow hold changed", headline, body),
                text,
                // SECURITY. See the class doc: TRANSACTIONAL would be dropped before
                // SES for every super admin address, and this is a privileged-
                // credential notice whose value is highest when unexpected.
                EmailKind.SECURITY);
    }

    // ------------------------------------------------------------------------
    // Blocks. Each renders to nothing when it has nothing to say.
    // ------------------------------------------------------------------------

    /**
     * "7 days", "1 day", or the special case that needs words rather than a number.
     *
     * <p>Zero is a legal value meaning "payable the moment the buyer confirms", and
     * rendering it as "0 days" reads like a bug or a missing field in the one message
     * where a reader most needs to be sure. Saying what it means costs a branch.
     */
    private static String describe(int holdDays) {
        if (holdDays == 0) {
            return "no hold (payable as soon as the buyer confirms)";
        }
        return holdDays + (holdDays == 1 ? " day" : " days");
    }

    /**
     * The consequence a number alone does not convey: a hold longer than a payout
     * period means money confirmed in one cycle can never be paid by the run that
     * closes it, so every vendor is systematically paid a cycle later. Legal, and
     * worth saying out loud.
     */
    private static String cadenceWarningHtml(int newHoldDays, int payoutPeriodDays) {
        return newHoldDays <= payoutPeriodDays
                ? ""
                : callout("Note: this hold is longer than the " + payoutPeriodDays
                        + "-day payout cycle, so money confirmed in one cycle can no longer be paid by the "
                        + "run that closes it. Vendors will be paid a full cycle later than before.");
    }

    private static String cadenceWarningText(int newHoldDays, int payoutPeriodDays) {
        return newHoldDays <= payoutPeriodDays
                ? ""
                : "\nNote: this hold is longer than the " + payoutPeriodDays + "-day payout cycle, so vendors "
                        + "will be paid a full cycle later than before.\n";
    }

    /**
     * The operator's own words. Run through {@code callout}, which escapes, rather
     * than {@code paragraphHtml}: this is free text a human typed into a form, and
     * this class does not get to decide it is safe. Same rule
     * {@code VendorEmails.notesBlockHtml} follows.
     */
    private static String reasonBlockHtml(String reason) {
        return reason == null || reason.isBlank() ? "" : callout("Reason given: " + reason);
    }

    private static String reasonBlockText(String reason) {
        return reason == null || reason.isBlank() ? "" : "\nReason given: " + reason + "\n";
    }

    /** The super admin settlement settings screen. Matches the route registered in router.tsx. */
    private static String settingsUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/admin/settlement-settings";
    }

    private static String textLink(String url) {
        return url == null || url.isBlank() ? "" : "\n" + url + "\n";
    }
}
