package com.procurepal_services.stock_bridge_api.push;

/**
 * What a device shows. {@code tag} groups one thing's messages, so a later one replaces the earlier on the screen
 * (the request that was moved); {@code quiet} shows without a sound and goes by itself after a moment.
 */
public record PushMessage(String title, String body, String tag, String url, boolean quiet) {
}
