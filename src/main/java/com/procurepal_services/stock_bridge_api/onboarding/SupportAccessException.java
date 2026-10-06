package com.procurepal_services.stock_bridge_api.onboarding;

import org.springframework.http.HttpStatus;

/** Why the team can't open a shop's workspace, in words the admin screen shows as they are. */
public class SupportAccessException extends RuntimeException {

    private final HttpStatus status;

    private SupportAccessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    static SupportAccessException notFound() {
        return new SupportAccessException(HttpStatus.NOT_FOUND, "That shop does not exist.");
    }

    static SupportAccessException suspended() {
        return new SupportAccessException(HttpStatus.CONFLICT, "This shop's account is suspended.");
    }

    static SupportAccessException notAsked() {
        return new SupportAccessException(HttpStatus.FORBIDDEN,
                "This shop hasn't asked for a setup, so Procurepaddy support can't open its workspace.");
    }

    static SupportAccessException switchedOff() {
        return new SupportAccessException(HttpStatus.FORBIDDEN,
                "The shop has switched off Procurepaddy support on its Users page. Ask them on WhatsApp to switch it back on.");
    }

    static SupportAccessException nameTaken() {
        return new SupportAccessException(HttpStatus.CONFLICT,
                "This shop has its own user called procurepaddy-support, so the support account can't be created.");
    }
}
