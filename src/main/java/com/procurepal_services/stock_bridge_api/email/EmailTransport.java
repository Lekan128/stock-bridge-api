package com.procurepal_services.stock_bridge_api.email;

/**
 * The one network call {@link EmailSender} makes, behind an interface so the mail
 * provider is a deploy-time choice ({@code app.email.provider}) rather than a code
 * change.
 *
 * <h2>Why the seam is here and not around EmailSender</h2>
 * Everything that is <em>policy</em> - the configured/unconfigured gate, the
 * per-recipient fan-out RFC 8058 forces on promotional mail, the rule that no
 * failure ever propagates to the caller - lives in {@link EmailSender} and is the
 * same whichever provider carries the bytes. Only the request shape differs, so
 * only the request shape is swappable. A provider implementation therefore never
 * decides <em>whether</em> or <em>to whom</em> to send; it is handed a message that
 * has already been decided and either gets it accepted or throws.
 *
 * <h2>Exactly one implementation is a bean</h2>
 * {@link ResendEmailTransport} when {@code app.email.provider} is {@code resend} or
 * unset, {@link SesEmailTransport} when it is {@code ses}. Selection is by
 * {@code @ConditionalOnProperty} on each class, so a typo in the property value
 * leaves no transport at all and fails startup loudly, rather than silently
 * picking one.
 */
public interface EmailTransport {

    /** For log lines: "resend" or "ses". */
    String name();

    /**
     * Whether this provider has what it needs to attempt a send - for Resend, an API
     * key. Folded into {@link EmailSender#isConfigured()} alongside the
     * provider-independent conditions in {@link EmailProperties#isConfigured()}.
     */
    boolean isConfigured();

    /**
     * Hands one message to the provider.
     *
     * @param unsubscribeUrl the recipient's one-click unsubscribe URL for PROMOTIONAL
     *     mail (in which case {@code message} has exactly one recipient), or null for
     *     every kind of mail that must not carry a List-Unsubscribe header
     * @return the provider's message id
     * @throws Exception on any failure; {@link EmailSender} absorbs it
     */
    String send(EmailMessage message, String unsubscribeUrl) throws Exception;
}
