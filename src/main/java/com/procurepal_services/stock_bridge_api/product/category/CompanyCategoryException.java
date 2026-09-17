package com.procurepal_services.stock_bridge_api.product.category;

import org.springframework.http.HttpStatus;

/** A category request that cannot be done, with the status and the sentence to say why. */
public class CompanyCategoryException extends RuntimeException {

    private final HttpStatus status;

    public CompanyCategoryException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    static CompanyCategoryException notFound() {
        return new CompanyCategoryException(HttpStatus.NOT_FOUND, "We couldn't find that category.");
    }

    static CompanyCategoryException duplicate(String name) {
        return new CompanyCategoryException(HttpStatus.CONFLICT, "You already have a category called “" + name + "”.");
    }
}
