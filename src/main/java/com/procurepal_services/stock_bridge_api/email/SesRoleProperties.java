package com.procurepal_services.stock_bridge_api.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * An IAM role for SES that is <em>separate from the one S3 uses</em>, assumed from
 * the same long-lived user credentials.
 *
 * <h2>Why this exists at all, when app.aws.role-arn already assumes a role</h2>
 * Because that role is the S3 role. Adding {@code ses:SendEmail} to it works, and was
 * the original shape of this feature, but it grants every principal that can write
 * product images the ability to send mail as the company - two unrelated blast radii
 * fused into one credential. Splitting them means a compromised image-upload path
 * cannot send invoices to your customers, and a compromised mail path cannot
 * overwrite your catalog. They are separate jobs and they get separate roles.
 *
 * <h2>"From the same credentials"</h2>
 * Only the ROLE differs. The IAM user keys in {@code app.aws.access-key-id} /
 * {@code app.aws.secret-access-key} remain the single long-lived secret, and are used
 * here for exactly one call: {@code sts:AssumeRole} against this ARN. So there is
 * still one credential to rotate, and it still carries no permissions of its own.
 *
 * <p>Note what this deliberately does NOT do: it does not chain off the already-assumed
 * S3 session. Role chaining is capped at one hour regardless of the role's configured
 * maximum, and it would require the S3 role's trust policy to name this one - coupling
 * the two roles right back together, which is the thing being undone. See
 * {@link SesClientConfig}.
 *
 * <h2>Blank is a supported configuration</h2>
 * Blank means "use whatever credentials S3 uses", which is the previous behaviour
 * exactly: the shared provider, whether that is a direct access key or the
 * {@code app.aws.role-arn} session. Nothing breaks for a deployment that never sets
 * this, and a deployment that sets it needs no other change.
 *
 * @param arn the role to assume for SES. Blank falls back to the shared provider. The
 *     role must carry {@code ses:SendEmail} and must trust the IAM user configured in
 *     {@code app.aws}.
 * @param sessionName names the session in CloudTrail, so "what sent this email" is
 *     answerable per environment rather than under one shared name. Ignored when
 *     {@code arn} is blank.
 */
@ConfigurationProperties(prefix = "app.email.role")
public record SesRoleProperties(String arn, String sessionName) {

    /** The default is distinct from S3's so the two roles are told apart in CloudTrail at a glance. */
    public static final String DEFAULT_SESSION_NAME = "stock-bridge-api-ses";

    public boolean assumesRole() {
        return arn != null && !arn.isBlank();
    }

    public String resolvedSessionName() {
        return sessionName != null && !sessionName.isBlank() ? sessionName.trim() : DEFAULT_SESSION_NAME;
    }
}
