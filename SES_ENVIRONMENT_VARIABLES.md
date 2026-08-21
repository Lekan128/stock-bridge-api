# SES — Environment Variables Reference

Every environment variable added by the email feature: what it is for, whether you need it, and
**how to obtain its value**. 22 variables in five groups.

Companion documents:
- [`SES_LOCAL_TESTING.md`](./SES_LOCAL_TESTING.md) — prove email works from your machine, in the SES sandbox
- [`SES_PRODUCTION_TESTING.md`](./SES_PRODUCTION_TESTING.md) — what to do once production access is approved
- [`WEBHOOKS.md`](./WEBHOOKS.md) — the URLs to paste into AWS/Monnify dashboards

**The short version.** Only two variables decide whether any mail leaves at all:
`EMAIL_ENABLED` (default `true`) and `EMAIL_FROM_ADDRESS` (no default). Leave the from-address
blank and the application starts normally, logs one warning, and writes every outgoing message to
the log instead of sending it. Nothing else in the system changes. Everything below is a
refinement of a message that would already send correctly.

Throughout, `REGION` means the AWS region your SES identity is verified in, and `ACCOUNT` your
12-digit account id:

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
```

---

## Group 1 — Core sending

### `EMAIL_FROM_ADDRESS`
**The one that matters.** The `From` on every outgoing message. Blank = email disabled.

Must be an address at an identity **verified in SES**. You verified a *domain*, so any address at
that domain is valid here — including one with no mailbox behind it, since SES only needs to own
the sending side. `no-reply@yourdomain.com` is the conventional choice.

Confirm the domain is actually verified before using it:
```bash
aws sesv2 get-email-identity --email-identity yourdomain.com --region REGION \
  --query '{Verified:VerifiedForSendingStatus, DKIM:DkimAttributes.Status}'
```
You want `Verified: true`. If `DKIM` is not `SUCCESS`, sending still works but deliverability will
be poor — finish the DKIM CNAMEs first.

> If the address has no mailbox, replies vanish. Set `EMAIL_REPLY_TO_ADDRESS` to somewhere real.

### `EMAIL_ENABLED`
Default `true`. The deliberate off switch, distinct from "not configured".

Set it `false` on any deployment that has working credentials but must not send — above all
**staging running against a restored production database**, which would otherwise email real
customers about orders being replayed. Set it before the restore, not after.

### `EMAIL_REGION`
Default: falls back to `AWS_REGION`, then `us-east-1`. The region your SES identity lives in.

Only set it if SES and your S3 bucket are in different regions. Find where your identities are:
```bash
for r in us-east-1 eu-west-1 eu-west-2 us-west-2; do
  echo "$r: $(aws sesv2 list-email-identities --region $r --query 'EmailIdentities[].IdentityName' --output text 2>/dev/null)"
done
```

### `EMAIL_FROM_NAME`
Default `ProcurePal`. The display name beside the address in the recipient's inbox. Free choice —
no AWS lookup. Blank sends the bare address, which is valid but less friendly.

### `EMAIL_REPLY_TO_ADDRESS`
Default blank. Where replies go when the from-address is a no-reply. Should be a **real monitored
mailbox** — customers reply to order emails regardless of what the header says. Does not need SES
verification (nothing is sent *from* it).

### `EMAIL_OPERATOR_ADDRESS`
Default blank. An extra recipient copied on operator-facing mail (new orders, payments received).

Blank is fine: operator mail already goes to the platform-owner tenant's own `admin_contact_email`.
Set it if you would rather it reached a shared alias like `ops@yourdomain.com`. **In the sandbox
this address must be verified too**, or those sends fail.

---

## Group 2 — The SES IAM role

### `EMAIL_ROLE_ARN`
Default blank. The role the app assumes **for SES only**, separate from `AWS_ROLE_ARN` (S3's).
You have already created this role.

```bash
aws iam get-role --role-name procurepal-ses-prod --query Role.Arn --output text
# arn:aws:iam::123456789012:role/procurepal-ses-prod
```

Blank means SES borrows whatever credentials S3 uses — which then requires `ses:SendEmail` on the
S3 role. Prefer the separate role: fusing them means anything that can write product images can
also send mail as your company.

**Three things must all be true**, and `AccessDenied` in the logs is always one of them:
1. The role has `ses:SendEmail` —
   `aws iam list-role-policies --role-name procurepal-ses-prod`
2. Your IAM user may assume it —
   `aws iam list-user-policies --user-name procurepal-s3-prod`
3. The role's trust policy names that user —
   `aws iam get-role --role-name procurepal-ses-prod --query Role.AssumeRolePolicyDocument`

Verify end-to-end before deploying anything:
```bash
aws sts assume-role \
  --role-arn "$(aws iam get-role --role-name procurepal-ses-prod --query Role.Arn --output text)" \
  --role-session-name manual-check --query 'Credentials.Expiration'
