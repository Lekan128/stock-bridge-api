# Webhooks & External URLs

Every URL in this system that something **outside it** calls, or that you must paste into a
third-party dashboard during setup. If you are standing up a new environment, this is the
checklist.

Two of these are true webhooks (a provider POSTs to us, unprompted). Two are URLs a provider
sends a *person* to. They are listed together because they share one failure mode: none of them
is exercised by local development, all of them are configured somewhere other than this repo,
and a wrong one is silent until a customer is affected.

- Backend base URL — the API's own public origin, e.g. `https://api.procurepal.example`.
  **Not** the frontend's. Everything in §1 and §2 hangs off it.
- Frontend base URL — e.g. `https://app.procurepal.example`. Everything in §3 hangs off it.

Env vars named here are documented in [`ENVIRONMENT.md`](../ENVIRONMENT.md) at the repository root;
the AWS/Monnify account setup is in [`DEPLOYMENT.md`](./DEPLOYMENT.md).

---

## 1. Monnify payment webhook

| | |
|---|---|
| **URL** | `POST {BACKEND}/api/payments/monnify/webhook` |
| **Where to add it** | Monnify dashboard → Settings → Webhooks (set the *Transaction Completion* URL) |
| **Auth** | None. Authenticated by the `monnify-signature` header (HMAC-SHA512 keyed with `MONNIFY_SECRET_KEY`) |
| **Env** | `MONNIFY_SECRET_KEY`, `MONNIFY_REQUIRE_WEBHOOK_SIGNATURE` |
| **Handled by** | `payment/PaymentController#webhook` → `MonnifyWebhookService` |

Monnify only sends `monnify-signature` on **production** notifications — sandbox callbacks carry
none. That is what `MONNIFY_REQUIRE_WEBHOOK_SIGNATURE=false` exists for, and it must be `true` in
production.

Relaxing it is survivable here in a way it would not be elsewhere: a callback is only ever a
*trigger*. On receiving one the server calls Monnify's transaction-status API itself and applies
the payment from **that** response, so a forged callback can at most make the server ask Monnify a
question, and Monnify answers with the truth. Every callback is recorded in `payment_webhook_events`
with its real `signature_valid` either way.

---

## 2. SES bounce & complaint notifications (via SNS)

| | |
|---|---|
| **URL** | `POST {BACKEND}/api/webhooks/ses/notifications` |
| **Where to add it** | AWS SNS → your topic → **Create subscription** → Protocol `HTTPS`, Endpoint = the URL above |
| **Then** | AWS SES → Configuration set → **Event destinations** → publish `Bounce` and `Complaint` to that SNS topic |
| **Auth** | None. Authenticated by AWS's SNS message signature, plus a pinned topic ARN |
| **Env** | `SES_SNS_TOPIC_ARN` (**required in production**), `SES_SNS_REQUIRE_SIGNATURE`, `SES_SNS_REGION`, `SES_SNS_CONFIRM_SUBSCRIPTIONS`, `SES_SNS_COMPLAINTS_SUPPRESS_ALL_MAIL` |
| **Handled by** | `email/webhook/SesNotificationController` → `SesNotificationService` |

### Setup order matters

1. Create the SNS topic. Note its ARN.
2. **Set `SES_SNS_TOPIC_ARN` and deploy** — before subscribing.
3. Subscribe the HTTPS endpoint. SNS immediately POSTs a `SubscriptionConfirmation`, which the app
   confirms automatically (it validates the `SubscribeURL` host is a real AWS SNS host first, then
   fetches it). The subscription flips to *Confirmed* on its own.
4. Point the SES configuration set's event destination at the topic.

If you subscribe **before** step 2, the confirmation is refused — with `SES_SNS_TOPIC_ARN` blank
and signature checking on, the endpoint rejects everything (see below), and the subscription stays
*Pending confirmation*. Set the ARN, redeploy, then hit **Request confirmation** in the SNS console.

### Why the topic ARN is required

A valid AWS signature proves only that **some** SNS topic in **some** AWS account sent the message —
and anyone can create a topic and subscribe your public URL to it. Since this endpoint's whole
effect is to stop mail to an address the message names, an unpinned endpoint would let a stranger
cut off any customer they can name. So while `SES_SNS_REQUIRE_SIGNATURE` is `true` (the default and
the only correct production value), a blank `SES_SNS_TOPIC_ARN` causes every notification to be
refused. Blank is tolerated only when signature checking is also off, which is the local-replay
posture.

### What it does on receipt

