package com.procurepal_services.stock_bridge_api.auth.passwordreset;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for self-service password reset (PASSWORD_RESET_PLAN.md). Every value has a
 * working default here rather than in YAML, the same arrangement as
 * {@code EmailVerificationProperties}: nothing gates the feature, and a deploy that
 * sets none of it behaves correctly.
 *
 * @param tokenTtl how long an emailed link works. One hour (owners' decision,
 *     2026-10-06): short, because a stolen link is an account takeover; not shorter,
 *     because mail on slow mobile data can take a while to arrive.
 * @param emailLimit reset emails one address may trigger per window. Bounds how often
 *     this public endpoint can make us mail one inbox.
 * @param ipLimit requests one client address may make per window, whatever emails
 *     they name. Higher than the email limit because a shop or office shares one IP.
 * @param window the rolling window both limits apply over.
 * @param maxAccounts most accounts one email can list. A shared office inbox can be
 *     the login at many companies; past this many, the email would be a wall of links.
 */
@ConfigurationProperties(prefix = "app.password-reset")
public record PasswordResetProperties(
        Duration tokenTtl, Integer emailLimit, Integer ipLimit, Duration window, Integer maxAccounts) {

    public PasswordResetProperties {
        tokenTtl = tokenTtl == null || tokenTtl.isNegative() || tokenTtl.isZero() ? Duration.ofHours(1) : tokenTtl;
        emailLimit = emailLimit == null || emailLimit < 1 ? 3 : emailLimit;
        ipLimit = ipLimit == null || ipLimit < 1 ? 10 : ipLimit;
        window = window == null || window.isNegative() || window.isZero() ? Duration.ofHours(1) : window;
        maxAccounts = maxAccounts == null || maxAccounts < 1 ? 5 : maxAccounts;
    }
}
