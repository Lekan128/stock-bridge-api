package com.procurepal_services.stock_bridge_api.email.webhook;

/**
 * A URL taken from a webhook body pointed somewhere this server refuses to send a
 * request. Thrown by {@link SnsEndpointGuard} and never caught into a "well, try it
 * anyway" branch - the whole value of the guard is that it has no soft failure.
 *
 * <p>The message deliberately names the host that was refused. It goes into a log
 * that only operators read, and the first question after "something was refused" is
 * always "refused what", which for an SSRF attempt is the single most useful line
 * in the file.
 */
public class SnsEndpointRefusedException extends RuntimeException {

    public SnsEndpointRefusedException(String message) {
        super(message);
    }
}