```
A timestamp means the chain works. An error tells you which of the three is missing.

### `EMAIL_ROLE_SESSION_NAME`
Default `stock-bridge-api-ses`. Names the assumed session in CloudTrail, so "what sent this email"
is answerable per environment. Free choice; use `-prod` / `-staging` / `-local` suffixes. Ignored
when `EMAIL_ROLE_ARN` is blank.

---

## Group 3 — Links inside emails

These are **not** interchangeable, and mixing them up produces mail that looks fine and whose links
are broken.

### `EMAIL_APP_BASE_URL`
Default `http://localhost:5173`. The **frontend** origin. Every link in every template is an in-app
React route — order pages, the sign-in page, the `/verify-email` page.

Value = whatever `FRONTEND_ORIGIN` is for that environment. Must match its host. Blank omits every
link and button rather than emitting one that 404s.

### `EMAIL_UNSUBSCRIBE_API_BASE_URL`
Default blank. The **backend** origin — this API's own public URL, e.g.
`https://api.yourdomain.com`. The only URL in the email package that is not a frontend route,
because the unsubscribe endpoint is called by the recipient's *mail provider*, not by a browser.

### `EMAIL_CONFIGURATION_SET`
Default blank. Names an SES configuration set — how bounce and complaint events get published to
SNS. Messages send fine without one, which is exactly the danger: bounces become invisible to you
and remain entirely visible to AWS.

Create one and use its name verbatim:
```bash
aws sesv2 create-configuration-set --configuration-set-name procurepal-prod --region REGION
aws sesv2 list-configuration-sets --region REGION --query 'ConfigurationSets'
```
Strongly recommended in production. See `SES_PRODUCTION_TESTING.md` for wiring its event
destination to SNS.

---

## Group 4 — Verification & unsubscribe

### `EMAIL_UNSUBSCRIBE_SECRET`
Default blank. HMAC key that signs one-click unsubscribe tokens. Generate it yourself:
```bash
openssl rand -base64 48
```

**Do not reuse `JWT_SECRET`.** Rotating the JWT secret is routine and costs nothing; rotating this
one invalidates every unsubscribe link ever mailed, in inboxes you cannot reach. Sharing them forces
the wrong rotation policy onto one of them.

Blank is currently harmless — nothing sends promotional mail yet, and promotional mail is *dropped*
rather than sent without a working unsubscribe link. Set it before you ever add a promotional sender.

### `EMAIL_VERIFICATION_TOKEN_TTL`
Default `24h`. How long a verification link stays valid. ISO-8601 or Spring duration (`24h`, `PT24H`).
Shorter is not obviously safer here — a stolen link only marks reachable an address its thief
already reads — and the cost of short windows is real (missed links, more resends).

### `EMAIL_VERIFICATION_RESEND_LIMIT` / `EMAIL_VERIFICATION_RESEND_WINDOW`
Defaults `3` and `1h`. Rate limit on "send me the link again", per user. This endpoint sends mail to
an address of the caller's choosing, so an unlimited version is a spam cannon.

