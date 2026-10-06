package com.procurepal_services.stock_bridge_api.email.verification;

/**
 * The acting user has not confirmed their email address, and the action they tried
 * - placing an order, paying for one - requires it. Mapped to 403 by the order and
 * payment exception handlers; the message is shown to the buyer verbatim.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Confirm your email address before placing orders or making payments. "
                + "Use the link we emailed you, or request a new one from your profile.");
    }
}
