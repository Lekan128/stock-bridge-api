# SES — Production Rollout & Testing

What to do **once AWS approves production access**, and how to prove email works against real
inboxes without risking the sending reputation you are about to depend on.

- Variable reference: [`SES_ENVIRONMENT_VARIABLES.md`](./SES_ENVIRONMENT_VARIABLES.md)
- Sandbox testing: [`SES_LOCAL_TESTING.md`](./SES_LOCAL_TESTING.md)
- URLs to register: [`WEBHOOKS.md`](./WEBHOOKS.md)

---

## Confirm approval first

```bash
aws sesv2 get-account --region REGION \
  --query '{ProductionAccess:ProductionAccessEnabled, Quota:SendQuota, Sending:SendingEnabled}'
```

`ProductionAccess: true` means approved — the field is *production access enabled*, so `true` is out
of the sandbox. Until then, production
will only reach addresses you have verified — everything will look correct and real customers will
receive nothing, which is the single most common way this feature appears to work and does not.

---

## The rollout order that avoids the two traps

### 1. DKIM, SPF and DMARC before volume

```bash
aws sesv2 get-email-identity --email-identity yourdomain.com --region REGION \
  --query '{DKIM:DkimAttributes.Status, Verified:VerifiedForSendingStatus}'
```
`DKIM` must be `SUCCESS`. Then confirm the domain publishes SPF and DMARC:
```bash
dig +short TXT yourdomain.com | grep spf
dig +short TXT _dmarc.yourdomain.com
```
Mail from a new domain without all three lands in spam often enough that the feature will look
broken rather than misconfigured. Start DMARC at `p=none` and tighten once you have reports.

### 2. Configuration set and SNS **before** the first real send

This is the trap that matters most: bounces you cannot see are still fully visible to AWS, and a
bounce rate high enough to trigger review — then a sending pause — takes down every environment at
once, since they share one domain.

```bash
REGION=<your-region>
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)

aws sesv2 create-configuration-set --configuration-set-name procurepal-prod --region $REGION
TOPIC=$(aws sns create-topic --name procurepal-ses-events --region $REGION --query TopicArn --output text)

# Let SES publish to the topic.
aws sns set-topic-attributes --topic-arn "$TOPIC" --attribute-name Policy --region $REGION \
  --attribute-value '{
    "Version":"2012-10-17",
    "Statement":[{
      "Effect":"Allow",
      "Principal":{"Service":"ses.amazonaws.com"},
      "Action":"sns:Publish",
      "Resource":"'"$TOPIC"'",
      "Condition":{"StringEquals":{"AWS:SourceAccount":"'"$ACCOUNT"'"}}
    }]
  }'

aws sesv2 create-configuration-set-event-destination \
  --configuration-set-name procurepal-prod \
  --event-destination-name bounces-and-complaints \
  --region $REGION \
  --event-destination '{
    "Enabled": true,
    "MatchingEventTypes": ["BOUNCE","COMPLAINT"],
    "SnsDestination": {"TopicArn": "'"$TOPIC"'"}
  }'
echo "$TOPIC"   # -> SES_SNS_TOPIC_ARN
```

### 3. Deploy with the ARN set, **then** subscribe

`SES_SNS_TOPIC_ARN` must be set **and live** before you subscribe the endpoint. With it blank and
signature checking on (both defaults), the endpoint refuses everything — including SNS's
subscription confirmation, which then sits at *Pending confirmation* looking like a broken deploy.

Deploy with production variables:
```bash
EMAIL_ENABLED=true
EMAIL_FROM_ADDRESS=no-reply@yourdomain.com
EMAIL_FROM_NAME=ProcurePal
EMAIL_REGION=REGION
EMAIL_ROLE_ARN=arn:aws:iam::ACCOUNT:role/procurepal-ses-prod
EMAIL_ROLE_SESSION_NAME=stock-bridge-api-ses-prod
EMAIL_REPLY_TO_ADDRESS=support@yourdomain.com
EMAIL_CONFIGURATION_SET=procurepal-prod
EMAIL_APP_BASE_URL=https://app.yourdomain.com          # frontend
EMAIL_UNSUBSCRIBE_API_BASE_URL=https://api.yourdomain.com   # backend — different on purpose
EMAIL_UNSUBSCRIBE_SECRET=<openssl rand -base64 48>
SES_SNS_TOPIC_ARN=<the ARN above>
SES_SNS_REGION=REGION
SES_SNS_REQUIRE_SIGNATURE=true
```

Then subscribe, and confirm it went through:
```bash
aws sns subscribe --topic-arn "$TOPIC" --protocol https \
  --notification-endpoint https://api.yourdomain.com/api/webhooks/ses/notifications --region $REGION

aws sns list-subscriptions-by-topic --topic-arn "$TOPIC" --region $REGION \
  --query 'Subscriptions[].SubscriptionArn'
```
An ARN means confirmed. The literal string `PendingConfirmation` means the app refused it — check
`SES_SNS_TOPIC_ARN` is actually set in the running process, then hit **Request confirmation** in the
SNS console.

