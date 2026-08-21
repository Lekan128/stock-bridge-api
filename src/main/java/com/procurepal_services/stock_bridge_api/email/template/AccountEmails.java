package com.procurepal_services.stock_bridge_api.email.template;

import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.Detail;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.bold;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.button;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.callout;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.detailTable;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.escape;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.page;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraph;
import static com.procurepal_services.stock_bridge_api.email.template.EmailLayout.paragraphHtml;

import com.procurepal_services.stock_bridge_api.email.EmailKind;
import com.procurepal_services.stock_bridge_api.email.EmailMessage;
import java.util.List;

/**
 * Account and identity mail: a new company, a new colleague, a changed password, a
 * suspended tenant.
 *
 * <h2>The rule this class does not break</h2>
 * No email here contains a password, and no email here contains a link that logs
 * anybody in. That is worth stating because both are tempting and both are how this
 * kind of feature usually leaks credentials: an admin creates a user and the
 * obvious convenience is to mail them the password the admin just typed, which puts
 * a working credential in plain text in two mailboxes, in SES's logs, and in
 * whatever scans them. The invite below names the account and says to expect the
 * password by another route, which is less convenient and materially safer.
 *
 * <p>The same reasoning makes {@link #passwordChanged} an alert rather than a
 * delivery: its value is entirely in reaching someone whose password was changed
 * without their knowledge, which it can only do by not being the thing that carries
 * the new password.
 *
 * <p>There is still deliberately no self-service password-reset email here, but the
 * reason has changed and is worth restating so nobody re-derives the old one. The
 * missing pieces used to be structural - a single-use expiring token, a table to
 * store it, an endpoint to redeem it - and all three now exist for email
 * verification ({@code email_verification_tokens}, {@code
 * EmailVerificationService}). What remains missing is the part that actually makes
 * a reset link dangerous: a redemption that hands over a CREDENTIAL rather than
 * setting a flag. A stolen verification link marks an address reachable that its
 * thief already reads; a stolen reset link is an account takeover. Reusing this
 * table for it would silently apply a 24-hour expiry chosen for the harmless case
 * to the harmful one. When that flow is built it needs its own table, its own much
 * shorter expiry, and its own decision about invalidating sessions - and its email
 * belongs here.
 */
public final class AccountEmails {

    private AccountEmails() {
    }

    /**
     * To the founder of a brand-new tenant, immediately after signup.
     *
     * <p>VERIFICATION, not TRANSACTIONAL, and the choice is not cosmetic. A user
     * created after V8 is unverified by definition - the column defaults to FALSE -
     * so a TRANSACTIONAL welcome would be suppressed by EmailEligibility for every
     * single new signup. The founder would be met with silence and would never
     * learn there was an address to verify, which is the deadlock EmailKind.
     * VERIFICATION exists to break. This is also the natural carrier for the
     * verification link itself when that flow lands.
     */
    public static EmailMessage welcome(
            List<String> to, String companyName, String loginUsername, String appBaseUrl) {
        return welcome(to, companyName, loginUsername, appBaseUrl, null, null);
    }

