# SES — Local Testing (while still in the sandbox)

How to send a **real email through SES from your machine** and watch it arrive, before AWS approves
production access.

Reference for every variable: [`SES_ENVIRONMENT_VARIABLES.md`](./SES_ENVIRONMENT_VARIABLES.md).
Once approved: [`SES_PRODUCTION_TESTING.md`](./SES_PRODUCTION_TESTING.md).

---

## The two rules that decide everything below

**1. The SES sandbox restricts the RECIPIENT, not the sender.** You may send only *to* verified
identities. Your domain is verified, so any address at it is a valid recipient. Everything else —
Gmail, a customer's inbox — is rejected with `MessageRejected: Email address is not verified`.

**2. Verified in SES is not the same as verified in the app.** Two independent gates:

| Gate | Question | Failure looks like |
|---|---|---|
| SES sandbox | Is the recipient a verified AWS identity? | `MessageRejected` in the log |
| App (`is_email_verified`) | Has this address confirmed itself in ProcurePal? | Nothing at all — silently dropped |

The second is the one that wastes an afternoon: everything is configured correctly, and no error
appears anywhere, because a dropped message is normal behaviour and logs at INFO.

**The shortcut that makes the first test easy:** welcome, invite and verification emails are
`EmailKind.VERIFICATION`, which **bypasses the app gate entirely** — a new user is unverified by
definition, so gating them would deadlock the feature. So *signup mail sends to any SES-verified
address with no app-side setup*. Start there, always.

---

## Step 1 — Make sure you can receive

You need a mailbox you can actually open. A verified *domain* proves you control DNS; it does not
create mailboxes.

- **If `@yourdomain.com` has real mailboxes**, use one. Nothing more to do.
- **If it does not**, verify your personal address as its own identity — this works in the sandbox
  and is the fastest path:

```bash
aws sesv2 create-email-identity --email-identity you@gmail.com --region REGION
# AWS emails you a confirmation link. Click it, then:
aws sesv2 get-email-identity --email-identity you@gmail.com --region REGION \
  --query VerifiedForSendingStatus     # must be true
```

Both are fine. The rest of this document says `<YOUR_INBOX>` for whichever you chose.

## Step 2 — Confirm the role chain works

Before involving the application at all, prove the credentials can assume the SES role:

```bash
ROLE=$(aws iam get-role --role-name procurepal-ses-local --query Role.Arn --output text)
aws sts assume-role --role-arn "$ROLE" --role-session-name local-check \
  --query 'Credentials.Expiration'
```

A timestamp means the chain is good. An error here is one of three things — role lacks
`ses:SendEmail`, user lacks `sts:AssumeRole`, or the trust policy does not name the user. See
`SES_ENVIRONMENT_VARIABLES.md` § `EMAIL_ROLE_ARN` for the check for each.

## Step 3 — Configure `.env`

That is `stock-bridge-api/.env` — the same directory as this document.

```bash
# --- SES ---
EMAIL_ENABLED=true
EMAIL_FROM_ADDRESS=no-reply@yourdomain.com     # any address at the verified domain
EMAIL_FROM_NAME=ProcurePal (local)
EMAIL_REGION=REGION                            # where the identity is verified
EMAIL_ROLE_ARN=arn:aws:iam::ACCOUNT:role/procurepal-ses-local
EMAIL_ROLE_SESSION_NAME=stock-bridge-api-ses-local
EMAIL_REPLY_TO_ADDRESS=<YOUR_INBOX>
EMAIL_APP_BASE_URL=http://localhost:5173       # frontend, so links resolve

# --- Existing AWS user keys: unchanged, still the only long-lived secret ---
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...

# --- Leave blank locally ---
EMAIL_CONFIGURATION_SET=
SES_SNS_TOPIC_ARN=
EMAIL_UNSUBSCRIBE_SECRET=                      # already set in application-local.yml
```

Also fix the unrelated CORS value while you are in this file — `FRONTEND_ORIGIN=*` is invalid for
credentialed requests and is what makes four tests fail:
```bash
FRONTEND_ORIGIN=http://localhost:5173,http://localhost:3000
```

## Step 4 — Start it and read the startup line

From this directory (`stock-bridge-api`):

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw spring-boot:run
```

**Check for this warning:**
```
Email is not configured (app.email.enabled must be true and app.email.from-address must be set ...)
```
If it appears, `.env` was not picked up — nothing will send. If it is **absent**, SES is live.

---

## Test A — Signup (start here)

The best first test: it bypasses the app-side gate, so only SES has to be right.

```bash
curl -X POST http://localhost:8080/api/clients/signup \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "SES Smoke Test Co",
    "adminEmail": "<YOUR_INBOX>",
    "password": "supersecret123",
    "confirmPassword": "supersecret123"
  }'
