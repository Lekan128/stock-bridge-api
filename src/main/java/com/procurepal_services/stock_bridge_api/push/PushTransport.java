package com.procurepal_services.stock_bridge_api.push;

import java.util.Map;

/** Delivers one encrypted message to a device's push service; the HTTP status it answered. A seam for tests. */
public interface PushTransport {

    int send(String endpoint, Map<String, String> headers, byte[] body);
}
