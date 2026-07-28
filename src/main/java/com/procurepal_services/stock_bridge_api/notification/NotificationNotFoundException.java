package com.procurepal_services.stock_bridge_api.notification;

/** No notification with that id is visible to this user - it belongs to another company, or to a colleague. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("That notification was not found.");
    }
}