    /**
     * The same email, carrying the verification link a brand-new account needs.
     * This is the form {@code ClientSignupService} actually uses; the four-argument
     * one above remains for callers that have no token to offer.
     *
     * <h2>Why the link rides on the welcome rather than arriving as a second mail</h2>
     * Sending "welcome" and "please confirm your address" as two messages seconds
     * apart is how a signup starts with two unread items, and it makes the one that
     * matters - the one with the only link that can ever set {@code
     * is_email_verified} - the one competing for attention. Worse, they are
     * indistinguishable in an inbox list until opened, so the reader picks at
     * random. Folding them together means the first thing a new account holder sees
     * is a single message whose primary call to action is the thing the account
     * actually needs.
     *
     * <p>It also removes a real failure: two dispatches are two chances for one to
     * be dropped by eligibility, by SES throttling, or by a spam filter that treats
     * near-duplicate mail as bulk. One message either arrives or does not.
     *
     * <p>The sign-in button is kept below the confirm button rather than replaced.
     * Verification is not a precondition for signing in - the account works
     * immediately - and an email that implied otherwise would strand anyone whose
     * link expired.
     */
    public static EmailMessage welcome(
            List<String> to,
            String companyName,
            String loginUsername,
            String appBaseUrl,
            String verificationUrl,
            String expiresIn) {
        String body = paragraphHtml("Welcome to ProcurePal, " + bold(escape(companyName)) + ".")
                + paragraph("Your company account is ready. You are its account holder, which means you can "
                        + "invite colleagues, set their roles, and manage everything the account can do.")
                + detailTable(List.of(
                        new Detail("Company", companyName),
                        new Detail("Your username", loginUsername)))
                + verificationBlockHtml(verificationUrl, expiresIn)
                + paragraph("From here you can add your products and stock, or start buying from the "
                        + "ProcurePal marketplace - anything you order lands in your inventory as incoming "
                        + "stock, and becomes usable stock once you confirm you have received it.")
                + button("Sign in to ProcurePal", signInUrl(appBaseUrl));

        String text = "Welcome to ProcurePal, " + companyName + ".\n\n"
                + "Your company account is ready and you are its account holder.\n\n"
                + "Company: " + companyName + "\nYour username: " + loginUsername + "\n"
                + verificationBlockText(verificationUrl, expiresIn)
                + textLink(signInUrl(appBaseUrl));

        return new EmailMessage(to, "Welcome to ProcurePal",
                page("Welcome to ProcurePal", "Your company account for " + companyName + " is ready.", body),
                text, EmailKind.VERIFICATION);
    }

    /**
     * To a user an admin just created. Names the account and where to sign in; see
     * the class doc for why it cannot name the password.
     *
     * <p>VERIFICATION for the same reason as {@link #welcome} above: an invited
     * user is brand new and therefore unverified, so gating this would mean nobody
     * ever receives the mail telling them their account exists.
     */
    public static EmailMessage userInvited(
            List<String> to, String companyName, String username, String roleName, String appBaseUrl) {
        return userInvited(to, companyName, username, roleName, appBaseUrl, null, null);
    }

    /**
     * The invitation, carrying a verification link.
     *
     * <p>A sub-user needs this at least as much as an account holder does, and for
     * a reason that is easy to miss: they are created by somebody else, with an
     * address somebody else typed. Nobody has demonstrated that address is even
     * theirs, so it is the population most likely to be wrong - and under V8 an
     * unverified user silently receives no order mail at all, forever, with nothing
     * in the product telling them why. Attaching the link to the one email they are
     * guaranteed to be sent is what stops "our warehouse lead never gets delivery
     * notifications" from becoming a support ticket nobody can explain.
     *
     * <p>Same one-message reasoning as {@link #welcome}; see there.
     */
    public static EmailMessage userInvited(
            List<String> to,
            String companyName,
            String username,
            String roleName,
            String appBaseUrl,
            String verificationUrl,
            String expiresIn) {
        String body = paragraphHtml("An administrator at " + bold(escape(companyName))
                        + " has created a ProcurePal account for you.")
                + detailTable(List.of(
                        new Detail("Company", companyName),
                        new Detail("Username", username),
                        new Detail("Role", EmailLayout.humanise(roleName))))
                + callout("Your password is not in this email. The administrator who created your account "
                        + "will give it to you directly - change it from your profile once you have signed in.")
                + verificationBlockHtml(verificationUrl, expiresIn)
                + button("Sign in to ProcurePal", signInUrl(appBaseUrl));

        String text = "An administrator at " + companyName + " has created a ProcurePal account for you.\n\n"
                + "Company: " + companyName + "\nUsername: " + username
                + "\nRole: " + EmailLayout.humanise(roleName) + "\n\n"
                + "Your password is not in this email - the administrator who created your account will "
                + "give it to you directly.\n"
                + verificationBlockText(verificationUrl, expiresIn)
                + textLink(signInUrl(appBaseUrl));

        return new EmailMessage(to, "Your ProcurePal account is ready",
                page("Your ProcurePal account is ready",
                        "An administrator at " + companyName + " created an account for you.", body),
                text, EmailKind.VERIFICATION);
    }

