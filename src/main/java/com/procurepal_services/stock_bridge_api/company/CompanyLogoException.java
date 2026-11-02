package com.procurepal_services.stock_bridge_api.company;

import org.springframework.http.HttpStatus;

/** A logo that could not be set, with the plain-words reason the settings page shows. */
public class CompanyLogoException extends RuntimeException {

    private final HttpStatus status;

    public CompanyLogoException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
