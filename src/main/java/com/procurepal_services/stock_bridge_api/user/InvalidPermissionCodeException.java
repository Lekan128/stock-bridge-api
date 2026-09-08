package com.procurepal_services.stock_bridge_api.user;

import java.util.Set;

public class InvalidPermissionCodeException extends RuntimeException {

    public InvalidPermissionCodeException(Set<String> unknownCodes) {
        super("Unknown permission code(s): " + String.join(", ", unknownCodes));
    }
}
