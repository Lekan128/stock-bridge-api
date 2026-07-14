package com.procurepal_services.stock_bridge_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Both fields are optional - bootstrap only runs when both are non-blank. */
@ConfigurationProperties(prefix = "app.super-admin")
public record SuperAdminBootstrapProperties(String username, String password) {

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }
}