    /**
     * To a user whose password an administrator reset. The value of this email is
     * entirely in the case where the user did not expect it, so the call to action
     * is "tell someone", not "sign in".
     *
     * <p>SECURITY, so it is delivered regardless of verification state. That
     * sentence above is the whole argument: this message is worth sending precisely
     * when it is unwelcome, and an unverified account is if anything the more
     * likely one to have been taken over. Suppressing it would mean the one warning
     * a compromised user gets is withheld from the users most likely to need it.
     */
    public static EmailMessage passwordChanged(List<String> to, String username, String companyName) {
        String body = paragraph("The password for your ProcurePal account was just changed by an "
                        + "administrator at " + companyName + ".")
                + detailTable(List.of(new Detail("Username", username), new Detail("Company", companyName)))
                + callout("If you were not expecting this, contact your administrator immediately - somebody "
                        + "else can currently sign in as you.")
                + paragraph("For security, the new password is not included in this email.");

        String text = "The password for your ProcurePal account (" + username + ") was just changed by an "
                + "administrator at " + companyName + ".\n\n"
                + "If you were not expecting this, contact your administrator immediately.\n\n"
                + "For security, the new password is not included in this email.\n";

        return new EmailMessage(to, "Your ProcurePal password was changed",
                page("Your password was changed", "An administrator reset the password on your account.", body),
                text, EmailKind.SECURITY);
    }

    /**
     * To a tenant's admin contact when ProcurePal suspends or restores their
     * account. Suspension is the more important of the two by a distance: a
     * suspended company's users are refused at login with no explanation the app can
     * give them, so this email is the only place the reason can come from.
     */
    public static EmailMessage accountStatusChanged(
            List<String> to, String companyName, boolean active, String appBaseUrl) {
        String heading = active
                ? "Your ProcurePal account has been reactivated"
                : "Your ProcurePal account has been suspended";
        String body;
        String text;

        if (active) {
            body = paragraphHtml("The ProcurePal account for " + bold(escape(companyName))
                            + " has been reactivated.")
                    + paragraph("You and your colleagues can sign in again, and everything in your account "
                            + "is exactly as you left it.")
                    + button("Sign in to ProcurePal", signInUrl(appBaseUrl));
            text = "The ProcurePal account for " + companyName + " has been reactivated. You and your "
                    + "colleagues can sign in again.\n" + textLink(signInUrl(appBaseUrl));
        } else {
            body = paragraphHtml("The ProcurePal account for " + bold(escape(companyName))
                            + " has been suspended.")
                    + callout("Nobody at your company can sign in while the account is suspended. Your data "
                            + "has not been deleted and will be exactly as you left it if the account is "
                            + "reactivated.")
                    + paragraph("If you believe this is a mistake, reply to this message or contact "
                            + "ProcurePal support.");
            text = "The ProcurePal account for " + companyName + " has been suspended.\n\n"
                    + "Nobody at your company can sign in while the account is suspended. Your data has not "
                    + "been deleted.\n\nIf you believe this is a mistake, contact ProcurePal support.\n";
        }

        return new EmailMessage(to, heading, page(heading, heading, body), text);
    }

