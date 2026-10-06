package com.procurepal_services.stock_bridge_api.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits on the public signup endpoint. Every signup sends a welcome-and-confirm email to the
 * submitted address, and that email deliberately bypasses the verified-address gate (it is the
 * mail that verifies). Without a limit, signup is a free way to make this server mail any
 * stranger as often as a script likes.
 *
 * <p>Two keys with two windows, because they answer different questions:
 * <ul>
 *   <li><b>Per address, per day</b> - the one that protects a victim. Flooding comes from many
 *       IPs at once, so only a per-address limit stops one inbox receiving a pile of welcomes.
 *       Three a day still lets a real owner set up a second or third business with the same
 *       email.</li>
 *   <li><b>Per IP, per hour</b> - caps how many companies one source can mint. Generous, because
 *       an office or a mobile carrier puts many real people behind one address.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.signup")
public record SignupProperties(Integer emailLimit, Duration emailWindow, Integer ipLimit, Duration ipWindow) {

    public SignupProperties {
        emailLimit = emailLimit == null || emailLimit < 1 ? 3 : emailLimit;
        emailWindow = positiveOr(emailWindow, Duration.ofHours(24));
        ipLimit = ipLimit == null || ipLimit < 1 ? 10 : ipLimit;
        ipWindow = positiveOr(ipWindow, Duration.ofHours(1));
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
