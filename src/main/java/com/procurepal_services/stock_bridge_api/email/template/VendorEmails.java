package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.bold;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.callout;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.escape;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.joinNonBlank;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraphHtml;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.timestamp;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The vendor waitlist: a business asking to sell, ProcurePal telling them we are
 * looking, and the answer either way.
 *
 * <h2>Why every applicant-facing message here is VERIFICATION and not TRANSACTIONAL</h2>
 * This is the trap {@link EmailKind}'s class doc describes, arriving through a
 * door it did not anticipate, and it is worth stating in full because
 * TRANSACTIONAL is the kind every instinct reaches for - these are outcome
 * notices about something that happened, which is TRANSACTIONAL's own definition.
 *
 * <p>Work an applicant's address through {@code EmailEligibility}'s rules in
 * order and it never matches anything. There is no {@code users} row (rule 4) -
 * that is the entire point of a waitlist, they do not have an account. There is
 * no {@code clients} row whose {@code admin_contact_email} it could be (rule 5),
 * for the same reason. It is not the operator alias (rule 6). So rule 7 fires and
 * a TRANSACTIONAL message is dropped before it is ever handed to SES. Not
 * delayed, not degraded - dropped, with one INFO line in a log nobody is reading.
 * The applicant hears nothing, ever, on every one of these three messages.
 *
 * <p>{@link #applicationApproved} is the one that looks like it escapes and does
 * not. By the time it is sent the {@code clients} row and the vendor's single
 * user DO exist, so rule 5 would pass - except rule 4 is consulted first, finds
 * the brand-new user row, sees {@code is_email_verified = false} (the column
 * defaults to FALSE - see {@code V8__email_eligibility.sql}) and answers no. That
 * is the same deadlock {@code AccountEmails.welcome} documents: the mail that
 * carries the confirmation link cannot itself require a confirmed address. This
 * message carries that link for exactly that reason, and it is the vendor's
 * welcome email as much as it is their approval notice.
 *
 * <p>The cost is the one {@link EmailKind} already prices in and bounds: these can
 * reach an address nobody has confirmed, so they can bounce, and bounces are
 * scored against a sending domain every tenant shares. Every condition that doc
 * sets for accepting that is met here - low volume, one message per human
 * decision by a human reviewer, never sent in bulk, never sent from a list. And
 * the suppression list (rule 1) still runs AHEAD of the bypass, so an address SES
 * has already told us is dead is not mailed a second time.
 *
 * <h2>The one message that is TRANSACTIONAL, and why</h2>
 * {@link #newApplicationForOperator} goes to {@code
 * app.email.vendor-waitlist-address}, which rule 6 makes eligible for
 * TRANSACTIONAL by name. Nothing about it establishes that an address works, so
 * calling it VERIFICATION would be borrowing a bypass it does not need in order
 * to describe itself wrongly.
 *
 * <h2>What is deliberately not in these emails</h2>
 * No password, on {@code AccountEmails}' rule and for its reason. The approval
 * email names the account and says the password arrives another way, even though
 * a super admin typed that password thirty seconds earlier and mailing it would
 * be the convenient thing.
 *
 * <p>And no reason in the rejection unless a reviewer wrote one. An automatically
 * generated "your application did not meet our criteria" is worse than silence on
 * the point: it reads as a decision a machine made, and the reviewer's own note -
 * which the admin UI requires - is both more useful and more honest.
 */
public final class VendorEmails {

    private VendorEmails() {
    }

    /**
     * To ProcurePal: somebody wants to sell on the marketplace.
     *
     * <p>Carries everything a reviewer needs to start researching the business
     * without opening the admin panel first - the name to search, the phone to
     * call, the address to check, and when it arrived so an aging queue is visible
     * from an inbox. The button is what they click once they have decided.
     *
     * <p>{@code submittedAt} is rendered rather than left to the mail client's own
     * received-at header because the two can differ by however long SES took, and
     * "this has been sitting for three days" is the fact the reviewer is actually
     * reading it for.
     */
    public static EmailMessage newApplicationForOperator(
            List<String> to,
            String businessName,
            String email,
            String contactPhone,
            String addressLine1,
            String addressLine2,
            String city,
            String state,
            String notes,
            OffsetDateTime submittedAt,
            String appBaseUrl) {
        String address = joinNonBlank(", ", addressLine1, addressLine2, city, state);
        String body = paragraphHtml("A business has applied to sell on the ProcurePal marketplace.")
                + detailTable(List.of(
                        new Detail("Business", businessName),
                        new Detail("Email", email),
                        new Detail("Phone", contactPhone),
                        new Detail("Address", address),
                        new Detail("Submitted", timestamp(submittedAt))))
                + notesBlockHtml(notes)
                + paragraph("Nobody has been told anything yet beyond an acknowledgement. Approving creates "
                        + "their vendor account; rejecting sends them your note.")
                + button("Review the waitlist", waitlistUrl(appBaseUrl));

        String text = "A business has applied to sell on the ProcurePal marketplace.\n\n"
                + "Business: " + businessName + "\n"
                + "Email: " + email + "\n"
                + "Phone: " + contactPhone + "\n"
                + (address.isBlank() ? "" : "Address: " + address + "\n")
                + "Submitted: " + timestamp(submittedAt) + "\n"
                + notesBlockText(notes)
                + textLink(waitlistUrl(appBaseUrl));

        return new EmailMessage(to, "New vendor application: " + businessName,
                page("New vendor application", businessName + " has applied to sell on the marketplace.", body),
                text, EmailKind.TRANSACTIONAL);
    }

    /**
     * To the applicant, the moment they submit: we have it, a human will read it.
     *
     * <p>Says "waitlist" and never "account", because the single most likely
     * support ticket this feature can generate is somebody trying to sign in with
     * an application they submitted. It states plainly that there is nothing to
     * sign into yet and that the next thing they will get is a decision.
     *
     * <p>No timeframe is promised. A number here becomes the thing an applicant
     * quotes back when it slips, and nobody has committed to one.
     */
    public static EmailMessage applicationReceived(List<String> to, String businessName) {
        String body = paragraphHtml("Thanks - we have your application for " + bold(escape(businessName)) + ".")
                + paragraph("Somebody on the ProcurePal team reads every application. We will look at "
                        + "yours and email you either way.")
                + callout("This is a waitlist, not an account. There is nothing to sign in to yet - if we "
                        + "approve you we will send you your login details in a separate email.")
                + paragraph("If we need anything else from you, we will reply to this address.");

        String text = "Thanks - we have your application for " + businessName + ".\n\n"
                + "Somebody on the ProcurePal team reads every application. We will look at yours and "
                + "email you either way.\n\n"
                + "This is a waitlist, not an account. There is nothing to sign in to yet - if we approve "
                + "you we will send you your login details in a separate email.\n\n"
                + "If we need anything else from you, we will reply to this address.\n";

        return new EmailMessage(to, "We have your ProcurePal vendor application",
                page("We have your application",
                        "Your application to sell on ProcurePal is with our team.", body),
                text, EmailKind.VERIFICATION);
    }

    /**
     * To the applicant: approved, here is your account.
     *
     * <p>This is a welcome email wearing an approval notice's subject line, and it
     * is built like {@code AccountEmails.welcome} rather than like the rejection
     * below - same detail table, same verification block, same sign-in button,
     * same refusal to carry the password. A vendor's single account is their whole
     * relationship with the platform, so the first message about it has to do the
     * work an ordinary tenant's welcome does.
     *
     * <p>{@code verificationUrl} is nullable and blank renders the email exactly as
     * it would have been without the confirmation block, per
     * {@code AccountEmails}' convention. It is worth having: a vendor who never
     * verifies receives no order mail at all under V8, and a seller who does not
     * hear about orders is a seller who does not ship.
     */
    public static EmailMessage applicationApproved(
            List<String> to,
            String businessName,
            String username,
            String reviewNote,
            String appBaseUrl,
            String verificationUrl,
            String expiresIn) {
        String body = paragraphHtml("Good news - " + bold(escape(businessName))
                        + " has been approved to sell on the ProcurePal marketplace.")
                + detailTable(List.of(
                        new Detail("Business", businessName),
                        new Detail("Your username", username)))
                + callout("Your password is not in this email. Whoever set up your account will give it to "
                        + "you directly - change it from your profile once you have signed in.")
                + reviewNoteBlockHtml(reviewNote, "A note from the team")
                + verificationBlockHtml(verificationUrl, expiresIn)
                + paragraph("From here you can list your products, set your stock, add the addresses we "
                        + "collect from, and see the orders companies place with you.")
                + button("Sign in to ProcurePal", signInUrl(appBaseUrl));

        String text = "Good news - " + businessName + " has been approved to sell on the ProcurePal "
                + "marketplace.\n\n"
                + "Business: " + businessName + "\nYour username: " + username + "\n\n"
                + "Your password is not in this email - whoever set up your account will give it to you "
                + "directly.\n"
                + reviewNoteBlockText(reviewNote, "A note from the team")
                + verificationBlockText(verificationUrl, expiresIn)
                + textLink(signInUrl(appBaseUrl));

        return new EmailMessage(to, "You have been approved to sell on ProcurePal",
                page("You have been approved to sell on ProcurePal",
                        "Your vendor account for " + businessName + " is ready.", body),
                text, EmailKind.VERIFICATION);
    }

    /**
     * To the applicant: not this time.
     *
     * <p>The reviewer's note is the body of this email, not a footnote to it -
     * which is why the admin surface requires one. It also says the door is not
     * bolted: there is deliberately no unique index on the waitlist's email column
     * (see {@code V11__vendors.sql}) precisely so a business can come back with
     * better information, and an applicant who does not know that will not.
     */
    public static EmailMessage applicationRejected(List<String> to, String businessName, String reviewNote) {
        String body = paragraphHtml("Thanks for applying to sell on ProcurePal. We are not able to take "
                        + bold(escape(businessName)) + " on at the moment.")
                + reviewNoteBlockHtml(reviewNote, "Why")
                + paragraph("This is not permanent. If something changes - or if there is more you can "
                        + "tell us about your business - you are welcome to apply again.");

        String text = "Thanks for applying to sell on ProcurePal. We are not able to take " + businessName
                + " on at the moment.\n"
                + reviewNoteBlockText(reviewNote, "Why")
                + "\nThis is not permanent. If something changes - or if there is more you can tell us "
                + "about your business - you are welcome to apply again.\n";

        return new EmailMessage(to, "Your ProcurePal vendor application",
                page("About your vendor application",
                        "An update on your application to sell on ProcurePal.", body),
                text, EmailKind.VERIFICATION);
    }

    // ------------------------------------------------------------------------
    // Blocks. Each renders to nothing when it has nothing to say, so a caller
    // never has to decide whether to include it.
    // ------------------------------------------------------------------------

    /**
     * The applicant's own words, quoted back to the reviewer. Deliberately not run
     * through {@code paragraphHtml}: this is the one field on the form that is
     * free text a stranger typed, so it goes through {@code paragraph}, which
     * escapes.
     */
    private static String notesBlockHtml(String notes) {
        return notes == null || notes.isBlank() ? "" : callout(notes);
    }

    private static String notesBlockText(String notes) {
        return notes == null || notes.isBlank() ? "" : "\nWhat they told us:\n" + notes + "\n";
    }

    private static String reviewNoteBlockHtml(String reviewNote, String label) {
        return reviewNote == null || reviewNote.isBlank() ? "" : callout(label + ": " + reviewNote);
    }

    private static String reviewNoteBlockText(String reviewNote, String label) {
        return reviewNote == null || reviewNote.isBlank() ? "" : "\n" + label + ": " + reviewNote + "\n";
    }

    /**
     * Copied in shape from {@code AccountEmails}' private equivalents rather than
     * shared with them. They are private for a reason - each class owns the copy in
     * its own emails - and hoisting them to {@link EmailLayout} would put wording
     * ("Confirm your email address so we can send you order receipts") in the class
     * whose job is chrome. The wording here is a vendor's, not a buyer's.
     */
    private static String verificationBlockHtml(String verificationUrl, String expiresIn) {
        if (verificationUrl == null || verificationUrl.isBlank()) {
            return "";
        }
        return callout("Confirm your email address so we can tell you when orders come in. This link "
                        + "works once" + expiryClause(expiresIn) + ".")
                + button("Confirm your email address", verificationUrl);
    }

    private static String verificationBlockText(String verificationUrl, String expiresIn) {
        if (verificationUrl == null || verificationUrl.isBlank()) {
            return "";
        }
        return "\nConfirm your email address so we can tell you when orders come in. This link works once"
                + expiryClause(expiresIn) + ".\n" + verificationUrl + "\n";
    }

    /** See {@code AccountEmails.expiryClause} - the TTL is configurable, so it is passed in, never written here. */
    private static String expiryClause(String expiresIn) {
        return expiresIn == null || expiresIn.isBlank() ? "" : " and expires in " + expiresIn;
    }

    private static String signInUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/login";
    }

    /** The super-admin waitlist queue. Matches the frontend route registered in router.tsx. */
    private static String waitlistUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/admin/vendor-waitlist";
    }

    private static String textLink(String url) {
        return url == null || url.isBlank() ? "" : "\n" + url + "\n";
    }
}