```

**Expected:** a 200 with tokens, and **two emails** arrive — a welcome message and a verification
link. Neither carries a `List-Unsubscribe` header, which is correct: that header belongs only on
promotional mail, and inviting someone to unsubscribe from their own account setup would be a bug.

**In the log, success is silent.** Failure is not:

| Log line | Meaning |
|---|---|
| *(nothing)* | Sent. SES accepted it. |
| `Email not configured - would have sent "..."` | `.env` not loaded, or `EMAIL_ENABLED=false` |
| `Failed to send email ... MessageRejected: Email address is not verified` | Sandbox: recipient not verified — Step 1 |
| `Failed to send email ... AccessDenied` | Role chain — Step 2 |
| `Failed to send email ... Could not connect / UnknownHost` | `EMAIL_REGION` wrong |

To see confirmation rather than silence, run with debug logging:
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./mvnw spring-boot:run \
  -Dspring-boot.run.arguments=--logging.level.com.procurepal_services.stock_bridge_api.email=DEBUG
```
You then get `Sent email "..." to [...] (SES message id ...)`.

## Test B — Click the verification link

The email's link points at `http://localhost:5173/verify-email?token=…`. With the frontend running
(`cd ../stock-bridge-ui && npm run dev`), click it.

**Expected:** a confirmation page, and — if you are signed in — the amber "verify your email" banner
disappears without a reload.

Confirm in the database:
```bash
PGPASSWORD=stock_bridge_local_dev psql -h localhost -p 5433 -U stock_bridge -d stock_bridge \
  -c "SELECT username, is_email_verified, email_verified_at FROM users WHERE username = '<YOUR_INBOX>';"
```
`is_email_verified` should now be `t`, with `email_verified_at` **later than** `created_at` — that
is what distinguishes a genuinely confirmed row from a grandfathered one.

Test the failure path too: POST the same token again. It must return `400` — single-use.

## Test C — A transactional email (proves the app-side gate)

Now that the address is verified, order mail will reach it. Sign in as the user from Test A, place
an order through the storefront, and expect an order-confirmation email.

**If nothing arrives**, the address is not app-verified — repeat Test B. This is the gate that fails
silently, and it is the whole point of testing it separately from Test A.

## Test D — Bounce handling (no webhook needed)

AWS's mailbox simulator works **in the sandbox, without verification**, and does not harm your
reputation:

```bash
# In the app: change a test user's email to one of these, verify it, then trigger any email.
bounce@simulator.amazonses.com      # hard bounce
complaint@simulator.amazonses.com   # complaint
ooto@simulator.amazonses.com        # transient (out of office)
success@simulator.amazonses.com     # clean delivery
```

Locally there is no SNS topic, so nothing calls the webhook and no flags change — that is expected.
To exercise the handler itself, POST a captured payload with `SES_SNS_REQUIRE_SIGNATURE=false`.
The full loop belongs in staging; see `SES_PRODUCTION_TESTING.md`.

`ooto@` is the case most worth proving eventually: a transient bounce must change **nothing**.

---

## Fresh-database gotcha

The demo users seeded into a **new** database (`docker compose down -v`, a new machine, CI) are
created *after* the migration that added `is_email_verified`, so they take its default of `false`
and receive no transactional mail — while an older database has them grandfathered to `true`. Two
developers following these steps would get different results depending on when they first ran the
project.

`src/main/resources/db/seed/V9002__seed_verified_demo_emails.sql` fixes this by marking demo users verified. It is in
`db/seed`, which production never loads. If you are on an older database, nothing changes for you.

## Sandbox limits

- **200 messages per 24 hours**, **1 per second.** A signup sends two, so budget accordingly.
- Only verified recipients. Every `MessageRejected` still counts toward your bounce rate — don't
  loop over unverified addresses.
- Check where you stand:
  `aws sesv2 get-account --region REGION --query '{ProductionAccess:ProductionAccessEnabled, Quota:SendQuota}'`
  — `ProductionAccess: false` means you are still in the sandbox; `true` means it has been granted.

## Turning it off again

`EMAIL_ENABLED=false`, or blank `EMAIL_FROM_ADDRESS`. The app keeps working and logs what it would
have sent — which is also the fastest way to inspect recipients and subjects without sending
anything.
