package com.procurepal_services.stock_bridge_api.settlement;

/**
 * The caller is a signed-in super admin, and the password they re-entered to
 * authorise a money-policy change did not match.
 *
 * <h2>403, and not 401</h2>
 * 401 is the obvious reading - a credential failed - and it is the wrong status here
 * for a practical reason that matters more than the taxonomy. The request IS
 * authenticated: a valid super admin bearer token got it this far. Answering 401
 * tells every HTTP client in the world that the TOKEN is bad, and this application's
 * frontend, like most, responds to a 401 by trying a refresh and then signing the
 * user out. Mistyping a password in a confirmation box would eject the operator from
 * the admin panel, which is a confusing punishment for a typo.
 *
 * <p>403 says the true thing: you are who you say you are, and you have not proved
 * enough to be allowed THIS. It also matches how the rest of this application uses
 * 403 - {@code VendorNotAllowedException} for "your account may not do this" - rather
 * than inventing a fourth vocabulary for a fourth kind of refusal.
 *
 * <h2>The message never says which part was wrong</h2>
 * There is exactly one message and it names neither the account nor the failure mode.
 * The request carries no username - the account is whoever the token says it is - so
 * there is no existence to leak by name; what a differentiated message WOULD leak is
 * that the endpoint reached the password check at all, which tells somebody holding a
 * borrowed token that the only thing left between them and the setting is a guess. A
 * single flat refusal gives a wrong password, a missing account row and a deleted
 * operator the same reply.
 *
 * <p>Nothing is written before this is thrown and nothing is emailed after it. A
 * failed attempt leaves no audit row on purpose:
 * {@code vendor_settlement_settings_changes} records CHANGES, and a table that also
 * recorded attempts would be a table where the successful change is harder to find.
 * The attempt is logged instead, at WARN, with the actor - see
 * {@code VendorSettlementSettingsService}.
 */
public class SuperAdminReauthenticationFailedException extends RuntimeException {

    public SuperAdminReauthenticationFailedException() {
        super("Password confirmation failed. Nothing was changed.");
    }
}