In-process and per-instance: with several instances behind a load balancer the effective limit is
`limit × instances`, and it resets on restart. Acceptable for a courtesy email; not a security
control.

---

## Group 5 — SNS bounce & complaint webhook

Full setup sequence in [`WEBHOOKS.md`](./WEBHOOKS.md).

### `SES_SNS_TOPIC_ARN`
Default blank — but **effectively required in production**. The only SNS topic this endpoint accepts
notifications from.

```bash
aws sns create-topic --name procurepal-ses-events --region REGION --query TopicArn --output text
# arn:aws:sns:REGION:123456789012:procurepal-ses-events
```

Blank + `SES_SNS_REQUIRE_SIGNATURE=true` (the default) means **every notification is refused**. That
is intentional: a valid AWS signature proves only that *some* topic in *some* AWS account sent the
message, and anyone can point their own topic at your public URL. Since the endpoint's effect is to
stop mail to an address the message names, unpinned it would let a stranger cut off any customer
they can name.

> Set this **and deploy** before subscribing the endpoint in SNS, or the subscription confirmation
> is refused and sits at *Pending confirmation*.

### `SES_SNS_REQUIRE_SIGNATURE`
Default `true`. **Keep it true everywhere**, including staging. Set `false` only to replay a captured
payload locally. Unlike the Monnify equivalent there is no safety net: SES has no "ask again whether
that bounced" API, so a forged notification is acted on as fact.

### `SES_SNS_REGION`
Default blank (any valid `sns.<region>.amazonaws.com` host is accepted). Setting it narrows the
SSRF allow-list to one host. Use the same region as the topic.

### `SES_SNS_COMPLAINTS_SUPPRESS_ALL_MAIL`
Default `true` — a spam complaint stops **all** mail to that address, not just marketing.

An unsubscribe is scoped by the mechanism it arrived through; a complaint is not, and SES usually
redacts the original message. Deciding the reader "only meant the marketing" is choosing the reading
that suits you, and the cost lands on a domain reputation *every tenant shares* (AWS reviews above
0.1% complaints). Set `false` only if your transactional mail is legally load-bearing.

### `SES_SNS_CONFIRM_SUBSCRIPTIONS`
Default `true`. Auto-confirms an SNS `SubscriptionConfirmation` by fetching its `SubscribeURL`
(after validating the host is genuinely AWS). Set `false` to confirm by hand instead.

### `SES_SNS_CONNECT_TIMEOUT` / `SES_SNS_READ_TIMEOUT`
Defaults `5s` / `10s`. Timeouts for fetching AWS's signing certificate and the subscribe URL.
Defaults are fine.

---

## Not SES, but added at the same time

### `DB_MAX_POOL_SIZE`
Default `3`, and it applies to the **local profile only**. Each cached Spring test context holds its
own connection pool; at the previous default of 10 the suite collectively exceeded Postgres's
`max_connections`, surfacing as an unrelated test failing with "too many clients". Raise it only
alongside Postgres's own limit.

---

## Minimum viable configurations

**Local, sandbox** (see `SES_LOCAL_TESTING.md`):
```bash
EMAIL_FROM_ADDRESS=no-reply@yourdomain.com
EMAIL_REGION=REGION
EMAIL_ROLE_ARN=arn:aws:iam::ACCOUNT:role/procurepal-ses-local
EMAIL_APP_BASE_URL=http://localhost:5173
```

**Production**, everything above plus:
```bash
EMAIL_CONFIGURATION_SET=procurepal-prod
EMAIL_REPLY_TO_ADDRESS=support@yourdomain.com
EMAIL_UNSUBSCRIBE_API_BASE_URL=https://api.yourdomain.com
EMAIL_UNSUBSCRIBE_SECRET=<openssl rand -base64 48>
SES_SNS_TOPIC_ARN=arn:aws:sns:REGION:ACCOUNT:procurepal-ses-events
```
