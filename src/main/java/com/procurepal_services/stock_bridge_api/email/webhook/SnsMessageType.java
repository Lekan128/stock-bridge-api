package com.procurepal_services.stock_bridge_api.email.webhook;

/**
 * The three values SNS puts in the envelope's {@code Type} field.
 *
 * <p>A small enum rather than three string constants because all three have to be
 * <em>handled</em>, not merely recognised, and an enum is what makes "did we cover
 * every case" a question the reader can answer by looking. The third one is the
 * reason: {@link #UNSUBSCRIBE_CONFIRMATION} arrives when somebody detaches this
 * endpoint from the topic, which is the exact moment bounce handling silently stops
 * working. Left as an unmatched string it would fall into a default branch and be
 * indistinguishable from noise.
 *
 * <p>Deliberately not an {@code @Enumerated} column on
 * {@code SesNotificationEvent} - the entity stores the raw string, so a type AWS
 * adds later is recorded faithfully instead of failing to deserialise a row.
 */
public enum SnsMessageType {

    /** The real payload: a bounce, a complaint, or an SES event we ignore. */
    NOTIFICATION("Notification"),

    /**
     * Sent once when the HTTP subscription is created. Must be answered by fetching
     * its {@code SubscribeURL} within an hour, or the subscription expires and no
     * bounce notification ever arrives.
     */
    SUBSCRIPTION_CONFIRMATION("SubscriptionConfirmation"),

    /**
     * Sent when the subscription is removed. Nothing to do but say so loudly: from
     * this moment SES bounces and complaints stop reaching this application, the
     * suppression list stops growing, and the only visible symptom is a bounce rate
     * that climbs at AWS while everything here looks healthy.
     */
    UNSUBSCRIBE_CONFIRMATION("UnsubscribeConfirmation");

    private final String wireValue;

    SnsMessageType(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Case-sensitive: AWS sends these exactly, and a case difference is a signal, not a variant. */
    public boolean matches(String type) {
        return wireValue.equals(type);
    }

    /** @return null for anything unrecognised - the caller records it and answers 200 rather than retrying forever. */
    public static SnsMessageType from(String type) {
        for (SnsMessageType candidate : values()) {
            if (candidate.matches(type)) {
                return candidate;
            }
        }
        return null;
    }
}