---

## Smoke tests, in order

Run 1–3 before any customer traffic reaches the system.

### 1. The simulator — safe, and proves the whole loop

AWS's simulator addresses generate **real** bounces and complaints without touching your reputation.
This is the only way to test the webhook end-to-end that is safe to run in production.

| Address | Expected effect |
|---|---|
| `success@simulator.amazonses.com` | Delivered, nothing suppressed |
| `bounce@simulator.amazonses.com` | Address suppressed **and** `is_email_verified` cleared |
| `complaint@simulator.amazonses.com` | `receive_promotional_email` cleared, address suppressed |
| `ooto@simulator.amazonses.com` | **Nothing changes** — the most important assertion here |

Create a throwaway tenant per address (signup sends a `VERIFICATION` email, which bypasses gating,
so it reaches the simulator without any further setup). Then:

```sql
SELECT address, reason, diagnostic, created_at FROM email_suppressions ORDER BY created_at DESC LIMIT 5;
SELECT message_id, notification_type, signature_valid, processed FROM ses_notification_events
  ORDER BY created_at DESC LIMIT 5;
```

`ooto@` must leave **both tables' effects absent** — an event row, but no suppression and no change
to `is_email_verified`. If a transient bounce demotes an address, stop and fix it before real
traffic: a customer with a full mailbox would otherwise lose their order receipts permanently.

### 2. A real signup with a real address

Sign up with a personal address on a mainstream provider (Gmail, Outlook). Confirm:

- Both emails arrive **in the inbox, not spam** — if spam, revisit DKIM/SPF/DMARC before anything else.
- Sender shows as `ProcurePal`, not a raw address.
- The verification link opens `https://app.yourdomain.com/verify-email?token=…` and confirms.
- Replying reaches `EMAIL_REPLY_TO_ADDRESS`.
- View the raw source: `DKIM-Signature` present, `dkim=pass` and `spf=pass` in
  `Authentication-Results`.

### 3. A real order

Place a real order end-to-end and confirm the buyer receipt and the operator's new-order alert both
arrive, with correct totals and working links.

**If the buyer receipt does not arrive**, the recipient is almost certainly not app-verified — the
silent gate. Check:
```sql
SELECT username, email, is_email_verified FROM users WHERE email = '<address>';
```

### 4. Unsubscribe headers

Nothing sends promotional mail yet, so there is nothing to check in an inbox today. When you add a
promotional sender, verify in the raw source that `List-Unsubscribe` and `List-Unsubscribe-Post` are
both present, that Gmail renders its unsubscribe control, and that clicking it flips
`receive_promotional_email` to false — while order receipts keep arriving.

---

## What to watch in the first week

```bash
aws sesv2 get-account --region REGION --query '{Quota:SendQuota, Enabled:SendingEnabled}'
```

Alarm on the configuration set's bounce and complaint rates via CloudWatch. AWS's thresholds:

| Metric | Keep below | Account under review | Sending may be paused |
|---|---|---|---|
| Bounce rate | 2% | 5% | 10% |
| Complaint rate | 0.1% | 0.1% | 0.5% |

The application deliberately cannot see delivery outcomes — `EmailSender` learns only whether SES
*accepted* a message, which is not the same as it arriving. Everything after that point is the
configuration set's job, which is why blank `EMAIL_CONFIGURATION_SET` is a production defect rather
than a missing nicety.

Also worth a look after a week:
```sql
-- Suppressions accumulating faster than expected means bad address data upstream.
SELECT reason, count(*) FROM email_suppressions GROUP BY reason;
-- Signature failures mean somebody is probing the webhook.
SELECT count(*) FROM ses_notification_events WHERE signature_valid = FALSE;
```

---

## Rollback

Set **`EMAIL_ENABLED=false`** and redeploy. Sending stops immediately; every other feature keeps
working, and the app logs what it would have sent. Nothing is lost and nothing is queued.

This is also the switch for **restoring production data into staging** — set it before the restore,
or staging will email real customers about orders being replayed.

Two things it does *not* undo, because they are database state rather than configuration:
suppressions (reversible only via `EmailSuppressionService.unsuppress`, deliberately not an
endpoint) and `is_email_verified` flags cleared by bounces (the user re-verifies).

---

## Known-unverified before you start

Neither can be proved without a live SES account, so treat the first production send as the test:

1. **`List-Unsubscribe` acceptance by SES.** The SDK field is confirmed present and the value is
   RFC-correct, but AWS rejects certain reserved headers and that has never been exercised against a
   real account. Low risk, zero impact today — nothing sends promotional mail.
2. **The first real bounce.** The handler is thoroughly tested against captured payloads; the
   simulator run in step 1 is what turns that into evidence.