| Event | Effect |
|---|---|
| Bounce, `Permanent` | Suppresses the address **and** clears `is_email_verified` on every matching user |
| Bounce, `Transient` / `Undetermined` | **Nothing** — a full mailbox must not permanently kill a customer's receipts |
| Complaint | Clears `receive_promotional_email`; also suppresses all mail unless `SES_SNS_COMPLAINTS_SUPPRESS_ALL_MAIL=false` |
| Complaint, `not-spam` | Nothing — that is a reader *rescuing* mail from spam |
| Duplicate `MessageId` | No-op; the retry is still recorded |

Responses: `401` bad signature, `403` wrong topic, `400` malformed or a non-AWS URL, `503`
understood-but-not-applied (SNS retries), `200` everything else — including events it deliberately
ignores, so SNS stops retrying.

Suppression is keyed by the **address**, not by a user id, so it also covers company contact
addresses and the ops alias, which have no user row to flag. Reversing one is
`EmailSuppressionService.unsuppress` — deliberately not an endpoint, since an endpoint that
un-suppresses an arbitrary address lets someone silence an arbitrary customer. It does not restore
`is_email_verified`; the user re-verifies through §3.

---

## 3. Inbound URLs that live in emails

Not webhooks — but they are absolute URLs baked into messages already in customers' inboxes, so a
change breaks links that were sent months ago. Nothing to configure in a dashboard; they just have
to be **right at send time**.

| Purpose | URL | Built from |
|---|---|---|
| One-click unsubscribe (RFC 8058) | `POST {BACKEND}/api/email/unsubscribe?token=…` | `EMAIL_UNSUBSCRIBE_API_BASE_URL` |
| Email verification link | `{FRONTEND}/verify-email?token=…` | `EMAIL_APP_BASE_URL` |

Note the two use **different** base URLs, and it is not an oversight: the unsubscribe endpoint is
called by the recipient's mail provider, which needs the API; the verification link is clicked by a
person, who needs the app. `EMAIL_UNSUBSCRIBE_API_BASE_URL` is the only URL in the email package
that is not a frontend route.

`GET` on the unsubscribe endpoint is a deliberate **405**. Link scanners and corporate mail-security
products follow GETs, and a GET that unsubscribed would let a mail filter silently opt your
customers out. A human-facing unsubscribe page must read the token and **POST** it.

Both are gated by config: promotional mail is *dropped* rather than sent without a working
unsubscribe link if `EMAIL_UNSUBSCRIBE_SECRET` is blank, and the verification email omits its
button if `EMAIL_APP_BASE_URL` is blank.

---

## 4. Browser redirect (Monnify checkout return)

| | |
|---|---|
| **URL** | `{FRONTEND}/checkout/return` |
| **Where to add it** | `MONNIFY_REDIRECT_URL` — sent by this API on every init-transaction call, so there is nothing to paste into Monnify |
| **Constraint** | Must be a route the frontend actually serves, and its host must match `FRONTEND_ORIGIN` |

Not a webhook, and payment is never applied from it — the buyer's browser landing here proves
nothing. It exists so the customer sees a sensible page; the money is settled by §1 and by the
reconciliation sweep.

---

## Per-environment checklist

For each of production and staging:

- [ ] Monnify webhook URL set in the Monnify dashboard, pointing at that environment's backend
- [ ] `MONNIFY_BASE_URL` is `https://api.monnify.com` in production (the default is sandbox)
- [ ] `MONNIFY_REQUIRE_WEBHOOK_SIGNATURE=true`
- [ ] SNS topic created, `SES_SNS_TOPIC_ARN` set **and deployed**, then the HTTPS endpoint subscribed
- [ ] SNS subscription shows **Confirmed**
- [ ] SES configuration set publishes `Bounce` and `Complaint` to that topic
- [ ] `EMAIL_CONFIGURATION_SET` names that same configuration set
- [ ] `EMAIL_APP_BASE_URL` = frontend origin; `EMAIL_UNSUBSCRIBE_API_BASE_URL` = backend origin
- [ ] `EMAIL_UNSUBSCRIBE_SECRET` set (and **not** shared with `JWT_SECRET` — rotating that one is
      routine, rotating this one kills every unsubscribe link ever mailed)
- [ ] `MONNIFY_REDIRECT_URL` points at that environment's `/checkout/return`
- [ ] SES account is **out of the sandbox** — otherwise everything above is correct and real
      customers still receive nothing

### Verifying without waiting for real traffic

- **Bounces**: send to `bounce@simulator.amazonses.com` (permanent) and
  `complaint@simulator.amazonses.com` (complaint). AWS's simulator generates real notifications
  without harming your reputation. Confirm a row lands in `ses_notification_events` and that the
  address appears in `email_suppressions`.
- **Transient**: `ooto@simulator.amazonses.com` — confirm it changes **nothing**, which is the rule
  most worth proving.
- **Monnify**: sandbox callbacks are unsigned, so this needs `MONNIFY_REQUIRE_WEBHOOK_SIGNATURE=false`
  in a non-production environment.