    /**
     * The standalone "confirm your address" email - what {@code POST /api/me/email/
     * verification} sends when somebody asks for the link again.
     *
     * <p>It exists separately from the two above because the reader's situation is
     * different: they already know what ProcurePal is and already have an account,
     * so a welcome would be noise. What they need is one link and an unambiguous
     * statement of WHICH address it confirms - a resend is very often triggered
     * because the first one went somewhere wrong, so naming the address is how the
     * reader discovers a typo instead of waiting for mail that will never come.
     *
     * <p>VERIFICATION, necessarily. Gating the mail that sets the verified flag on
     * being verified is the deadlock {@code EmailKind.VERIFICATION} exists to
     * break, and this method is the single most literal instance of it in the
     * codebase.
     *
     * <p>The address is interpolated through {@code escape} like everything else.
     * That is not paranoia about an email address: this string comes from a
     * user-editable profile field, is only loosely validated, and reaches a mail
     * client that renders markup.
     */
    public static EmailMessage verifyEmailAddress(
            List<String> to, String companyName, String emailAddress, String verificationUrl, String expiresIn) {
        String body = paragraph("Confirm this email address so ProcurePal can send you order receipts, "
                        + "delivery updates and payment confirmations.")
                + detailTable(List.of(
                        new Detail("Company", companyName),
                        new Detail("Email address", emailAddress)))
                + verificationBlockHtml(verificationUrl, expiresIn)
                + paragraph("Until it is confirmed, your account works normally - you simply will not "
                        + "receive email from us.")
                + paragraph("If you did not ask for this, you can ignore it. Nothing changes unless the "
                        + "link is used.");

        String text = "Confirm this email address so ProcurePal can send you order receipts, delivery "
                + "updates and payment confirmations.\n\n"
                + "Company: " + companyName + "\nEmail address: " + emailAddress + "\n"
                + verificationBlockText(verificationUrl, expiresIn)
                + "\nUntil it is confirmed, your account works normally - you simply will not receive "
                + "email from us.\n\n"
                + "If you did not ask for this, you can ignore it. Nothing changes unless the link is "
                + "used.\n";

        return new EmailMessage(to, "Confirm your ProcurePal email address",
                page("Confirm your email address",
                        "One click confirms " + emailAddress + " so ProcurePal can email you.", body),
                text, EmailKind.VERIFICATION);
    }

    /**
     * The confirm callout and button, shared by all three verification-bearing
     * emails so the wording of the single most important sentence in this file is
     * written once.
     *
     * <p>Returns empty for a blank URL, matching {@link EmailLayout#button}'s own
     * rule and for the same reason: an unconfigured {@code app.email.app-base-url}
     * would otherwise produce a callout urging the reader to click a link that is
     * not there. A caller with no token to offer passes null and gets the email it
     * would have got before this flow existed.
     */
    private static String verificationBlockHtml(String verificationUrl, String expiresIn) {
        if (verificationUrl == null || verificationUrl.isBlank()) {
            return "";
        }
        return callout("Confirm your email address so we can send you order receipts and delivery "
                        + "updates. This link works once" + expiryClause(expiresIn) + ".")
                + button("Confirm your email address", verificationUrl);
    }

    /**
     * The plain-text half. The raw URL is printed on its own line rather than
     * wrapped in prose because that is what makes it clickable in a text-only
     * client and copy-pasteable in one that is not - the same convention
     * {@link #textLink} follows for the sign-in link.
     */
    private static String verificationBlockText(String verificationUrl, String expiresIn) {
        if (verificationUrl == null || verificationUrl.isBlank()) {
            return "";
        }
        return "\nConfirm your email address so we can send you order receipts and delivery updates. "
                + "This link works once" + expiryClause(expiresIn) + ".\n"
                + verificationUrl + "\n";
    }

    /**
     * "and expires in 24 hours", or nothing at all.
     *
     * <p>The duration is passed in rather than written here because it is
     * configurable ({@code app.email.verification.token-ttl}), and an email that
     * confidently states a number the server does not use is worse than one that
     * says nothing - it is the sentence a reader will quote back when the link
     * stops working early.
     */
    private static String expiryClause(String expiresIn) {
        return expiresIn == null || expiresIn.isBlank() ? "" : " and expires in " + expiresIn;
    }

    private static String signInUrl(String appBaseUrl) {
        return appBaseUrl == null || appBaseUrl.isBlank() ? "" : appBaseUrl + "/login";
    }

    private static String textLink(String url) {
        return url == null || url.isBlank() ? "" : "\n" + url + "\n";
    }
}
